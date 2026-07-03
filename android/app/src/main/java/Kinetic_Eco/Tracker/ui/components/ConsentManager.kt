package Kinetic_Eco.Tracker.ui.components

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ConsentManager"

/**
 * Process-lifetime gate that runs Google's User Messaging Platform (UMP) consent
 * flow **before** any ad is requested, then initializes the Mobile Ads SDK once
 * consent (where legally required, e.g. GDPR in the EEA/UK) allows it.
 *
 * AdMob policy requires a consent message for users in the EEA/UK. Neither the
 * banner ([AdMobBanner]) nor the interstitial ([InterstitialAdManager]) may load
 * until [canRequestAds] turns true — both observe / check it.
 *
 * Flow (per Google's UMP guide):
 *   1. [requestConsentInfoUpdate] refreshes the user's consent status.
 *   2. [loadAndShowConsentFormIfRequired] shows the form only when needed
 *      (first EEA/UK launch, or after the status expires / is reset).
 *   3. When [ConsentInformation.canRequestAds] is true we flip [canRequestAds]
 *      and initialize the Mobile Ads SDK exactly once.
 *
 * Consent for a returning user is cached by the SDK, so on later launches
 * [ConsentInformation.canRequestAds] is already true and ads init immediately
 * without showing a form.
 */
internal object ConsentManager {

    private val _canRequestAds = MutableStateFlow(false)

    /** True once consent (where required) permits ad requests. Observed by [AdMobBanner]. */
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    /** Guards against initializing the Mobile Ads SDK more than once. */
    private val mobileAdsInitialized = AtomicBoolean(false)

    /**
     * Hashed IDs of devices that should be treated as EEA test devices in **debug
     * builds only** (ignored in release). Each ID forces [ConsentDebugSettings]
     * with [ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA] so the GDPR
     * consent form appears regardless of your real location.
     *
     * To find a device's hashed ID: run a debug build once and look in Logcat for
     * a line like:
     *   "Use new ConsentDebugSettings.Builder().addTestDeviceHashedId("ABCD1234…")"
     * Copy that ID here, then reinstall. Leaving this empty keeps debug geography
     * forcing off (the form still shows on a real EEA device / emulator locale).
     */
    private val TEST_DEVICE_HASHED_IDS: List<String> = listOf(
        // "PASTE_YOUR_TEST_DEVICE_HASHED_ID_HERE",
    )

    /**
     * Kick off the consent flow. Must be called with an [Activity] so a consent
     * form can be presented. Safe to call on every launch — it no-ops the form
     * when consent is already on file. Errors are logged and, per Google's
     * guidance, ads are still requested if [ConsentInformation.canRequestAds]
     * permits (a form-load failure does not revoke prior consent).
     */
    fun gatherConsent(activity: Activity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder()
            .apply { debugSettings(activity)?.let { setConsentDebugSettings(it) } }
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError: FormError? ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form error code=${formError.errorCode}: ${formError.message}")
                    }
                    maybeEnableAds(activity, consentInformation)
                }
            },
            { requestError: FormError ->
                Log.w(TAG, "Consent info update failed code=${requestError.errorCode}: ${requestError.message}")
                // A stale/failed refresh should not block ads if consent is already on file.
                maybeEnableAds(activity, consentInformation)
            }
        )

        // If consent was already obtained on a previous launch, we can enable ads
        // immediately without waiting for the (no-op) form callback above.
        maybeEnableAds(activity, consentInformation)
    }

    /**
     * Builds [ConsentDebugSettings] for debuggable builds so the consent form can
     * be exercised from a non-EEA location. Returns null in release, or in debug
     * when no test device IDs are configured (nothing to force).
     */
    private fun debugSettings(context: Context): ConsentDebugSettings? {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable || TEST_DEVICE_HASHED_IDS.isEmpty()) return null
        return ConsentDebugSettings.Builder(context)
            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            .apply { TEST_DEVICE_HASHED_IDS.forEach { addTestDeviceHashedId(it) } }
            .build()
    }

    private fun maybeEnableAds(context: Context, consentInformation: ConsentInformation) {
        if (!consentInformation.canRequestAds()) return
        initializeMobileAds(context)
        _canRequestAds.value = true
    }

    private fun initializeMobileAds(context: Context) {
        if (!mobileAdsInitialized.compareAndSet(false, true)) return
        try {
            MobileAds.initialize(context.applicationContext) {
                Log.d(TAG, "Mobile Ads SDK initialized")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MobileAds.initialize failed — app continues without ads", t)
            mobileAdsInitialized.set(false)
        }
    }
}