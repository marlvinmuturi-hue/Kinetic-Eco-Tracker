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
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    /** Coroutine that polls GPS speed every [SPEED_CHECK_INTERVAL_MS] to catch vehicle starts
     *  that Activity Recognition missed (step counter never fires while driving). */
    private var speedCheckJob: Job? = null

    /**
     * Wall-clock time of the last successful auto-start trigger (from either
     * the step counter, activity transitions, or significant motion). Used as a
     * cooldown so a continuous walk doesn't repeatedly fire `startForegroundService`
     * while `TrackingService` is already running.
     */
    private var lastTriggerTimeMs: Long = 0L
    /** Consecutive times the indoor GPS gate suppressed an auto-start. Resets on success or manual stop. */
    private var indoorGateSuppressCount: Int = 0

    // ── Periodic Activity Recognition state ──────────────────────────────────
    //
    // Populated by [handleActivityResult] from the low-power periodic activity-updates stream.
    // Two uses: (1) directly trigger a vehicle auto-start on a confident IN_VEHICLE reading, and
    // (2) act as the free "is the device moving?" gate for the GPS speed poll — when the device is
    // confidently STILL we skip the active GPS fix entirely, so battery is only spent when there's
    // reason to believe the user is actually in motion.
    @Volatile private var lastActivityType: Int = DetectedActivity.UNKNOWN
    @Volatile private var lastActivityConfidence: Int = 0
    @Volatile private var lastActivityUpdateMs: Long = 0L
    /** Wall-clock time significant motion last fired. A recent motion trigger overrides a stale STILL
     *  Activity-Recognition reading so the GPS speed-poll still samples right after a cold drive start. */
    @Volatile private var lastSignificantMotionMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        activityTransitionManager = ActivityTransitionManager(this)
    }

    override fun onDestroy() {
        // Defensive: the service may be killed without ACTION_STOP if the OS
        // reclaims memory. Make sure we don't leave dangling sensor listeners.
        unregisterStepListener()
        disarmSignificantMotionSensor()
        stopSpeedCheckLoop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundIfNeeded()
                activityTransitionManager.registerTransitions()
                activityTransitionManager.registerActivityUpdates()
                registerStepListener()
                armSignificantMotionSensor()
                startSpeedCheckLoop()
                Log.d(TAG, "Auto-start monitor running (transitions + periodic AR + step counter + significant motion + GPS speed poll)")
            }
            ACTION_STOP -> {
                activityTransitionManager.unregisterTransitions()
                activityTransitionManager.unregisterActivityUpdates()
                unregisterStepListener()
                disarmSignificantMotionSensor()
                stopSpeedCheckLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.d(TAG, "Auto-start monitor stopped")
            }
            ACTION_PROCESS_ACTIVITY_TRANSITION -> {
                // Required: FGS entry from Play Services — must promote to foreground immediately.
                startForegroundIfNeeded()
                handleActivityTransition(intent)
            }
            ACTION_PROCESS_ACTIVITY_RESULT -> {
                // Also an FGS entry from Play Services — promote to foreground immediately.
                startForegroundIfNeeded()
                handleActivityResult(intent)
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
                activityTransitionManager.registerActivityUpdates()
            } catch (e: Exception) {
                Log.e(TAG, "restoreIfNeeded: registerTransitions/updates failed", e)
            }
            // Step listener, significant-motion sensor and speed-check loop may have been
            // torn down with the previous process; restart all so triggers fire after a sticky restart.
            registerStepListener()
            armSignificantMotionSensor()
            startSpeedCheckLoop()
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
     * Handles a periodic activity-recognition update. Records the most-probable activity so the GPS
     * speed poll can use it as a motion gate, and directly triggers a vehicle auto-start on a confident
     * IN_VEHICLE reading — the redundant, OEM-resilient path for the "walk→drive with no stationary gap"
     * case that transitions miss.
     */
    private fun handleActivityResult(intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val prefs = UserPreferencesManager(this)
        if (!prefs.getAutoStartOnWalkEnabled() && !prefs.getPendingResumeAfterIdleAutoStop()) return

        val probable = result.mostProbableActivity
        lastActivityType = probable.type
        lastActivityConfidence = probable.confidence
        lastActivityUpdateMs = System.currentTimeMillis()

        if (probable.type == DetectedActivity.IN_VEHICLE &&
            probable.confidence >= IN_VEHICLE_CONFIDENCE_TRIGGER
        ) {
            Log.d(TAG, "AR periodic update: IN_VEHICLE conf=${probable.confidence}% → vehicle auto-start")
            startTrackingFromAutoStart(vehicleColdStart = true)
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

    private fun startSpeedCheckLoop() {
        speedCheckJob?.cancel()
        speedCheckJob = lifecycleScope.launch {
            while (isActive) {
                delay(SPEED_CHECK_INTERVAL_MS)
                checkGpsSpeedForVehicleStart()
            }
        }
    }

    private fun stopSpeedCheckLoop() {
        speedCheckJob?.cancel()
        speedCheckJob = null
    }

    /** Requests a single fresh GPS fix and, if its speed clearly indicates driving
     *  (>= [VEHICLE_SPEED_THRESHOLD_MS]), launches TrackingService — catching vehicle starts that the
     *  step counter (no steps while driving) and Activity Recognition transitions (suppressed on many
     *  OEM ROMs) missed.
     *
     *  Battery: this used to read the passive `lastLocation` cache, which is almost always stale when
     *  the app is closed (nothing keeps a fix warm) — so it never fired, and driving went unrecorded.
     *  It now takes an **active** balanced-power fix, but only when the free Activity-Recognition gate
     *  says the device may be moving. When AR confidently reports STILL, or a session is already
     *  running, we spend zero GPS — active location is only used when there's real reason to. */
    private suspend fun checkGpsSpeedForVehicleStart() {
        val prefs = UserPreferencesManager(this@AutoStartMonitorService)
        if (!prefs.getAutoStartOnWalkEnabled() && !prefs.getPendingResumeAfterIdleAutoStop()) return
        // A session is already tracking — TrackingService owns GPS; don't double-sample.
        if (TrackingService.isActivelyTracking) return

        // Free motion gate: skip the active GPS fix when AR recently and confidently said STILL.
        // If AR data is stale (suppressed / not yet delivered) we fall through and sample anyway.
        // Exception: if significant motion just fired, the STILL reading is stale by definition — AR
        // lags a fresh cold drive-off — so sample anyway or an immediate short drive gets missed.
        val motionRecentlyFired = lastSignificantMotionMs > 0L &&
            (System.currentTimeMillis() - lastSignificantMotionMs) < MOTION_GATE_OVERRIDE_MS
        val arAgeMs = System.currentTimeMillis() - lastActivityUpdateMs
        if (!motionRecentlyFired &&
            arAgeMs < ACTIVITY_GATE_STALE_MS &&
            lastActivityType == DetectedActivity.STILL &&
            lastActivityConfidence >= STILL_CONFIDENCE_GATE
        ) {
            return
        }

        sampleGpsAndMaybeVehicleStart()
    }

    /**
     * Takes a single **active** balanced-power GPS fix and, if its speed clearly indicates driving
     * (>= [VEHICLE_SPEED_THRESHOLD_MS]), launches TrackingService as a vehicle cold-start.
     * Returns true iff a vehicle start was launched. Shared by the periodic speed-poll and the
     * significant-motion trigger (which needs a fresh fix, not the stale cached `lastLocation`).
     */
    private suspend fun sampleGpsAndMaybeVehicleStart(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(
            this@AutoStartMonitorService, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(
            this@AutoStartMonitorService, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return false

        val cts = CancellationTokenSource()
        try {
            val loc = LocationServices.getFusedLocationProviderClient(applicationContext)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .await()
            if (loc != null && loc.hasSpeed()) {
                val ageMs = System.currentTimeMillis() - loc.time
                if (ageMs < SPEED_CHECK_STALE_MS && loc.speed >= VEHICLE_SPEED_THRESHOLD_MS) {
                    Log.d(TAG, "Speed sample: GPS=${loc.speed * 3.6f}km/h (${ageMs / 1000}s old) → vehicle auto-start")
                    startTrackingFromAutoStart(vehicleColdStart = true)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speed check failed", e)
        } finally {
            cts.cancel()
        }
        return false
    }

    /**
     * Handles a significant-motion trigger. Guards against disabled auto-start and routes
     * through the shared [startTrackingFromAutoStart] path (GPS accuracy gate + cooldown).
     */
    private fun handleSignificantMotionTrigger() {
        val prefs = UserPreferencesManager(this)
        if (!prefs.getAutoStartOnWalkEnabled() && !prefs.getPendingResumeAfterIdleAutoStop()) return
        // Record the motion so the GPS speed-poll ignores a stale STILL reading (AR lags a cold drive-off).
        lastSignificantMotionMs = System.currentTimeMillis()
        // A session is already tracking — nothing to start; the timestamp above is still useful.
        if (TrackingService.isActivelyTracking) return
        // Take an immediate active GPS fix rather than trusting the stale cached lastLocation: a real
        // vehicle start is caught in ~seconds and launched as a cold-start (halved motor-promotion
        // latency, skips the indoor GPS gate). If speed isn't clearly vehicular, fall back to the
        // pedestrian path (indoor accuracy gate) so this still works as a walk auto-start.
        lifecycleScope.launch {
            val launchedVehicle = sampleGpsAndMaybeVehicleStart()
            if (!launchedVehicle) {
                startTrackingFromAutoStart(vehicleColdStart = false)
            }
        }
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
            // Guarded: throws ForegroundServiceStartNotAllowedException (Android 12+, background start)
            // or SecurityException/MissingForegroundServiceType (Android 14+, no location permission).
            // Aggressive OEM ROMs (Samsung, Xiaomi) hit this where stock Android doesn't. Swallow and stop
            // the monitor rather than crashing the whole app; the non-fatal surfaces the real cause.
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed: ${e.javaClass.simpleName}", e)
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e)
                stopSelf()
                return
            }
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

        /** How often the GPS speed-poll loop checks for undetected vehicle motion. */
        private const val SPEED_CHECK_INTERVAL_MS = 20_000L

        /** A GPS fix older than this is too stale to derive current vehicle speed from. */
        private const val SPEED_CHECK_STALE_MS = 30_000L

        /** GPS speed (m/s) above which the device is clearly in a vehicle. 8 m/s = ~29 km/h,
         *  safely above the fastest running pace (~4 m/s) to avoid false triggers. */
        private const val VEHICLE_SPEED_THRESHOLD_MS = 8f

        /** Minimum confidence (%) on a periodic IN_VEHICLE reading to auto-start directly. */
        private const val IN_VEHICLE_CONFIDENCE_TRIGGER = 60

        /** Confidence (%) at/above which a STILL reading gates off the active GPS speed poll. */
        private const val STILL_CONFIDENCE_GATE = 70

        /** Beyond this age, the last activity reading is too stale to gate the GPS poll —
         *  sample anyway (covers OEMs that suppress activity delivery). */
        private const val ACTIVITY_GATE_STALE_MS = 90_000L

        /** If significant motion fired within this window, the STILL motion gate is overridden and the
         *  GPS speed-poll samples anyway — a fresh physical motion trigger beats a lagging STILL reading. */
        private const val MOTION_GATE_OVERRIDE_MS = 60_000L

        const val ACTION_START = "kinetic_eco.ACTION_START_AUTO_MONITOR"
        const val ACTION_STOP = "kinetic_eco.ACTION_STOP_AUTO_MONITOR"
        /** Delivered by Play Services when activity transitions fire (see [ActivityTransitionManager]). */
        const val ACTION_PROCESS_ACTIVITY_TRANSITION = "kinetic_eco.ACTION_PROCESS_ACTIVITY_TRANSITION"
        /** Delivered by Play Services for periodic activity-recognition updates (see [ActivityTransitionManager]). */
        const val ACTION_PROCESS_ACTIVITY_RESULT = "kinetic_eco.ACTION_PROCESS_ACTIVITY_RESULT"

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
