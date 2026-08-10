package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import Kinetic_Eco.Tracker.data.EnergyPrices
import Kinetic_Eco.Tracker.data.PriceSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Currency
import java.util.Locale

/**
 * Resolves what a litre of fuel or a kWh actually costs the user — or admits it does
 * not know.
 *
 * Precedence, highest first:
 *  1. **The user's own price**, in the user's own currency. Someone who paid €1.75
 *     knows better than any table, and outside regulated markets it is the only
 *     workable answer.
 *  2. **`energyPrices/{country}` in Firestore**, refreshed monthly. Kenya is sourced
 *     from EPRA's published caps and the Kenya Power tariff.
 *  3. **The compiled-in seed — for Kenya only.**
 *
 * When none of those apply the flow emits **null**, and every money figure in the app
 * disappears rather than being quoted in the wrong currency. That is the whole point:
 * the argument for showing cost at all was that a wrong money figure is worse than
 * none, and a French user shown Kenyan shillings is the worst version of exactly that.
 */
object EnergyPriceRepository {

    private const val TAG = "EnergyPriceRepository"
    private const val COLLECTION = "energyPrices"

    /**
     * Null means "no idea what energy costs here" — not zero, and not a fallback to
     * some other country's prices. Consumers must hide cost entirely when it is null.
     */
    private val _prices = MutableStateFlow<EnergyPrices?>(null)
    val prices: StateFlow<EnergyPrices?> = _prices.asStateFlow()

    private var appContext: Context? = null

    /** The country whose prices are currently in effect, for display in Settings. */
    private val _region = MutableStateFlow("")
    val region: StateFlow<String> = _region.asStateFlow()

    /**
     * The last table fetched from Firestore, kept so re-resolving after a user edit
     * does not throw it away.
     *
     * Previously each re-resolve passed `_prices.value.takeIf { it.source == REMOTE }`
     * as the base, which works exactly once: after the first override the current value
     * is `USER`, the base becomes null, and every field the user had *not* overridden
     * silently reverted to 0 — so typing a petrol price quietly deleted the remote
     * diesel price.
     */
    private var lastRemote: EnergyPrices? = null

    /**
     * Countries the picker always offers, independent of Firestore.
     *
     * Being listed here is not a claim that we know the prices — only that the user can
     * select the country and type what they pay, in that country's own currency. The
     * list used to be exactly the `energyPrices` collection, which meant an empty
     * collection or a dropped network left the dropdown blank: no way to correct a wrong
     * detection, which is the one job this picker has.
     *
     * **Litre markets only.** The override field is labelled per litre, and someone in a
     * gallon market would type a per-gallon figure that is silently ~3.8x wrong — the
     * exact class of confidently-wrong number this whole feature is built to avoid.
     * Adding the US and the other gallon markets needs a unit-aware input first.
     */
    private val CURATED_REGIONS = listOf(
        // Africa
        "KE", "UG", "TZ", "RW", "ET", "ZA", "NG", "GH", "ZM", "ZW", "MW", "MU", "EG", "MA",
        // Europe
        "GB", "IE", "FR", "DE", "ES", "IT", "NL", "BE", "PT", "PL", "SE", "NO", "DK", "FI",
        "CH", "AT", "GR", "CZ", "RO",
        // Asia-Pacific and the Americas
        "IN", "PK", "BD", "LK", "AE", "SA", "QA", "SG", "MY", "TH", "PH", "ID", "CN", "JP",
        "KR", "AU", "NZ", "CA", "BR", "MX", "AR", "CL"
    )

