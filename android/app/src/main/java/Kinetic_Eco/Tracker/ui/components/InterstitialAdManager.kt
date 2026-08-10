package Kinetic_Eco.Tracker.ui.components

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.AdFrequencyPolicy
import Kinetic_Eco.Tracker.services.EntitlementRepository
import Kinetic_Eco.Tracker.services.UserPreferencesManager

private const val TAG = "InterstitialAdManager"

/** Google test interstitial — returned for every debuggable build. */
private const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

/**
 * Process-lifetime singleton that manages a single pre-loaded interstitial ad.
 *
 * Rules enforced here (in addition to any AdMob dashboard settings):
 *   - At most 1 interstitial shown per tracking session.
 *   - At least [AdFrequencyPolicy.COOLDOWN_MS] between successive shows.
 *   - At most [AdFrequencyPolicy.MAX_PER_DAY] per local day.
 *   - Lazy-load: [preload] kicks off a background load while the user reads their
 *     session summary, so the ad is ready with no wait when [showIfReady] is called.
 *
 * The cooldown and the daily count live in [UserPreferencesManager], not here. This is
 * an object in a process that Android kills freely between trips, and in-memory
 * counters silently reset with it — which is how a "3 minute" rule became no rule at
 * all for anyone whose app got evicted between outings.
 *
 * Call [resetSession] each time the user starts a new tracking session so the
 * per-session cap resets correctly.
 */
internal object InterstitialAdManager {

    @Volatile private var pending: InterstitialAd? = null
    @Volatile private var loading = false
    private var shownThisSession = false

    /** Reset the per-session shown flag. Call this when a new tracking session starts. */
    fun resetSession() {
        shownThisSession = false
    }

    /**
     * Every frequency rule in one place, checked before loading *and* before showing.
     *
     * Applied at load time too, deliberately: an ad we are not allowed to show is a
     * download the user paid for in data and battery for nothing, and an impression
     * AdMob counted as unfilled.
     */
    private fun allowedNow(context: Context): Boolean {
        val prefs = UserPreferencesManager(context.applicationContext)
        val decision = AdFrequencyPolicy.decide(
            isPremium = EntitlementRepository.isPremiumNow(),
            // Do not request or show ads before UMP consent permits it (GDPR/EEA/UK).
            canRequestAds = ConsentManager.canRequestAds.value,
            shownThisSession = shownThisSession,
            shownToday = prefs.interstitialsShownToday(),
            lastShownMs = prefs.interstitialLastShownMs(),
            nowMs = System.currentTimeMillis()
        )
        if (decision != AdFrequencyPolicy.Decision.ALLOW) {
            Log.d(TAG, "Suppressed: $decision")
        }
        return decision == AdFrequencyPolicy.Decision.ALLOW
    }

    /**
     * Kick off a background load if no ad is already in flight and the session gate
     * allows another show. Safe to call multiple times — no-ops if already loaded or
     * loading, or if the session cap has been reached.
     */
    fun preload(context: Context) {
        // Premium, consent, session cap, cooldown and daily cap all live in
        // allowedNow — checked here rather than only at show time, so a subscriber or
        // a capped-out user never pays bandwidth for an ad that cannot be displayed.
        if (!allowedNow(context)) return
        if (loading || pending != null) return
        loading = true
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val unitId = if (isDebuggable) TEST_INTERSTITIAL_UNIT_ID
                     else context.getString(R.string.admob_interstitial_unit_id)
        InterstitialAd.load(
            context.applicationContext,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    pending = ad
                    loading = false
                    Log.d(TAG, "Pre-loaded successfully")
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    loading = false
                    Log.w(TAG, "Load failed code=${err.code}: ${err.message}")
                }
            }
        )
    }

    /**
     * Attempt to show the pre-loaded ad.
     * Returns true if an ad was shown; false if any guard prevented it
     * (session cap reached, cooldown active, or no ad ready).
     */
    fun showIfReady(activity: Activity): Boolean {
        // Re-checked at show time, not just at load: an ad can sit cached for a long
        // while, and in that window the user may have subscribed, withdrawn consent,
        // or hit the daily cap via another trip.
        if (!allowedNow(activity)) return false
        val ad = pending ?: return false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                shownThisSession = true
                // Recorded on *shown*, never on load or on the attempt — the only
                // event the user actually experiences is the one worth rate-limiting.
                UserPreferencesManager(activity.applicationContext).recordInterstitialShown()
                pending = null
                Log.d(TAG, "Shown")
            }
            override fun onAdDismissedFullScreenContent() {
                pending = null
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                pending = null
                Log.w(TAG, "Show failed: ${e.message}")
            }
        }
        ad.show(activity)
        return true
    }
}