package Kinetic_Eco.Tracker.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import Kinetic_Eco.Tracker.data.ActivityType

/**
 * Handles taps on the "What are you doing?" activity-confirm notification.
 * Sets the chosen [ActivityType] as manual mode on [TrackingService] via an Intent action,
 * then dismisses the notification.
 */
class ActivityConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activityName = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: return

        // "KEEP" = user wants to keep auto-detect; just dismiss
        if (activityName != "KEEP") {
            val activity = try {
                ActivityType.valueOf(activityName)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Unknown activity type: $activityName")
                return
            }

            Log.d(TAG, "Activity confirmed via notification: $activity")

            val svcIntent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_SET_ACTIVITY
                putExtra(EXTRA_ACTIVITY_TYPE, activityName)
            }
            try {
                context.startService(svcIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not reach TrackingService to confirm activity", e)
            }
        } else {
            Log.d(TAG, "User chose to keep auto-detect")
        }

        // Dismiss the notification
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(CONFIRM_NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "ActivityConfirmReceiver"
        const val ACTION_CONFIRM_ACTIVITY = "kinetic_eco.ACTION_CONFIRM_ACTIVITY"
        const val ACTION_SET_ACTIVITY = "kinetic_eco.ACTION_SET_ACTIVITY"
        const val EXTRA_ACTIVITY_TYPE = "extra_activity_type"
        const val CONFIRM_NOTIFICATION_ID = 4001
    }
}