    /**
     * Apply any saved override immediately, then keep prices in step with who is
     * signed in.
     *
     * Re-refreshing on auth change is not incidental: the GPS fallback in
     * [CountryResolver] reads a stored trip endpoint, which needs a uid. Refreshing
     * only at process start — before anyone has signed in — meant that fallback could
     * never fire, so a device with no SIM was stuck on locale forever.
     */
    fun start(context: Context, scope: CoroutineScope) {
        val ctx = context.applicationContext
        appContext = ctx
        resolve(ctx, remote = null)

        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            scope.launch { runCatching { refresh(auth.currentUser?.uid) } }
        }
    }

    /**
     * Fetch the monthly table for the device's country.
     *
     * Failure is silent by design — whatever was already resolved stays on screen, and
     * a price is not worth an error banner.
     */
    suspend fun refresh(userId: String? = null) {
        val ctx = appContext ?: return
        // SIM, then where they actually drove, then locale — see CountryResolver.
        val region = CountryResolver.resolve(ctx, userId)
        _region.value = region
        if (region.isBlank()) {
            resolve(ctx, remote = null, region = region)
            return
        }
        val remote = try {
            val snap = FirebaseFirestore.getInstance()
                .collection(COLLECTION).document(region.uppercase(Locale.US)).get().await()
            if (!snap.exists()) null else EnergyPrices(
                currencyCode = snap.getString("currencyCode") ?: return,
                petrolPerLitre = snap.getDouble("petrolPerLitre") ?: 0.0,
                dieselPerLitre = snap.getDouble("dieselPerLitre") ?: 0.0,
                electricityPerKwh = snap.getDouble("electricityPerKwh") ?: 0.0,
                source = PriceSource.REMOTE,
                effectiveMonth = snap.getString("effectiveMonth") ?: "—",
                // Whoever publishes prices in this country. Kenya is EPRA; France is a
                // ministry; an uncurated market has none and the UI says so neutrally.
                sourceName = snap.getString("sourceName")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Price refresh failed for $region", e)
            null
        }
        lastRemote = remote
        resolve(ctx, remote, region)
    }

    /**
     * Combine remote/seed prices with any user override.
     *
     * A user override alone is enough to produce prices even with no table at all —
     * that is how someone outside Kenya gets working cost figures today. Fields they
     * have not set stay 0, and [Kinetic_Eco.Tracker.data.MobilityCostCalculator]
     * declines to cost those modes rather than treating them as free.
     */
    private fun resolve(
        context: Context,
        remote: EnergyPrices?,
        region: String = _region.value
    ) {
        val prefs = UserPreferencesManager(context)
        val petrol = prefs.getFuelPriceOverride(UserPreferencesManager.FuelKind.PETROL)
        val diesel = prefs.getFuelPriceOverride(UserPreferencesManager.FuelKind.DIESEL)
        val electricity = prefs.getFuelPriceOverride(UserPreferencesManager.FuelKind.ELECTRICITY)
        val hasOverride = petrol != null || diesel != null || electricity != null

        val base = remote ?: EnergyPrices.seedFor(region)

        _prices.value = when {
            hasOverride -> {
                // The override's currency wins. Previously only the *number* was
                // overridden, so a French user typing 1.75 was shown "KES 1.75" —
                // a figure that is now confidently wrong rather than obviously wrong.
                // Region before device: someone who picked France and typed 1.75 means
                // euros, whatever their phone's locale says. Falling straight to the
                // locale is how an en-KE handset labelled a French price "KES".
                val currency = prefs.getPriceCurrencyOverride()
                    ?: base?.currencyCode
                    ?: currencyFor(region)
                    ?: deviceCurrency()
                EnergyPrices(
                    currencyCode = currency,
                    petrolPerLitre = petrol ?: base?.petrolPerLitre ?: 0.0,
                    dieselPerLitre = diesel ?: base?.dieselPerLitre ?: 0.0,
                    electricityPerKwh = electricity ?: base?.electricityPerKwh ?: 0.0,
                    source = PriceSource.USER,
                    effectiveMonth = base?.effectiveMonth ?: "—",
                    sourceName = base?.sourceName
                )
            }
            else -> base
        }
    }

    /**
     * Save (or clear, with null) a user price and re-resolve immediately.
     *
     * The currency is captured at the same time and defaults to the device's, so a user
     * outside a served region gets a sensible unit without being asked to pick one.
     */
    fun setOverride(
        context: Context,
        kind: UserPreferencesManager.FuelKind,
        value: Double?,
        currencyCode: String = currentCurrencyCode()
    ) {
        val ctx = context.applicationContext
        val prefs = UserPreferencesManager(ctx)
        prefs.setFuelPriceOverride(kind, value)
        if (value != null) prefs.setPriceCurrencyOverride(currencyCode)
        resolve(ctx, remote = lastRemote)
    }

    /** Currency currently in effect, for prefilling the override field. */
    fun currentCurrencyCode(): String =
        _prices.value?.currencyCode ?: currencyFor(_region.value) ?: deviceCurrency()

    /**
     * Countries offered by the Settings picker.
     *
     * A union, because each source alone has a hole: the `energyPrices` collection so a
     * newly maintained country appears without an app update, [CURATED_REGIONS] so the
     * list is never empty offline, and the region currently in effect so the active
     * choice is always present in the list it came from.
     *
     * Returned in code order; the caller sorts by display name, which is the only
     * ordering that makes sense once the codes are rendered as country names.
     */
    suspend fun availableRegions(): List<String> {
        val remote = try {
            FirebaseFirestore.getInstance().collection(COLLECTION).get().await()
                .documents.map { it.id }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list price regions", e)
            emptyList()
        }
        return (CURATED_REGIONS + remote + _region.value)
            .map { it.uppercase(Locale.US) }
            .filter { it.length == 2 }
            .distinct()
    }

    /**
     * Pick a country explicitly, or pass null to go back to detection.
     *
     * Changing country clears any typed price. A price is only meaningful in the country
     * it was typed for — 214 is a fair Nairobi petrol price and an absurd Parisian one —
     * and the captured currency would otherwise follow the user across the border and
     * label their new prices KES.
     */
    suspend fun setRegionOverride(context: Context, code: String?, userId: String? = null) {
        val ctx = context.applicationContext
        val previous = _region.value
        UserPreferencesManager(ctx).setCountryOverride(code)
        refresh(userId)

        if (_region.value != previous) {
            val prefs = UserPreferencesManager(ctx)
            UserPreferencesManager.FuelKind.values().forEach { prefs.setFuelPriceOverride(it, null) }
            prefs.setPriceCurrencyOverride(null)
            resolve(ctx, remote = lastRemote)
        }
    }

    /** ISO currency for a country, e.g. "FR" to "EUR". Null when the pair is unknown. */
    private fun currencyFor(region: String): String? = runCatching {
        Currency.getInstance(Locale("", region.uppercase(Locale.US))).currencyCode
    }.getOrNull()

    /** ISO currency for the device locale, e.g. "EUR". Falls back to the seed's. */
    private fun deviceCurrency(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }
            .getOrDefault(EnergyPrices.KENYA_SEED.currencyCode)
}
