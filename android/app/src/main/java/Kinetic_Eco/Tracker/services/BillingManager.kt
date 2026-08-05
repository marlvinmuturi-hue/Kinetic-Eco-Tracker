package Kinetic_Eco.Tracker.services

import android.app.Activity
import android.content.Context
import android.util.Log
import Kinetic_Eco.Tracker.ui.components.AppOpenAdManager
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Collections

/**
 * The Play Billing half of premium: showing a price, launching the purchase flow,
 * and handing every purchase token to the server.
 *
 * It deliberately does **not** decide entitlement. A successful purchase here only
 * means Play took the money; access is granted when [PlayPurchaseVerifier] posts the
 * token to `verifyPlayPurchase`, that function checks it against the Play Developer
 * API and writes `users/{uid}/entitlements/premium`, and [EntitlementRepository]'s
 * Firestore listener picks the document up. Keeping the two apart is what stops a
 * patched client from granting itself premium — see [EntitlementRepository].
 *
 * Purchases are re-queried on every start and every [refresh]. That single habit
 * covers reinstalls, new devices, purchases that completed while the app was dead,
 * and verification calls that failed on a flaky network — all without a "restore"
 * button, though the paywall offers one anyway because users look for it.
 */
object BillingManager {

    private const val TAG = "BillingManager"

    /**
     * Play Console product id. Must exist under Monetize → Subscriptions with an
     * **active** base plan; a draft base plan returns no offers and the paywall
     * will correctly report that premium is unavailable.
     *
     * This must match the console string exactly and is permanent once created —
     * Play does not allow renaming a product id, so if this ever needs to change,
     * it changes here, not there. Unrelated to the Firestore entitlement document
     * name (also "premium"), which is ours and independent.
     *
     * **Underscore, not hyphen.** Play ids allow only `a-z`, `0-9`, `_` and `.`.
     * An id containing a hyphen is rejected by the Billing Library itself before any
     * network call, surfacing as an unfetched product with
     * `StatusCode.INVALID_PRODUCT_ID_FORMAT` (2) and an empty product id — which
     * looks exactly like a mis-configured Play Console and cost a day of chasing one.
     */
    const val PREMIUM_PRODUCT_ID = "premiumv2"

    /** Whether the store is usable on this device right now. */
    sealed interface Status {
        object Connecting : Status
        object Ready : Status
        /** No Play Store, an old Play Store, or no offers configured. */
        data class Unavailable(val reason: String) : Status
    }

    /** One-shot outcomes of a purchase attempt, for snackbars on the paywall. */
    sealed interface PurchaseEvent {
        /** Play accepted payment; waiting on server verification. */
        object Verifying : PurchaseEvent
        object Success : PurchaseEvent
        object Cancelled : PurchaseEvent
        /** Slow payment method (cash, bank transfer). Nothing to do but wait. */
        object Pending : PurchaseEvent
        /**
         * A user-initiated restore completed and Play reported no owned subscription.
         * Only ever emitted for an explicit tap — the startup sweep stays silent.
         */
        object NothingToRestore : PurchaseEvent
        data class Failed(val message: String) : PurchaseEvent
    }

    /**
     * The offer we will actually charge, resolved from the product's offer list.
     * [formattedPrice] is already localised and currency-correct — never build a
     * price string by hand.
     */
    data class PremiumOffer(
        val productDetails: ProductDetails,
        val offerToken: String,
        val formattedPrice: String,
        /** ISO-8601 recurrence of the paid phase, e.g. "P1M", "P1Y". */
        val billingPeriodIso: String,
        /** ISO-8601 free-trial length when the offer opens with a free phase. */
        val freeTrialIso: String?
    )

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _offer = MutableStateFlow<PremiumOffer?>(null)
    val offer: StateFlow<PremiumOffer?> = _offer.asStateFlow()

    private val _events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PurchaseEvent> = _events.asSharedFlow()

    /**
     * True while a user-initiated [refresh] is in flight, so the paywall can show a
     * spinner. A button that queries Play and then changes nothing on screen is
     * indistinguishable from a broken one — which is exactly how Restore behaved
     * before this existed.
     */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var billingClient: BillingClient? = null
    private var scope: CoroutineScope? = null
    private var started = false

