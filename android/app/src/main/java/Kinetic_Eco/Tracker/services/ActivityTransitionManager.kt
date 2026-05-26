package Kinetic_Eco.Tracker.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Manages Activity Recognition Transition API registration.
 * Registers for WALKING/RUNNING/ON_FOOT/IN_VEHICLE ENTER transitions to enable auto-start on motion.
 */
class ActivityTransitionManager(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)

    /**
     * Register for activity transitions. Call when auto-start is enabled.
     */
    fun registerTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ok = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) {
                Log.w(TAG, "ACTIVITY_RECOGNITION not granted — cannot register walking transitions for auto-start")
                return
            }
        }

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_FOOT)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)
        // Deliver to our foreground auto-start service — not a BroadcastReceiver.
        // Android 12+ blocks starting a location foreground service from a background receiver;
        // starting TrackingService from AutoStartMonitorService (already/almost foreground) is allowed.
        val intent = Intent(context, AutoStartMonitorService::class.java).apply {
            action = AutoStartMonitorService.ACTION_PROCESS_ACTIVITY_TRANSITION
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Remove stale registration so retries after failure / process death do not stack duplicates.
        client.removeActivityTransitionUpdates(pendingIntent).addOnCompleteListener {
            client.requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Log.d(TAG, "Activity transitions registered successfully")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to register activity transitions", e)
                }
        }
    }

    /**
     * Unregister from activity transitions. Call when auto-start is disabled.
     */
    fun unregisterTransitions() {
        val intent = Intent(context, AutoStartMonitorService::class.java).apply {
            action = AutoStartMonitorService.ACTION_PROCESS_ACTIVITY_TRANSITION
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        client.removeActivityTransitionUpdates(pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "Activity transitions unregistered successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to unregister activity transitions", e)
            }
    }

    companion object {
        private const val TAG = "ActivityTransitionMgr"
        private const val REQUEST_CODE = 2001
    }
}
