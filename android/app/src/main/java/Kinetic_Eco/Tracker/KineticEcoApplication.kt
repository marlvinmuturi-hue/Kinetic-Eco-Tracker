package Kinetic_Eco.Tracker

import android.app.Application
import android.util.Log
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
        // NOTE: The Mobile Ads SDK is intentionally NOT initialized here. Ads must
        // not be requested before the UMP consent flow runs (GDPR/EEA/UK). Consent
        // is gathered in MainActivity via ConsentManager.gatherConsent(), which
        // initializes the SDK only once ConsentInformation.canRequestAds() is true.
    }

    /**
     * Logs the full stack to Logcat before the process exits. Filter Logcat for [CRASH_LOG_TAG]
     * or "FATAL" to capture the real exception when diagnosing "keeps stopping".
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
