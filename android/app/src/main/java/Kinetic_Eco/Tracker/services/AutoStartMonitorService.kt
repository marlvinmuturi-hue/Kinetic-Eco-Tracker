package Kinetic_Eco.Tracker.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.R

/**
 * Foreground service that keeps auto-start on walk active in the background.
 * Registers for Activity Recognition transitions and starts TrackingService when walking is detected.
 *
 * **Important:** [ACTION_PROCESS_ACTIVITY_TRANSITION] is delivered via [PendingIntent.getForegroundService].
 * Every such start **must** call [startForegroundIfNeeded] immediately, or the app crashes
 * (ForegroundServiceDidNotStartInTimeException).
 */
class AutoStartMonitorService : LifecycleService() {

    private lateinit var activityTransitionManager: ActivityTransitionManager

    override fun onCreate() {
        super.onCreate()
        activityTransitionManager = ActivityTransitionManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundIfNeeded()
                activityTransitionManager.registerTransitions()
                Log.d(TAG, "Auto-start monitor running in background")
            }
            ACTION_STOP -> {
                activityTransitionManager.unregisterTransitions()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.d(TAG, "Auto-start monitor stopped")
            }
            ACTION_PROCESS_ACTIVITY_TRANSITION -> {
                // Required: FGS entry from Play Services — must promote to foreground immediately.
                startForegroundIfNeeded()
                handleActivityTransition(intent)
            }
            null -> {
                restoreIfNeededAfterStickyRestart()
            }
            else -> {
                restoreIfNeededAfterStickyRestart()
            }
        }
        return START_STICKY
    }

    /**
     * Sticky [START_STICKY] can restart the service with a null intent. Re-attach foreground + transitions
     * when auto-start is still enabled; otherwise stop.
     */
    private fun restoreIfNeededAfterStickyRestart() {
        val prefs = UserPreferencesManager(this)
        if (prefs.getAutoStartOnWalkEnabled()) {
            startForegroundIfNeeded()
            try {
                activityTransitionManager.registerTransitions()
            } catch (e: Exception) {
                Log.e(TAG, "restoreIfNeeded: registerTransitions failed", e)
            }
        } else {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
        }
    }

    private fun handleActivityTransition(intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) {
            Log.w(TAG, "Transition intent has no ActivityTransitionResult")
            return
        }
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val prefs = UserPreferencesManager(this)
        if (!prefs.getAutoStartOnWalkEnabled()) return

        for (event in result.transitionEvents) {
            if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
            val type = event.activityType
            if (type == DetectedActivity.WALKING ||
                type == DetectedActivity.RUNNING ||
                type == DetectedActivity.ON_FOOT
            ) {
                Log.d(TAG, "Activity transition ENTER (type=$type) → starting tracking")
                startTrackingFromAutoStart()
                break
            }
        }
    }

    private fun startTrackingFromAutoStart() {
        val ts = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(ts)
            } else {
                startService(ts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TrackingService from auto-start", e)
        }
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.auto_start_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.auto_start_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.auto_start_notification_title))
            .setContentText(getString(R.string.auto_start_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }
    }

    companion object {
        private const val TAG = "AutoStartMonitor"
        private const val CHANNEL_ID = "auto_start_monitor"
        private const val NOTIFICATION_ID = 3001

        const val ACTION_START = "kinetic_eco.ACTION_START_AUTO_MONITOR"
        const val ACTION_STOP = "kinetic_eco.ACTION_STOP_AUTO_MONITOR"
        /** Delivered by Play Services when activity transitions fire (see [ActivityTransitionManager]). */
        const val ACTION_PROCESS_ACTIVITY_TRANSITION = "kinetic_eco.ACTION_PROCESS_ACTIVITY_TRANSITION"

        fun start(context: Context) {
            val intent = Intent(context, AutoStartMonitorService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start: failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoStartMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "stop: failed", e)
            }
        }
    }
}
