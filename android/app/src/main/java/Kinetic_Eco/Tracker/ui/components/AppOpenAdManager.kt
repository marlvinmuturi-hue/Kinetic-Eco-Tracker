package Kinetic_Eco.Tracker.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.services.EntitlementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AppOpenAdManager"

/** Google test app-open unit — returned for every debuggable build. */
private const val TEST_APP_OPEN_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"

/**
 * App-open ads expire 4 h after they load (Google's documented freshness window). A cached ad older
 * than this is discarded and reloaded rather than shown.
 */
private const val AD_EXPIRY_MS = 4 * 60 * 60 * 1_000L

/**
 * Minimum wall-clock gap between two app-open shows. The user prefers minimal intrusion, so a user
 * who bounces in and out of the app repeatedly sees at most one app-open ad per hour. Tune here.
 */
private const val MIN_INTERVAL_BETWEEN_SHOWS_MS = 60 * 60 * 1_000L

/**
 * Process-lifetime singleton that shows a single App Open ad when the user brings the app to the
 * foreground, per Google's App Open implementation guide.
 *
 * How it works:
 *   - [register] hooks [ProcessLifecycleOwner] (to learn when the app foregrounds) and
 *     [Application.ActivityLifecycleCallbacks] (to know which [Activity] to show the ad over). It
 *     also watches [ConsentManager.canRequestAds] and pre-loads an ad the moment consent permits,
 *     so one is ready for the *next* foreground.
 *   - On each foreground ([onStart]) it shows the pre-loaded ad if every guard passes.
 *
 * Guards (in addition to any AdMob dashboard settings):
 *   - **Consent**: never loads or shows before [ConsentManager.canRequestAds] is true (GDPR/EEA/UK).
 *   - **Gate**: [gateOpen] must be true. MainActivity sets it to reflect whether the user is in the
 *     main app, so no ad shows over the consent form, login, onboarding, or terms.
 *   - **External-return suppression**: [suppressNextForegroundAd] marks the next foreground as a
 *     return from an app-initiated external flow (Google Sign-In, Play Store, camera, share sheet),
 *     which must not be interrupted by an ad.
 *   - **Freshness**: a cached ad older than [AD_EXPIRY_MS] is discarded.
 *   - **Frequency**: at most one show per [MIN_INTERVAL_BETWEEN_SHOWS_MS].
 *   - **No stacking**: never shows while an app-open ad is already on screen.
 *
 * Cold start intentionally does not show an ad: consent has not resolved and nothing is pre-loaded
 * yet, so the first qualifying show happens on a later foreground.
 */
object AppOpenAdManager : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    @Volatile private var appOpenAd: AppOpenAd? = null
    @Volatile private var loading = false
    @Volatile private var showing = false
    private var loadedAtMs = 0L
    private var lastShownMs = 0L

    private var currentActivity: Activity? = null
    private var appContext: Context? = null
    private var registered = false

    /**
     * When true, a qualifying foreground may show an ad. MainActivity keeps this in sync with whether
     * the user is in the main app (signed in, past login/terms/onboarding) so app-open ads never
     * cover the consent form or the pre-app flows.
     */
    @Volatile var gateOpen: Boolean = false

    /**
     * Set just before the app launches an external activity it initiated (Google Sign-In, Play Store,
     * camera/gallery, share sheet). The subsequent foreground is a return from that flow — not a fresh
     * app open — so it must not be interrupted by an ad. Consumed on the next [onStart].
     */
    @Volatile private var suppressNextForeground = false

    /**
     * Wire up the foreground/activity observers and start pre-loading once consent permits. Must run
     * on the main thread (call from [Application.onCreate]). Idempotent.
     */
    fun register(application: Application, scope: CoroutineScope) {
        if (registered) return
        registered = true
        appContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // Pre-load as soon as consent permits so an ad is ready for the next foreground. The SDK is
        // initialized by ConsentManager before canRequestAds flips true, so load() is safe here.
        // Collect on the main dispatcher: AppOpenAd.load() must run on the main thread (the SDK
        // throws "Must be called on the main UI thread" otherwise), and [scope] is Dispatchers.Default.
        scope.launch(Dispatchers.Main) {
            ConsentManager.canRequestAds.collect { canRequest ->
                if (canRequest) loadAd()
            }
        }
    }

    /** Suppress the ad on the next foreground (return from an app-initiated external flow). */
    fun suppressNextForegroundAd() {
        suppressNextForeground = true
    }

    // ── ProcessLifecycleOwner: app moved to the foreground ────────────────────────────────────────
    override fun onStart(owner: LifecycleOwner) {
        // Consume the suppression flag unconditionally so it can never leak into a later, unrelated
        // foreground.
        val suppressed = suppressNextForeground
        suppressNextForeground = false
        if (suppressed) {
            Log.d(TAG, "Foreground is a return from an app-initiated external flow — skipping ad")
            return
        }
        showAdIfAvailable()
    }

    private fun isAdAvailable(): Boolean {
        if (appOpenAd == null) return false
        return System.currentTimeMillis() - loadedAtMs < AD_EXPIRY_MS
    }

    private fun loadAd() {
        val ctx = appContext ?: return
        // Premium users never see an app-open ad, so never fetch one.
        if (EntitlementRepository.isPremiumNow()) return
        if (!ConsentManager.canRequestAds.value) return
        if (loading || isAdAvailable()) return
        loading = true
        val isDebuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val unitId = if (isDebuggable) TEST_APP_OPEN_UNIT_ID
                     else ctx.getString(R.string.admob_app_open_unit_id)
        AppOpenAd.load(
            ctx,
            unitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    loadedAtMs = System.currentTimeMillis()
                    loading = false
                    Log.d(TAG, "Loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    Log.w(TAG, "Load failed code=${error.code}: ${error.message}")
                }
            }
        )
    }

    private fun showAdIfAvailable() {
        if (showing) return
        // Independent of [gateOpen], which tracks where the user is in the app.
        // Entitlement is a separate axis and must not be folded into it.
        if (EntitlementRepository.isPremiumNow()) return
        if (!gateOpen) return
        if (!ConsentManager.canRequestAds.value) return
        val now = System.currentTimeMillis()
        if (lastShownMs != 0L && now - lastShownMs < MIN_INTERVAL_BETWEEN_SHOWS_MS) return
        val activity = currentActivity ?: return
        val ad = appOpenAd?.takeIf { isAdAvailable() }
        if (ad == null) {
            // Nothing fresh to show — make sure one is loading for next time.
            loadAd()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                showing = true
                lastShownMs = System.currentTimeMillis()
                Log.d(TAG, "Shown")
            }
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                showing = false
                loadAd()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                showing = false
                Log.w(TAG, "Show failed: ${error.message}")
                loadAd()
            }
        }
        ad.show(activity)
    }

    // ── Application.ActivityLifecycleCallbacks: track the activity to show the ad over ─────────────
    // currentActivity is set on start (not just resume) so it is populated before ProcessLifecycleOwner
    // dispatches onStart on a foreground.
    override fun onActivityStarted(activity: Activity) { currentActivity = activity }
    override fun onActivityResumed(activity: Activity) { currentActivity = activity }
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}