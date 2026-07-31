package Kinetic_Eco.Tracker.services

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import Kinetic_Eco.Tracker.data.Entitlement
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "is this user premium?".
 *
 * Everything that gates on premium — the three ad surfaces today, paid features
 * later — reads [isPremium] and nothing else. One flow means one place to get the
 * answer wrong, and one place to fix it.
 *
 * **Reads only.** The entitlement document is written exclusively by Cloud
 * Functions after server-side verification against the Play Developer API. This
 * class never writes it, and `firestore.rules` denies client writes outright.
 *
 * **Cached locally on purpose.** A paying user must not see ads because their
 * train went into a tunnel. The last known entitlement is persisted and applied
 * immediately at startup, before Firestore has connected. It stays trusted until
 * its own expiry passes, which also means an offline refund keeps working until
 * the period they paid for ends — the standard trade, and the right way round.
 */
object EntitlementRepository {

    private const val TAG = "EntitlementRepository"
    private const val PREFS = "entitlement_cache"
    private const val KEY_EXPIRY_MS = "expiry_ms"
    private const val KEY_PRODUCT_ID = "product_id"
    private const val KEY_WILL_RENEW = "will_renew"
    private const val KEY_ACTIVE = "active"
    /** Debug-build-only override so premium can be exercised before Play Billing lands. */
    private const val KEY_DEBUG_OVERRIDE = "debug_override"

    private val _entitlement = MutableStateFlow(Entitlement.NONE)
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    private val _isPremium = MutableStateFlow(false)
    /** The gate. Collect this; do not re-derive premium from anything else. */
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private var listener: ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var appContext: Context? = null
    private var started = false

    /**
     * Begin tracking entitlement for whoever is signed in, re-binding on sign-in
     * and sign-out. Idempotent; call once from `Application.onCreate`.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        appContext = ctx

        // Apply the cached value synchronously so the very first frame is correct.
        // Without this a subscriber sees a banner for however long Firestore takes
        // to connect, which on a cold start over mobile data is plainly visible.
        applyCachedEntitlement(ctx)

        val auth = FirebaseAuth.getInstance()
        val l = FirebaseAuth.AuthStateListener { firebaseAuth ->
            bindTo(ctx, firebaseAuth.currentUser?.uid)
        }
        authListener = l
        auth.addAuthStateListener(l)
    }

    /** Attach the Firestore listener for [uid], or clear state when signed out. */
    private fun bindTo(context: Context, uid: String?) {
        listener?.remove()
        listener = null

        if (uid.isNullOrEmpty()) {
            // Signed out. Drop the cache too — the next user must not inherit
            // this one's entitlement on a shared device.
            clearCache(context)
            _entitlement.value = Entitlement.NONE
            recompute(context)
            return
        }

        listener = FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection(Entitlement.COLLECTION).document(Entitlement.DOCUMENT_ID)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Keep whatever the cache said. A transient Firestore error is
                    // not evidence that someone stopped paying.
                    Log.w(TAG, "Entitlement listener error; keeping cached value", error)
                    return@addSnapshotListener
                }
                val parsed = if (snapshot != null && snapshot.exists()) {
                    Entitlement(
                        active = snapshot.getBoolean("active") ?: false,
                        productId = snapshot.getString("productId"),
                        expiryMs = snapshot.getLong("expiryMs") ?: 0L,
                        willRenew = snapshot.getBoolean("willRenew") ?: false,
                        source = snapshot.getString("source"),
                        updatedAtMs = snapshot.getLong("updatedAtMs") ?: 0L
                    )
                } else {
                    Entitlement.NONE
                }
                _entitlement.value = parsed
                cache(context, parsed)
                recompute(context)
            }
    }

    /** Recompute [isPremium] from the current entitlement plus any debug override. */
    private fun recompute(context: Context) {
        val overridden = debugOverride(context)
        _isPremium.value = overridden ?: _entitlement.value.isEntitledAt()
    }

    private fun applyCachedEntitlement(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _entitlement.value = Entitlement(
            active = prefs.getBoolean(KEY_ACTIVE, false),
            productId = prefs.getString(KEY_PRODUCT_ID, null),
            expiryMs = prefs.getLong(KEY_EXPIRY_MS, 0L),
            willRenew = prefs.getBoolean(KEY_WILL_RENEW, false),
            source = "cache"
        )
        recompute(context)
    }

    private fun cache(context: Context, entitlement: Entitlement) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, entitlement.active)
            .putString(KEY_PRODUCT_ID, entitlement.productId)
            .putLong(KEY_EXPIRY_MS, entitlement.expiryMs)
            .putBoolean(KEY_WILL_RENEW, entitlement.willRenew)
            .apply()
    }

    private fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_PRODUCT_ID)
            .remove(KEY_EXPIRY_MS)
            .remove(KEY_WILL_RENEW)
            .apply()
    }

    // ── Debug override ────────────────────────────────────────────────────────
    // Lets the premium experience be built and tested before Play Billing exists.
    // Ignored outright in release builds: shipping a flag that grants premium for
    // free would be the whole paywall, defeated by one SharedPreferences write.

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun debugOverride(context: Context): Boolean? {
        if (!isDebuggable(context)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DEBUG_OVERRIDE)) return null
        return prefs.getBoolean(KEY_DEBUG_OVERRIDE, false)
    }

    /** Force premium on/off in debug builds; pass null to fall back to the real entitlement. */
    fun setDebugOverride(context: Context, premium: Boolean?) {
        val ctx = context.applicationContext
        if (!isDebuggable(ctx)) {
            Log.w(TAG, "Debug override ignored in a release build")
            return
        }
        val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (premium == null) editor.remove(KEY_DEBUG_OVERRIDE) else editor.putBoolean(KEY_DEBUG_OVERRIDE, premium)
        editor.apply()
        recompute(ctx)
        Log.d(TAG, "Debug override set to $premium; isPremium=${_isPremium.value}")
    }

    /**
     * Snapshot for non-Compose callers that cannot collect a flow — the ad
     * managers, which are plain singletons invoked from lifecycle callbacks.
     */
    fun isPremiumNow(): Boolean = _isPremium.value
}