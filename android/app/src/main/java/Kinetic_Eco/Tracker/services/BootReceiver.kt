package Kinetic_Eco.Tracker.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Re-registers Activity Recognition transitions after device boot.
 * Transitions are cleared on reboot, so we must re-register if auto-start is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefsManager = UserPreferencesManager(context)
        if (!prefsManager.getAutoStartOnWalkEnabled() && !prefsManager.getPendingResumeAfterIdleAutoStop()) {
            Log.d(TAG, "Auto-start and pending idle-resume off, skipping transition re-registration")
            return
        }

        Log.d(TAG, "Boot completed - starting auto-start monitor service")
        try {
            AutoStartMonitorService.start(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AutoStartMonitorService after boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
