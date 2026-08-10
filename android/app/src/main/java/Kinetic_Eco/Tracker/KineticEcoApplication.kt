package Kinetic_Eco.Tracker

import android.app.Application
import android.util.Log
import Kinetic_Eco.Tracker.ui.components.AppOpenAdManager
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import Kinetic_Eco.Tracker.services.BillingManager
import Kinetic_Eco.Tracker.services.EnergyPriceRepository
import Kinetic_Eco.Tracker.services.EntitlementRepository
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KineticEcoApplication : Application() {

    /**
     * Outlives [TrackingService] so work (e.g. auto-stop save) can finish after [stopSelf]
     * without being cancelled with the service's lifecycleScope.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionLogger()
        initAppCheck()
        // NOTE: The Mobile Ads SDK is intentionally NOT initialized here. Ads must
        // not be requested before the UMP consent flow runs (GDPR/EEA/UK). Consent
        // is gathered in MainActivity via ConsentManager.gatherConsent(), which
        // initializes the SDK only once ConsentInformation.canRequestAds() is true.
        //
        // App Open ads must observe the app's whole foreground lifecycle, so their
        // manager is registered here (in the Application) rather than in an Activity.
        // This only wires ProcessLifecycleOwner/ActivityLifecycleCallbacks observers
        // and a consent watcher — it does not request any ad until consent permits.
        AppOpenAdManager.register(this, applicationScope)

        // Entitlement must be resolved before anything asks whether to show an ad.
        // Started here so the cached value is applied on the very first frame —
        // a subscriber seeing a banner flash while Firestore connects reads as the
        // app forgetting they paid.
        EntitlementRepository.start(this)

        // Connects to Play and re-posts any owned subscription for verification.
        // Started here rather than from the paywall so a reinstall, a new device, or
        // a purchase that completed while the app was dead restores itself silently
        // — the user should never have to find a "Restore" button to get what they
        // already paid for. Started after the repository so the entitlement listener
        // is already attached when verification writes the document.
        // Belt and braces: BillingManager guards its own setup, but nothing reached from
        // Application.onCreate may be allowed to throw. A crash here is a black screen at
        // launch with no UI and no way for the user to recover — which is exactly what
        // versionCode 23 shipped.
        try {
            BillingManager.start(this, applicationScope)
        } catch (t: Throwable) {
            Log.e(TAG, "BillingManager.start failed — premium purchase disabled this run", t)
        }

        // Energy prices: apply any saved user override synchronously so the calculator
        // never briefly quotes a price the user has already corrected, then refresh the
        // published table in the background. A failed refresh is silent — the seed or
        // the user's own figure is already correct enough to show.
        try {
            // Takes the scope so it can re-resolve on sign-in — the country fallback
            // that reads a tracked trip needs a uid, which does not exist yet here.
            EnergyPriceRepository.start(this, applicationScope)
        } catch (t: Throwable) {
            Log.e(TAG, "Energy price init failed — falling back to bundled prices", t)
        }
    }

    /**
     * Install the App Check provider before any Firebase service issues a request, so tokens are
     * attached from the first call. Debug builds use the debug provider — Play Integrity cannot
     * attest an emulator or a locally-signed APK — which prints a token to Logcat (tag
     * `DebugAppCheckProvider`) that must be registered under the app in the Firebase console.
     *
     * Failure here is deliberately non-fatal: while every service is UNENFORCED a missing token
     * changes nothing, and crashing the app over attestation setup would be far worse than the
     * upload failure this was added to fix.
     */
    private fun initAppCheck() {
        try {
            FirebaseApp.initializeApp(this)
            val factory: AppCheckProviderFactory = if (BuildConfig.DEBUG) {
                // The debug provider ships only in the debugImplementation-only artifact
                // (firebase-appcheck-debug), so it is absent from the release classpath. Load it
                // reflectively to keep the release variant compiling — this branch only runs in debug.
                Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    .getMethod("getInstance")
                    .invoke(null) as AppCheckProviderFactory
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
            Log.d(TAG, "App Check provider installed (debug=${BuildConfig.DEBUG})")
        } catch (t: Throwable) {
            Log.e(TAG, "App Check init failed — requests will carry no attestation token", t)
        }
    }

    /**
     * Logs the full stack to Logcat before the process exits. Filter Logcat for [CRASH_LOG_TAG]
     * or "FATAL" to capture the real exception when diagnosing "keeps stopping".
     *
     * This chains to [previous], which is Firebase Crashlytics' handler (installed during Firebase's
     * ContentProvider init, before this runs) — so fatals are still reported to the Crashlytics console
     * on the user's device. This local logger is just for on-device / adb debugging.
     */
    private fun installUncaughtExceptionLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e(CRASH_LOG_TAG, "FATAL on thread ${thread.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        private const val TAG = "KineticEcoApplication"
        /** Use this tag in Logcat when reproducing crashes. */
        const val CRASH_LOG_TAG = "KineticEcoCrash"
    }
}
