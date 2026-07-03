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

private const val TAG = "InterstitialAdManager"

/** Google test interstitial — returned for every debuggable build. */
private const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

/** 3-minute minimum gap between interstitials (wall-clock, survives session resets). */
private const val COOLDOWN_MS = 3 * 60 * 1_000L

/**
 * Process-lifetime singleton that manages a single pre-loaded interstitial ad.
 *
 * Rules enforced here (in addition to any AdMob dashboard settings):
 *   - At most 1 interstitial shown per tracking session.
 *   - At least [COOLDOWN_MS] (3 min) between successive shows.
 *   - Lazy-load: [preload] kicks off a background load while the user reads their
 *     session summary, so the ad is ready with no wait when [showIfReady] is called.
 *
 * Call [resetSession] each time the user starts a new tracking session so the
 * per-session cap resets correctly.
 */
internal object InterstitialAdManager {

    @Volatile private var pending: InterstitialAd? = null
    @Volatile private var loading = false
    private var shownThisSession = false
    private var lastShownMs = 0L

    /** Reset the per-session shown flag. Call this when a new tracking session starts. */
    fun resetSession() {
        shownThisSession = false
    }

    /**
     * Kick off a background load if no ad is already in flight and the session gate
     * allows another show. Safe to call multiple times — no-ops if already loaded or
     * loading, or if the session cap has been reached.
     */
    fun preload(context: Context) {
        // Do not request ads before UMP consent permits it (GDPR/EEA/UK).
        if (!ConsentManager.canRequestAds.value) return
        if (shownThisSession || loading || pending != null) return
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
        if (shownThisSession) return false
        val now = System.currentTimeMillis()
        if (now - lastShownMs < COOLDOWN_MS) return false
        val ad = pending ?: return false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                shownThisSession = true
                lastShownMs = System.currentTimeMillis()
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