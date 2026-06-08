package Kinetic_Eco.Tracker.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.R

/**
 * Foreground service that keeps auto-start on walk active in the background.
 *
 * Two parallel triggers feed [startTrackingFromAutoStart]:
 *  1. **Hardware step counter** — [Sensor.TYPE_STEP_COUNTER] listener
 *     registered while this service is alive. Fires after
 *     [STEP_TRIGGER_THRESHOLD] steps. This is the primary trigger because
 *     it's fast (a few seconds of walking) and reliable — Activity
 *     Recognition transitions are often suppressed by aggressive OEM ROMs.
 *  2. **Activity Recognition Transition API** — secondary, mainly useful for
 *     `IN_VEHICLE` transitions where the step counter doesn't apply.
 *
 * **Important:** [ACTION_PROCESS_ACTIVITY_TRANSITION] is delivered via
 * [PendingIntent.getForegroundService]. Every such start **must** call
 * [startForegroundIfNeeded] immediately, or the app crashes
 * (`ForegroundServiceDidNotStartInTimeException`).
 */
class AutoStartMonitorService : LifecycleService() {

    private lateinit var activityTransitionManager: ActivityTransitionManager

    // ── Background step-counter trigger ─────────────────────────────────────
    //
    // The hardware step counter (Sensor.TYPE_STEP_COUNTER) is the most reliable
    // way to detect that the user has actually started walking — it fires
    // within seconds, vs. the Activity Recognition Transition API which often
    // takes 30+ seconds and is suppressed entirely on many OEM ROMs.
    //
    // Because this sensor listener is registered from a foreground service,
    // it keeps delivering events while the app is closed, fixing the original
    // bug where tracking only auto-started after the user manually opened the
    // app (the in-app StepMonitor in NavGraph runs only while the Compose
    // tree is alive).
    private val sensorManager: SensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val stepCounter: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }
    private var stepListener: SensorEventListener? = null
    /** Cumulative step counter value at the moment the listener was last (re-)registered. */
    private var stepBaseline: Int = -1

    // ── Significant-motion trigger ───────────────────────────────────────────
    //
    // TYPE_SIGNIFICANT_MOTION is a hardware-accelerated one-shot sensor that fires
    // within 1–3 s of the device transitioning from stationary to any significant
    // motion (walk, cycle, vehicle start). It requires no permission, drains negligible
    // battery (handled in hardware), and catches driving starts that the step counter
    // misses entirely. After each fire it must be re-armed manually.
    private val significantMotionSensor: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }
    private var significantMotionListener: TriggerEventListener? = null

    /**
     * Wall-clock time of the last successful auto-start trigger (from either
     * the step counter, activity transitions, or significant motion). Used as a
     * cooldown so a continuous walk doesn't repeatedly fire `startForegroundService`
     * while `TrackingService` is already running.
     */
    private var lastTriggerTimeMs: Long = 0L
    /** Consecutive times the indoor GPS gate suppressed an auto-start. Resets on success or manual stop. */
    private var indoorGateSuppressCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        activityTransitionManager = ActivityTransitionManager(this)
    }

    override fun onDestroy() {
        // Defensive: the service may be killed without ACTION_STOP if the OS
        // reclaims memory. Make sure we don't leave dangling sensor listeners.
        unregisterStepListener()
        disarmSignificantMotionSensor()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundIfNeeded()
                activityTransitionManager.registerTransitions()
                registerStepListener()
                armSignificantMotionSensor()
                Log.d(TAG, "Auto-start monitor running (transitions + step counter + significant motion)")
            }
            ACTION_STOP -> {
                activityTransitionManager.unregisterTransitions()
                unregisterStepListener()
                disarmSignificantMotionSensor()
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
        if (prefs.getAutoStartOnWalkEnabled() || prefs.getPendingResumeAfterIdleAutoStop()) {
            startForegroundIfNeeded()
            try {
                activityTransitionManager.registerTransitions()
            } catch (e: Exception) {
                Log.e(TAG, "restoreIfNeeded: registerTransitions failed", e)
            }
            // Step listener and significant-motion sensor may have been torn down with the
            // previous process; restart both so all triggers fire after a sticky restart.
            registerStepListener()
            armSignificantMotionSensor()
        } else {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            unregisterStepListener()
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
        if (!prefs.getAutoStartOnWalkEnabled() && !prefs.getPendingResumeAfterIdleAutoStop()) return

        for (event in result.transitionEvents) {
            if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
            val type = event.activityType
            if (type == DetectedActivity.WALKING ||
                type == DetectedActivity.RUNNING ||
                type == DetectedActivity.ON_FOOT ||
                type == DetectedActivity.IN_VEHICLE
            ) {
                val vehicleColdStart = (type == DetectedActivity.IN_VEHICLE)
                Log.d(TAG, "Activity transition ENTER (type=$type, vehicleColdStart=$vehicleColdStart) → starting tracking")
                startTrackingFromAutoStart(vehicleColdStart)
                break
            }
        }
    }

    /**
     * Register a [Sensor.TYPE_STEP_COUNTER] listener so we can detect walking
     * from the background. Idempotent — calling repeatedly while already
     * registered is a no-op. Bails silently if the device has no step counter,
     * if [Manifest.permission.ACTIVITY_RECOGNITION] is not granted (Android
     * 10+), or if the user already has tracking running.
     */
    private fun registerStepListener() {
        if (stepListener != null) return
        if (stepCounter == null) {
            Log.w(TAG, "No step counter sensor on device — relying on Activity Recognition transitions only")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "ACTIVITY_RECOGNITION not granted — cannot use step counter for auto-start")
                return
            }
        }

        // Reset baseline on every (re-)registration so the threshold is
        // measured from "now", not from device-boot cumulative steps.
        stepBaseline = -1

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
                val raw = event.values.firstOrNull()?.toInt() ?: return
                if (stepBaseline == -1) {
                    stepBaseline = raw
                    return
                }
                val delta = raw - stepBaseline
                if (delta >= STEP_TRIGGER_THRESHOLD) {
                    Log.d(TAG, "Step counter threshold reached ($delta steps) → starting tracking")
                    // Reset baseline so we don't immediately re-fire on the
                    // next sensor sample. TrackingService.startTracking() is
                    // idempotent if tracking is already running.
                    stepBaseline = raw
                    handleStepTrigger()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // SENSOR_DELAY_NORMAL is the right knob for a stepping detector — UI
        // delay would burn battery for no benefit since we only care about
        // the cumulative count, not per-step timing.
        sensorManager.registerListener(listener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
        stepListener = listener
        Log.d(TAG, "Step counter listener registered (threshold = $STEP_TRIGGER_THRESHOLD)")
    }

    private fun unregisterStepListener() {
        stepListener?.let { sensorManager.unregisterListener(it) }
        stepListener = null
        stepBaseline = -1
    }

    /**
     * Arm the [Sensor.TYPE_SIGNIFICANT_MOTION] trigger. Idempotent — a no-op if already armed
     * or if the device has no such sensor. Re-arming after each fire keeps detection continuous.
     */
    private fun armSignificantMotionSensor() {
        val sensor = significantMotionSensor ?: return  // hardware not present — step counter + transitions cover us
        if (significantMotionListener != null) return   // already armed
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                // One-shot: Android auto-cancels the registration after firing.
                significantMotionListener = null
                Log.d(TAG, "Significant motion detected → motion auto-start trigger")
                handleSignificantMotionTrigger()
                // Re-arm immediately so the next stationary→motion transition is also caught.
                armSignificantMotionSensor()
            }
        }
        try {
            if (sensorManager.requestTriggerSensor(listener, sensor)) {
                significantMotionListener = listener
                Log.d(TAG, "Significant motion sensor armed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to arm significant motion sensor", e)
        }
    }

    private fun disarmSignificantMotionSensor() {
        val listener = significantMotionListener ?: return
        val sensor = significantMotionSensor ?: return
        try { sensorManager.cancelTriggerSensor(listener, sensor) } catch (_: Exception) {}
        significantMotionListener = null
    }

    /**
     * Handles a significant-motion trigger. Guards against disabled auto-start and routes
     * through the shared [startTrackingFromAutoStart] path (GPS accuracy gate + cooldown).
     */
    private fun handleSignificantMotionTrigger() {
        val prefs = UserPreferencesManager(this)
        if (!prefs.getAutoStartOnWalkEnabled() && !prefs.getPendingResumeAfterIdleAutoStop()) return
        startTrackingFromAutoStart(vehicleColdStart = false)
    }

    /**
     * Step-counter-triggered auto-start. Mirrors [handleActivityTransition]'s
     * pref guard so toggling auto-start off mid-walk doesn't fire a stale
     * trigger that's already in flight.
     */
    private fun handleStepTrigger() {
        val prefs = UserPreferencesManager(this)
        if (!prefs.getAutoStartOnWalkEnabled() && !prefs.getPendingResumeAfterIdleAutoStop()) return
        startTrackingFromAutoStart(vehicleColdStart = false)
    }

    private fun startTrackingFromAutoStart(vehicleColdStart: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < TRIGGER_COOLDOWN_MS) return

        val prefs = UserPreferencesManager(this)
        if (!prefs.getPendingResumeAfterIdleAutoStop()) {
            val msSinceManualStop = now - prefs.getManualStopMs()
            if (msSinceManualStop < 30_000L) {
                Log.d(TAG, "Suppressing auto-start: manual stop ${msSinceManualStop}ms ago")
                indoorGateSuppressCount = 0
                return
            }
        }

        // Set cooldown before the async GPS check so concurrent step/transition
        // triggers don't each spawn their own GPS query.
        lastTriggerTimeMs = now

        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            launchTrackingService(vehicleColdStart)
            return
        }

        // Vehicle cold-starts skip the indoor GPS gate — if the user is already driving,
        // the indoor accuracy check would wrongly suppress the start in a parking garage.
        if (vehicleColdStart) {
            Log.d(TAG, "Auto-start: IN_VEHICLE cold start — bypassing indoor GPS gate")
            launchTrackingService(vehicleColdStart = true)
            return
        }

        // GPS accuracy gate: a recent fix with accuracy > GPS_INDOOR_ACCURACY_M strongly
        // suggests the user is indoors (kitchen walk, climbing stairs, fidgeting).
        // Suppress the auto-start and allow a quick retry — the gate will clear the
        // moment the user steps outside and GPS locks on cleanly.
        LocationServices.getFusedLocationProviderClient(applicationContext).lastLocation
            .addOnCompleteListener { task ->
                val loc = task.result
                val ageMs = if (loc != null) System.currentTimeMillis() - loc.time else Long.MAX_VALUE
                val accuracy = loc?.accuracy ?: 0f
                if (loc != null && ageMs < GPS_FIX_STALE_MS && accuracy > GPS_INDOOR_ACCURACY_M) {
                    indoorGateSuppressCount++
                    if (indoorGateSuppressCount < GPS_INDOOR_MAX_SUPPRESSIONS) {
                        Log.d(TAG, "Auto-start suppressed ($indoorGateSuppressCount/${GPS_INDOOR_MAX_SUPPRESSIONS}): GPS accuracy ${accuracy}m > ${GPS_INDOOR_ACCURACY_M}m")
                        // Shorten the cooldown so the step-counter can retry sooner
                        // (e.g. user walks to the door and GPS snaps into shape).
                        lastTriggerTimeMs = now - TRIGGER_COOLDOWN_MS + GPS_INDOOR_RETRY_COOLDOWN_MS
                        return@addOnCompleteListener
                    }
                    // After GPS_INDOOR_MAX_SUPPRESSIONS consecutive suppressions the user is
                    // likely outdoors in a weak-signal area, not truly indoors — launch anyway.
                    Log.d(TAG, "Auto-start: GPS indoor gate override after $indoorGateSuppressCount suppressions (accuracy=${accuracy}m) — launching anyway")
                }
                indoorGateSuppressCount = 0
                Log.d(TAG, "Auto-start GPS gate passed (accuracy=${accuracy}m age=${ageMs / 1000}s) — launching TrackingService")
                launchTrackingService(vehicleColdStart = false)
            }
    }

    private fun launchTrackingService(vehicleColdStart: Boolean = false) {
        val ts = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
            if (vehicleColdStart) putExtra(TrackingService.EXTRA_COLD_START_VEHICLE, true)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(ts)
            } else {
                applicationContext.startService(ts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TrackingService from auto-start: ${e.javaClass.simpleName}", e)
        }
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN: no sound, no vibration, no status-bar icon — the
            // notification only appears if the user manually opens the shade,
            // collapsed at the very bottom. This is the least intrusive level
            // Android allows for a foreground service.
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
            .setSilent(true)
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
        // Android 14+ expects location-type FGS to interact with location APIs; keeps policy consistent on OEM builds.
        pingFusedLocationForFgsCompliance()
    }

    private fun pingFusedLocationForFgsCompliance() {
        val fine = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            LocationServices.getFusedLocationProviderClient(applicationContext).lastLocation
                .addOnCompleteListener { }
        } catch (e: Exception) {
            Log.w(TAG, "FGS location compliance ping failed", e)
        }
    }

    companion object {
        private const val TAG = "AutoStartMonitor"
        // v2: bumped from auto_start_monitor to force channel recreation with IMPORTANCE_LOW.
        // Android locks channel importance at first creation, so the old IMPORTANCE_MIN channel
        // must be abandoned — existing installs will silently adopt the new channel on next launch.
        private const val CHANNEL_ID = "auto_start_monitor_v2"
        private const val NOTIFICATION_ID = 3001

        /**
         * Number of steps (from the moment the listener registered) that
         * triggers an auto-start. Matches the in-app `IN_APP_AUTO_START_STEP_THRESHOLD`
         * in `NavGraph.kt` so the user gets the same behavior whether the app
         * is open or closed.
         */
        private const val STEP_TRIGGER_THRESHOLD = 15

        /**
         * Cooldown between auto-start trigger attempts. Long enough that we
         * won't keep poking [TrackingService] during a continuous walk, short
         * enough that an idle-stop → walk-again cycle re-fires promptly.
         */
        private const val TRIGGER_COOLDOWN_MS = 15_000L

        /**
         * GPS horizontal accuracy above which auto-start is suppressed.
         * 100 m covers genuine indoor readings while allowing marginal outdoor
         * fixes (urban canyon, tree cover) that routinely sit at 60–90 m.
         */
        private const val GPS_INDOOR_ACCURACY_M = 100f

        /**
         * After this many consecutive indoor-gate suppressions, launch anyway.
         * Prevents permanent blocking when the user is outdoors in a weak-signal
         * area whose GPS accuracy never clears the [GPS_INDOOR_ACCURACY_M] bar.
         */
        private const val GPS_INDOOR_MAX_SUPPRESSIONS = 3

        /**
         * A GPS fix older than this is too stale to be a reliable indoor/outdoor
         * indicator — allow auto-start even if the cached fix had poor accuracy.
         */
        private const val GPS_FIX_STALE_MS = 300_000L  // 5 minutes

        /**
         * After suppressing an indoor auto-start, retry after this delay
         * instead of the full [TRIGGER_COOLDOWN_MS] — so tracking starts
         * promptly once the user actually steps outside and GPS clears.
         */
        private const val GPS_INDOOR_RETRY_COOLDOWN_MS = 8_000L

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