    /**
     * Tokens already accepted by the server in this process. Purely a de-duplication
     * cache so routine re-queries don't re-post the same purchase; it is never
     * consulted to decide whether the user is premium.
     */
    private val verifiedTokens = Collections.synchronizedSet(mutableSetOf<String>())

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                handlePurchases(purchases.orEmpty())

            BillingClient.BillingResponseCode.USER_CANCELED ->
                emit(PurchaseEvent.Cancelled)

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Owned but not reflected here — almost always a verification that
                // never completed. Re-query and push the token up again.
                Log.i(TAG, "Item already owned; re-syncing purchases")
                queryPurchases()
            }

            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} ${result.debugMessage}")
                emit(PurchaseEvent.Failed(userMessage(result)))
            }
        }
    }

    /**
     * Connect to Play and sync any existing purchase. Idempotent; call once from
     * `Application.onCreate` with the application scope.
     */
    fun start(context: Context, appScope: CoroutineScope) {
        if (started) return
        started = true
        scope = appScope

        try {
            billingClient = BillingClient.newBuilder(context.applicationContext)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        // enableOneTimeProducts() is NOT optional even in a
                        // subscriptions-only app: build() throws
                        // "Pending purchases for one-time products must be supported."
                        // without it. Omitting it took the whole app down at launch
                        // in versionCode 23, because this runs in Application.onCreate.
                        .enableOneTimeProducts()
                        // Subscriptions can be prepaid plans, which may be paid by a
                        // slow method and arrive PENDING.
                        .enablePrepaidPlans()
                        .build()
                )
                // Play reconnects the service itself on disconnect (7.1+), so a dropped
                // binding no longer leaves the paywall permanently stuck on "Connecting".
                .enableAutoServiceReconnection()
                .build()
        } catch (t: Throwable) {
            // Nothing about billing is worth a launch crash. The app's entire value —
            // tracking trips — works without it, and a user who cannot subscribe is in
            // far better shape than one who cannot open the app. Mirrors the deliberate
            // non-fatal handling of App Check init in KineticEcoApplication.
            Log.e(TAG, "Billing client init failed — premium purchase disabled this run", t)
            _status.value = Status.Unavailable("Google Play billing isn't available on this device")
            return
        }

        connect()

        // A different account must not inherit the previous one's purchase sync, and
        // a fresh sign-in needs its purchases pushed up so a reinstall restores.
        FirebaseAuth.getInstance().addAuthStateListener {
            verifiedTokens.clear()
            if (it.currentUser != null) queryPurchases()
        }
    }

    private fun connect() {
        val client = billingClient ?: return
        if (client.isReady) return

        _status.value = Status.Connecting
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing connected")
                    queryOffer()
                    queryPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                    _status.value = Status.Unavailable(userMessage(result))
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection handles the retry; just reflect it.
                Log.d(TAG, "Billing service disconnected")
                _status.value = Status.Connecting
            }
        })
    }

    /**
     * Re-read the offer and any owned purchases. Safe to call from onResume.
     *
     * [userInitiated] marks an explicit Restore/Try-again tap, which must always
     * produce visible feedback — a spinner and then a result. The automatic calls
     * stay silent so a cold start never fires a snackbar at nobody.
     */
    fun refresh(userInitiated: Boolean = false, announceEmptyRestore: Boolean = false) {
        val client = billingClient
        if (client == null || !client.isReady) {
            connect()
            if (userInitiated) {
                emit(PurchaseEvent.Failed("Google Play isn't connected yet. Try again in a moment."))
            }
            return
        }
        if (userInitiated) _refreshing.value = true
        queryOffer()
        queryPurchases(userInitiated, announceEmptyRestore)
    }

    // ── Product details ──────────────────────────────────────────────────────

    private fun queryOffer() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PREMIUM_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        client.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Product query failed: ${result.responseCode} ${result.debugMessage}")
                _status.value = Status.Unavailable(userMessage(result))
                return@queryProductDetailsAsync
            }

            // Billing 8.0+ reports *why* a product could not be fetched instead of
            // silently omitting it. Without this the only symptom of a console
            // misconfiguration is an empty list, which is indistinguishable from a
            // wrong product id, a draft base plan, or a country the plan is not
            // priced for. Logged unconditionally — it is the single most useful line
            // in this file when premium "just doesn't show up".
            productDetailsResult.unfetchedProductList.forEach { unfetched ->
                Log.w(TAG, "Unfetched product '${unfetched.productId}': statusCode=${unfetched.statusCode}")
            }

            val details = productDetailsResult.productDetailsList.firstOrNull()
            if (details == null) {
                // Reached Play, but it has nothing to sell. In practice this is the
                // product id not existing, its base plan still being a draft, the base
                // plan not being priced for this account's country, or the build not
                // being on a track the account can see.
                Log.w(
                    TAG,
                    "No product details for '$PREMIUM_PRODUCT_ID' " +
                        "(${productDetailsResult.unfetchedProductList.size} unfetched)"
                )
                _status.value = Status.Unavailable("Premium isn't available on this account yet")
                _offer.value = null
                return@queryProductDetailsAsync
            }

            val resolved = bestOffer(details)
            if (resolved == null) {
                Log.w(TAG, "'$PREMIUM_PRODUCT_ID' has no offer this user is eligible for")
                _status.value = Status.Unavailable("Premium isn't available on this account yet")
                _offer.value = null
                return@queryProductDetailsAsync
            }

            _offer.value = resolved
            _status.value = Status.Ready
        }
    }

    /**
     * Pick which offer to charge.
     *
     * Play has already filtered the list to offers this user is eligible for, so
     * anything here is fair game. We prefer one that starts free — a trial the user
     * qualifies for should never be withheld because a cheaper-looking base plan
     * sorted first — and otherwise take the lowest recurring price.
     */
    private fun bestOffer(details: ProductDetails): PremiumOffer? {
        val offers = details.subscriptionOfferDetails.orEmpty().ifEmpty { return null }

        fun freePhase(o: ProductDetails.SubscriptionOfferDetails) =
            o.pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros == 0L }

        fun paidPhase(o: ProductDetails.SubscriptionOfferDetails) =
            o.pricingPhases.pricingPhaseList.lastOrNull { it.priceAmountMicros > 0L }
                ?: o.pricingPhases.pricingPhaseList.last()

        val chosen = offers.filter { freePhase(it) != null }
            .minByOrNull { paidPhase(it).priceAmountMicros }
            ?: offers.minByOrNull { paidPhase(it).priceAmountMicros }
            ?: return null

        val paid = paidPhase(chosen)
        return PremiumOffer(
            productDetails = details,
            offerToken = chosen.offerToken,
            formattedPrice = paid.formattedPrice,
            billingPeriodIso = paid.billingPeriod,
            freeTrialIso = freePhase(chosen)?.billingPeriod
        )
    }

    // ── Purchase flow ────────────────────────────────────────────────────────

    /** Launch Play's purchase sheet. No-op with a [PurchaseEvent.Failed] if not ready. */
    fun launchPurchase(activity: Activity) {
        val client = billingClient
        val current = _offer.value

        if (client == null || !client.isReady) {
            emit(PurchaseEvent.Failed("Google Play isn't connected yet. Try again in a moment."))
            connect()
            return
        }
        if (current == null) {
            emit(PurchaseEvent.Failed("Premium isn't available on this account yet"))
            refresh()
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            // Entitlement is keyed to the Firebase uid so it survives reinstalls and
            // follows the user across devices. An anonymous purchase has nowhere to go.
            emit(PurchaseEvent.Failed("Please sign in before subscribing"))
            return
        }

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(current.productDetails)
                        .setOfferToken(current.offerToken)
                        .build()
                )
            )
            // Hashed, never the raw uid: Play forbids PII here, and the server
            // compares this against sha256(uid) so a token can't be replayed by
            // another account.
            .setObfuscatedAccountId(sha256(uid))
            .build()

        // Play's sheet backgrounds the app; without this the App Open ad fires as
        // the user comes back from paying us to remove ads.
        AppOpenAdManager.suppressNextForegroundAd()

        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow rejected: ${result.responseCode} ${result.debugMessage}")
            emit(PurchaseEvent.Failed(userMessage(result)))
        }
    }

    private fun queryPurchases(userInitiated: Boolean = false, announceEmptyRestore: Boolean = false) {
        val client = billingClient ?: return
        if (!client.isReady) {
            if (userInitiated) _refreshing.value = false
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        client.queryPurchasesAsync(params) { result, purchases ->
            if (userInitiated) _refreshing.value = false

            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases failed: ${result.responseCode} ${result.debugMessage}")
                if (userInitiated) emit(PurchaseEvent.Failed(userMessage(result)))
                return@queryPurchasesAsync
            }

            // Only the Restore button may say "nothing to restore". "Try again" is
            // about fetching the *price*, and answering it with a message about
            // owned purchases sent the user off recreating a Play Console product
            // that was never the problem.
            if (announceEmptyRestore &&
                purchases.none { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            ) {
                emit(PurchaseEvent.NothingToRestore)
            }
            // An explicit restore should announce what it found; the startup sweep
            // must not, or every cold start congratulates the user on nothing.
            handlePurchases(purchases, fromRestore = !userInitiated)
        }
    }

    /**
     * Route every purchase Play tells us about to the server.
     *
     * [fromRestore] distinguishes the routine startup sweep from a purchase the user
     * is watching happen: the sweep must stay silent, because firing a "Success"
     * snackbar on every cold start would be baffling.
     */
    private fun handlePurchases(purchases: List<Purchase>, fromRestore: Boolean = false) {
        purchases.forEach { purchase ->
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    // Re-verify anything unacknowledged even if we've seen it: an
                    // unacknowledged purchase is one Play will refund in three days.
                    if (purchase.isAcknowledged && verifiedTokens.contains(purchase.purchaseToken)) {
                        return@forEach
                    }
                    verify(purchase, silent = fromRestore)
                }

                Purchase.PurchaseState.PENDING -> {
                    Log.i(TAG, "Purchase pending payment")
                    if (!fromRestore) emit(PurchaseEvent.Pending)
                }

                else -> Unit
            }
        }
    }

    private fun verify(purchase: Purchase, silent: Boolean) {
        val currentScope = scope ?: return
        currentScope.launch {
            if (!silent) emit(PurchaseEvent.Verifying)

            val productId = purchase.products.firstOrNull() ?: PREMIUM_PRODUCT_ID
            when (val outcome = PlayPurchaseVerifier.verify(purchase.purchaseToken, productId)) {
                is PlayPurchaseVerifier.Outcome.Verified -> {
                    verifiedTokens.add(purchase.purchaseToken)
                    Log.d(TAG, "Purchase verified server-side")
                    // The server acknowledges via the Play Developer API. This is a
                    // fallback for the case where it verified but its acknowledge call
                    // failed: an unacknowledged purchase is auto-refunded after three
                    // days, so it is worth a second, harmless attempt from here.
                    if (!purchase.isAcknowledged) acknowledgeLocally(purchase)
                    if (!silent) emit(PurchaseEvent.Success)
                }

                is PlayPurchaseVerifier.Outcome.Rejected -> {
                    Log.w(TAG, "Purchase rejected: ${outcome.message}")
                    if (!silent) emit(PurchaseEvent.Failed(outcome.message))
                }

                is PlayPurchaseVerifier.Outcome.Unreachable -> {
                    // Not marked verified, so the next launch retries it.
                    Log.w(TAG, "Verification unreachable: ${outcome.message}")
                    if (!silent) {
                        emit(
                            PurchaseEvent.Failed(
                                "Payment went through, but we couldn't confirm it yet. " +
                                    "It'll unlock automatically once you're back online."
                            )
                        )
                    }
                }
            }
        }
    }

    private fun acknowledgeLocally(purchase: Purchase) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Local acknowledge failed: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun emit(event: PurchaseEvent) {
        _events.tryEmit(event)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Turn a billing response code into something worth showing a user. */
    private fun userMessage(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play billing isn't available on this device"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
            "Google Play is unreachable right now. Please try again."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "Premium isn't available on this account yet"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "Subscriptions aren't supported by this device's Play Store"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "Store configuration problem — please report this"
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "No connection to Google Play"
        else -> "Purchase couldn't be completed (code ${result.responseCode})"
    }
}