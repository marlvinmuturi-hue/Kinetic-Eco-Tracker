package Kinetic_Eco.Tracker

import android.app.Application
import android.util.Log
import Kinetic_Eco.Tracker.ui.components.AppOpenAdManager
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
