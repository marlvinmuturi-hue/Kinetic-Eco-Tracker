package Kinetic_Eco.Tracker.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.google.firebase.auth.FirebaseAuth
import Kinetic_Eco.Tracker.KineticEcoApplication
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.filters.KalmanFilter
import Kinetic_Eco.Tracker.filters.FlightTracker
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.util.adjustStatsForSimplifiedPath
import kotlin.math.min

class TrackingService : LifecycleService() {
    private lateinit var locationService: LocationService
    private lateinit var sensorService: SensorService
    private lateinit var sessionManager: SessionManager
    private lateinit var userPrefsManager: UserPreferencesManager
    private var userPhysicalProfile: UserPhysicalProfile? = null
    private var vehicleProfile: VehicleProfile = VehicleProfile.DEFAULT
    
    // Advanced filtering and flight tracking
    private val kalmanFilter = KalmanFilter()
    private val flightTracker = FlightTracker()

    // Tracking state
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _currentActivity = MutableStateFlow(ActivityType.IDLE)
    val currentActivity: StateFlow<ActivityType> = _currentActivity.asStateFlow()

    private val _sessionDuration = MutableStateFlow(0L)
    val sessionDuration: StateFlow<Long> = _sessionDuration.asStateFlow()

    private val _sessionDistance = MutableStateFlow(0.0)
    val sessionDistance: StateFlow<Double> = _sessionDistance.asStateFlow()

    private val _sessionSteps = MutableStateFlow(0)
    val sessionSteps: StateFlow<Int> = _sessionSteps.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _currentPosition = MutableStateFlow<GeoPosition?>(null)
    val currentPosition: StateFlow<GeoPosition?> = _currentPosition.asStateFlow()

    private val _manualActivityMode = MutableStateFlow<ActivityType?>(null)
    val manualActivityMode: StateFlow<ActivityType?> = _manualActivityMode.asStateFlow()

    /** While manual activity is pinned, IMU pocket-lean may suggest Motorcycle or Cycling — never applied automatically. */
    private val _leanActivityHint = MutableStateFlow<ActivityType?>(null)
    val leanActivityHint: StateFlow<ActivityType?> = _leanActivityHint.asStateFlow()

    private val pocketLeanEstimator = PocketTwoWheelLeanEstimator()
    private var leanMotorPromoSinceMs: Long = 0L
    private var leanBikePromoSinceMs: Long = 0L
    /** After user dismisses a lean hint, suppress re-show for [LEAN_HINT_COOLDOWN_MS]. */
    private var leanHintDismissedAtMs: Long = 0L
    private val leanPromoHoldMs = 3_800L
    private val leanHintCooldownMs = 90_000L

    private var lastPosition: GeoPosition? = null
    private var previousAltitude: Double = 0.0
    private var lastUpdateTime: Long = 0
    private var smoothedSpeed: Float = 0f
    private val activityHistory = mutableListOf<ActivityType>()
    private val ACTIVITY_HISTORY_SIZE = 2  // Reduced to 2 for faster response (speed lag fix)
    
    // Activity persistence tracking - how long current activity has been ongoing
    private var activityStartTime: Long = System.currentTimeMillis()
    private var activityDurationSeconds: Long = 0

    // Session start time - when user clicked Start (used for correct session date)
    private var sessionStartTimeMs: Long = 0

    // Kilometer milestone notification tracking
    private var lastNotifiedKm: Int = 0
    private var durationAtLastKm: Long = 0  // seconds

    // Sensor data for hybrid
    private var currentAcceleration = 0f
    private var currentRotationRate = 0f
    private var lastStepCount = 0
    private var lastStepTimestamp = 0L
    
    // Collector jobs - cancelled on stop to prevent pedometer carry-over to next session
    private var locationCollectJob: Job? = null
    private var sensorCollectJob: Job? = null
    private var sensorWarmUpJob: Job? = null  // Pre-feed accelerometer during GPS warm-up
    private var idleCheckJob: Job? = null
    private var deadReckoningJob: Job? = null
    private var activityConfirmJob: Job? = null

    /** Seconds after tracking start where we use relaxed debounce (requiredConsistency=1) for faster convergence */
    private val STARTUP_DEBOUNCE_SECONDS = 10

    // Auto-stop on idle: when we transitioned to IDLE
    private var idleStartTimeMs: Long = 0

    /**
     * When [ActivityType.DRIVING] or [ActivityType.ELECTRIC_VEHICLE] but GPS speed drops below driving min
     * (traffic, parking), keep motor mode until low speed persists for [MOTOR_LOW_SPEED_EXIT_MS].
     * [motorLowSpeedSinceMs] counts only after [STICKY_RECENT_DRIVING_MS] without speed in the driving band.
     */
    private var motorLowSpeedSinceMs: Long = 0L

    /** Last fix time when smoothed speed was at/above [SpeedThresholds.DRIVING_MIN] (sticky window). */
    private var lastDrivingBandMs: Long = 0L

    /**
     * Timestamp of the first tick that wanted to promote a pedestrian activity
     * (IDLE/WALKING/RUNNING/CYCLING) into a motor mode (DRIVING/EV/TRAIN/FLYING).
     * Stays at 0 while the candidate hasn't started; cleared back to 0 the
     * moment a tick disagrees. Used by [applyPedestrianToMotorPromotionLatency]
     * to require sustained high speed before the activity flips — single GPS
     * multipath spikes in urban canyons no longer count.
     */
    private var pedestrianToMotorRiseSinceMs: Long = 0L

    /** OSM railway geometry (Overpass) — promotes DRIVING/EV → TRAIN when the fix hugs mapped track. */
    private var railLookupJob: Job? = null
    @Volatile private var railMinDistanceM: Double? = null
    @Volatile private var railSampleAtMs: Long = 0L
    private var lastRailScheduleWallMs: Long = 0L
    private var lastRailScheduleLat: Double? = null
    private var lastRailScheduleLon: Double? = null
    private var trainCorridorPositiveStreak: Int = 0
    
    // Elevation tracking for calorie calculations and session summary
    private var totalElevationGain = 0.0
    private var totalElevationLoss = 0.0
    private var startingAltitude: Double? = null
    private var stoppingAltitude: Double? = null
    private var minAltitude: Double? = null
    private var maxAltitude: Double? = null
    
    // Kilometer milestones for session summary (time per km)
    private val kmMilestones = mutableListOf<KmMilestone>()
    
    // Top speed (raw GPS max) for session summary
    private var sessionTopSpeedMps = 0.0
    
    // Route path for map display - record points when tracking
    private val pathPoints = mutableListOf<RoutePoint>()
    private var lastPathRecordTime: Long = 0
    private val PATH_RECORD_INTERVAL_MS = 1500L // Record every ~1.5s; ensures route shows even when stationary or slow (autodetect)

    /** Same activity as route segment colors (post GPS refinement). Step/dead-reck stats must not use debounced [_currentActivity] alone. */
    private var lastRefinedActivity: ActivityType = ActivityType.IDLE

    /**
     * Manual-mode mismatch detection: while the user has pinned an activity
     * via [setManualActivityMode], we still run the auto-classifier in the
     * background ("shadow") and flag the user when its output disagrees with
     * the manual choice for a sustained window. The fields below scope that
     * detection to a single in-flight session.
     *
     *  - [manualMismatchCandidate]: the auto-detected activity we're currently
     *    timing. Reset whenever shadow flips back to the manual choice or to
     *    a different non-manual candidate.
     *  - [manualMismatchSinceMs]: when [manualMismatchCandidate] first started
     *    disagreeing — used to enforce [MANUAL_MISMATCH_PROMPT_DELAY_MS].
     *  - [manualMismatchLastPromptMs]: last time we posted (or the user
     *    dismissed) the prompt — gates [MANUAL_MISMATCH_COOLDOWN_MS].
     */
    private var manualMismatchCandidate: ActivityType? = null
    private var manualMismatchSinceMs: Long = 0L
    private var manualMismatchLastPromptMs: Long = 0L

    // Accelerometer samples for Firestore analytics (1 sample/sec during tracking)
    private val accelerometerSamples = mutableListOf<AccelerometerSample>()
    private var lastAccelSampleTimeMs: Long = 0
    private val ACCEL_SAMPLE_INTERVAL_MS = 1000L
    
    // Speed validation constants (in m/s) - Enhanced for flying
    private val MAX_REALISTIC_SPEEDS = mapOf(
        ActivityType.IDLE to 0.5f,              // 1.8 km/h
        ActivityType.WALKING to 2.0f,           // 7.2 km/h (fast walk)
        ActivityType.RUNNING to 7.0f,           // 25 km/h (world-class sprint)
        ActivityType.CYCLING to 16.7f,          // 60 km/h (pro cyclist)
        ActivityType.MOTORCYCLE to 55.0f,       // ~200 km/h plausible road bike
        ActivityType.TRAIN to 97.0f,            // 350 km/h (high-speed rail)
        ActivityType.DRIVING to 50.0f,          // 180 km/h (reasonable max)
        ActivityType.ELECTRIC_VEHICLE to 50.0f, // 180 km/h
        ActivityType.FLYING to 333.0f           // 1200 km/h (allow for high cruise speeds)
    )
    
    private val MAX_SPEED_CHANGE = 10.0f // Max 10 m/s (36 km/h) change per update (ground, non-walking)
    /** Walking / slow jog: GPS jitter causes large apparent speed changes — cap delta per fix. */
    private val MAX_SPEED_CHANGE_WALKING = 2.5f // ~9 km/h max jump per update
    private val MAX_SPEED_CHANGE_FLYING = 30.0f // Max 30 m/s (108 km/h) for takeoff/landing

    // Fallback thresholds: use alt sources when GPS speed is unreliable
    private val GPS_SPEED_UNRELIABLE_MAX = 0.5f  // m/s - treat as unreliable below this
    private val POOR_ACCURACY_METERS = 80f       // Use Kalman/fallback when accuracy worse
    private val SUSPICIOUS_ZERO_HOLD_SPEED = 2.0f // m/s - hold last speed if was above this

    // Dead reckoning during GPS gaps (flying, tunnels)
    private val DEAD_RECKON_GAP_THRESHOLD_MS = 3000L   // Start after 3s without GPS
    private val DEAD_RECKON_MAX_DURATION_MS = 60000L   // Max 60s extrapolation
    private var deadReckonStartMs = 0L

    // Step-based distance fallback when GPS is weak (urban/indoor walking)
    /**
     * How long without a successful GPS distance accumulation before we let
     * the pedometer carry distance. Lowered from 4 s — in urban canyons fixes
     * routinely arrive every 1–2 s with poor accuracy (so they don't pass the
     * `accuracy <= MAX_ACCURACY_METERS_*` gate and don't move
     * [lastGpsDistanceUpdateMs]); the old 4 s window meant we lost up to ~5 m
     * of walking each time we re-entered weak GPS.
     */
    private val STEP_BASED_GAP_THRESHOLD_MS = 2500L
    private var lastGpsDistanceUpdateMs = 0L
    private val STEP_LENGTH_WALKING = 0.75  // meters per step (GPS fresh)
    private val STEP_LENGTH_RUNNING = 1.0   // meters per step
    /** Slightly conservative when GPS distance is stale (reduces GPS+step inflation). */
    private val STEP_LENGTH_WALKING_STALE = 0.68

    /** Steps counted at last GPS distance increment — caps GPS wiggles vs step-based distance. */
    private var stepCountAtLastGps = 0
    private val MAX_ACCURACY_METERS_FOR_DISTANCE = 25f
    /**
     * Walking-specific accuracy ceiling for accepting GPS into distance.
     * Raised from 25 m — at 25 m we threw away most urban-canyon fixes and
     * the step-cap blending below was already strong enough to bound multipath
     * wiggle (it limits each interval to roughly steps × stride + small slack,
     * so a 35 m fix that "thinks" you walked 12 m gets clipped back to ~5 m if
     * only six steps registered).
     */
    private val MAX_ACCURACY_METERS_WALKING_DISTANCE = 35f
    private val WALKING_GPS_MIN_SEGMENT_M = 1.65
    private val CAP_WALKING_SPEED_MPS = 2.2
    private val CAP_RUNNING_SPEED_MPS = 4.5
    /** Extra slack when blending steps with GPS; only used when steps > 0 in interval. */
    private val STEP_GPS_BLEND_SLACK_M = 4.0

    // Never apply sensor-based speed zeroing above this (3 km/h)
    private val IDLE_OVERRIDE_THRESHOLD = 0.833f

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        locationService = LocationService(this)
        sensorService = SensorService(this)
        sessionManager = SessionManager(this)
        userPrefsManager = UserPreferencesManager(this)
        
        // Load user's physical profile for accurate calorie calculations
        userPhysicalProfile = userPrefsManager.loadPhysicalProfile()
        android.util.Log.d("TrackingService", "Physical profile loaded: $userPhysicalProfile")
        vehicleProfile = userPrefsManager.loadVehicleProfile()
        android.util.Log.d("TrackingService", "Vehicle profile loaded: $vehicleProfile")

        startTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> startTracking()
            ACTION_STOP_TRACKING -> stopTracking()
            ACTION_STOP_AND_SAVE -> stopAndSaveFromNotification()
            ACTION_DISCARD -> discardFromNotification()
            ACTION_MANUAL_SWITCH -> {
                val name = intent.getStringExtra(EXTRA_TARGET_ACTIVITY)
                val type = name?.let {
                    try { ActivityType.valueOf(it) } catch (_: Exception) { null }
                }
                if (type != null) {
                    setManualActivityMode(type)
                    // setManualActivityMode resets mismatch state already, but
                    // also clear the prompt so the switch feels confirmed.
                    dismissManualMismatchNotification()
                }
            }
            ACTION_MANUAL_KEEP -> {
                // User stands by their manual choice: just dismiss and start
                // the cooldown so we don't pester them again right away.
                manualMismatchLastPromptMs = System.currentTimeMillis()
                manualMismatchCandidate = null
                manualMismatchSinceMs = 0L
                dismissManualMismatchNotification()
            }
            ActivityConfirmReceiver.ACTION_SET_ACTIVITY -> {
                val name = intent.getStringExtra(ActivityConfirmReceiver.EXTRA_ACTIVITY_TYPE)
                val type = name?.let {
                    try { ActivityType.valueOf(it) } catch (_: Exception) { null }
                }
                if (type != null) setManualActivityMode(type)
            }
            null -> { /* system restart; tracking state is false until user starts again */ }
        }
        return Service.START_STICKY
    }

    /**
     * Save the in-flight session and then stop tracking.
     *
     * Triggered by the **"Stop & save"** action button on the foreground
     * notification — mirrors what the in-app Stop button does in
     * `TrackerViewModel.stopAndSaveSession`. The save is launched on
     * `applicationScope` so it survives the service tearing itself down a few
     * lines later.
     */
    private fun stopAndSaveFromNotification() {
        if (!_isTracking.value) {
            // Already idle — nothing to save, just make sure the foreground
            // notification is gone.
            stopTracking()
            return
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        val rawStats = _sessionStats.value
        val startMs = sessionStartTimeMs
        val routePath = getRoutePath()
        val accelSamples = getAccelerometerSamples()
        val adjustedStats = adjustStatsForSimplifiedPath(rawStats, routePath)
        val statsWithRoute = adjustedStats.copy(routePath = routePath)
        val app = applicationContext as KineticEcoApplication

        // Surface the "Session saved" notification while we still have a live
        // service context (NotificationManager outlives the service, but the
        // helper relies on `this`).
        showSessionCompleteNotification(adjustedStats)

        stopTracking()

        if (userId.isNullOrEmpty()) {
            android.util.Log.w("TrackingService", "Stop&save from notification: no signed-in user, skipping persistence")
            return
        }

        app.applicationScope.launch(Dispatchers.IO) {
            try {
                SessionManager(app.applicationContext).saveSession(
                    userId,
                    statsWithRoute,
                    startMs,
                    accelSamples
                )
                android.util.Log.d("TrackingService", "Stop&save from notification: session saved")
            } catch (e: Exception) {
                android.util.Log.e("TrackingService", "Stop&save from notification: failed to save", e)
            }
        }
    }

    /**
     * Stop tracking and discard the in-flight session without persisting it.
     *
     * Triggered by the **"Discard"** action on the foreground notification —
     * intended for sessions that started without the user's intent (e.g. the
     * auto-start step counter fired during a car ride). A short confirmation
     * notification is posted so the user knows the discard succeeded.
     */
    private fun discardFromNotification() {
        android.util.Log.d("TrackingService", "Discard from notification: dropping current session")
        showDiscardConfirmationNotification()
        stopTracking()
    }

    private fun startTracking() {
        if (_isTracking.value) return
        userPrefsManager.setPendingResumeAfterIdleAutoStop(false)

        // Cancel any previous collectors to prevent pedometer carry-over between sessions
        locationCollectJob?.cancel()
        sensorCollectJob?.cancel()
        sensorWarmUpJob?.cancel()
        idleCheckJob?.cancel()
        deadReckoningJob?.cancel()
        activityConfirmJob?.cancel()
        locationCollectJob = null
        sensorCollectJob = null
        sensorWarmUpJob = null
        idleCheckJob = null
        deadReckoningJob = null
        activityConfirmJob = null
        idleStartTimeMs = 0
        motorLowSpeedSinceMs = 0L
        lastDrivingBandMs = 0L
        pedestrianToMotorRiseSinceMs = 0L
        railLookupJob?.cancel()
        railLookupJob = null
        railMinDistanceM = null
        railSampleAtMs = 0L
        lastRailScheduleWallMs = 0L
        lastRailScheduleLat = null
        lastRailScheduleLon = null
        trainCorridorPositiveStreak = 0
        activityHistory.clear()

        pocketLeanEstimator.reset()
        leanMotorPromoSinceMs = 0L
        leanBikePromoSinceMs = 0L
        _leanActivityHint.value = null
        leanHintDismissedAtMs = 0L
        _currentActivity.value = ActivityType.IDLE
        locationService.setFlyingMode(false)
        _isTracking.value = true

        // Call startForeground immediately after marking active — avoids FGS timeout if later init is slow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = createNotification()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }

        lastUpdateTime = System.currentTimeMillis()
        lastPosition = null
        previousAltitude = 0.0
        smoothedSpeed = 0f
        lastStepCount = 0
        lastStepTimestamp = System.currentTimeMillis()
        stepCountAtLastGps = 0
        _sessionSteps.value = 0  // Reset step counter

        // Reset session accumulators so new session starts from 0 (fixes carry-over after auto-stop)
        _sessionDuration.value = 0L
        _sessionDistance.value = 0.0
        _sessionStats.value = SessionStats()
        totalElevationGain = 0.0
        totalElevationLoss = 0.0
        accelerometerSamples.clear()
        lastAccelSampleTimeMs = 0
        
        // Log sensor availability
        android.util.Log.d("TrackingService", "Has step counter: ${sensorService.hasStepCounter()}")
        
        // Reset filters and trackers (do NOT reset pattern analyzer here - preserve warm-up data)
        kalmanFilter.reset()
        flightTracker.reset()
        sensorService.resetBarometerCalibration()
        
        // Reset activity persistence tracking
        activityStartTime = System.currentTimeMillis()
        activityDurationSeconds = 0

        // Record session start for correct date when saving (e.g. session started at 11pm, saved at 12am next day)
        sessionStartTimeMs = System.currentTimeMillis()

        // Reset kilometer milestone tracking for new session
        lastNotifiedKm = 0
        durationAtLastKm = 0
        kmMilestones.clear()
        sessionTopSpeedMps = 0.0
        pathPoints.clear()
        lastPathRecordTime = 0
        lastRefinedActivity = ActivityType.IDLE
        startingAltitude = null
        stoppingAltitude = null
        minAltitude = null
        maxAltitude = null
        deadReckonStartMs = 0
        lastGpsDistanceUpdateMs = 0

        locationCollectJob = lifecycleScope.launch {
            try {
                // Use 100m accuracy threshold so route populates in marginal GPS (urban, indoor start, trees)
                locationService.getLocationUpdates(maxAccuracy = 100f).collect { position ->
                    try {
                        updateLocation(position)
                    } catch (e: Exception) {
                        android.util.Log.e("TrackingService", "updateLocation failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TrackingService", "Location collector ended with error", e)
            }
        }

        sensorCollectJob = lifecycleScope.launch {
            try {
                sensorService.getSensorUpdates().collect { sensorData ->
                    try {
                        updateSensors(sensorData)
                    } catch (e: Exception) {
                        android.util.Log.e("TrackingService", "updateSensors failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TrackingService", "Sensor collector ended with error", e)
            }
        }

        // Auto-stop on idle: poll often enough that we do not overshoot the user's timeout by a full minute
        idleCheckJob = lifecycleScope.launch {
            while (isActive && _isTracking.value) {
                delay(15_000)
                if (!_isTracking.value) break
                if (_currentActivity.value == ActivityType.IDLE) {
                    if (idleStartTimeMs == 0L) idleStartTimeMs = System.currentTimeMillis()
                    val idleMinutes = userPrefsManager.getIdleStopMinutes()
                    val elapsedMs = System.currentTimeMillis() - idleStartTimeMs
                    if (elapsedMs >= idleMinutes * 60 * 1000L) {
                        android.util.Log.d("TrackingService", "Auto-stop: idle for $idleMinutes minutes")
                        performAutoStopAndSave()
                        break
                    }
                } else {
                    idleStartTimeMs = 0
                }
            }
        }

        // Activity-confirm notification: after 30 s of tracking (manual mode not set), ask user
        activityConfirmJob = lifecycleScope.launch {
            delay(ACTIVITY_CONFIRM_DELAY_MS)
            if (_isTracking.value && _manualActivityMode.value == null) {
                showActivityConfirmNotification()
            }
        }

        // Dead reckoning: extrapolate distance when GPS drops (flying, tunnels)
        deadReckoningJob = lifecycleScope.launch {
            while (isActive && _isTracking.value) {
                delay(2_000)
                if (!_isTracking.value) break
                val now = System.currentTimeMillis()
                val gapMs = now - lastUpdateTime
                if (gapMs < DEAD_RECKON_GAP_THRESHOLD_MS || lastUpdateTime == 0L) continue
                val activity = if (_manualActivityMode.value != null) {
                    _manualActivityMode.value!!
                } else {
                    reclassifyBySpeed(smoothedSpeed.coerceAtLeast(0f))
                }
                val movingActivity = activity == ActivityType.FLYING ||
                    activity == ActivityType.DRIVING ||
                    activity == ActivityType.ELECTRIC_VEHICLE ||
                    activity == ActivityType.MOTORCYCLE ||
                    activity == ActivityType.TRAIN ||
                    activity == ActivityType.CYCLING
                if (!movingActivity || smoothedSpeed < 0.5f) continue
                if (deadReckonStartMs == 0L) deadReckonStartMs = lastUpdateTime
                val elapsedSinceStart = now - deadReckonStartMs
                if (elapsedSinceStart > DEAD_RECKON_MAX_DURATION_MS) continue
                val deltaSec = 2.0  // 2s since last tick
                val distanceDelta = scaledGpsDistanceMeters(smoothedSpeed * deltaSec)
                _sessionDistance.value += distanceDelta
                updateStats(distanceDelta, activity, smoothedSpeed.toDouble(), 0.0, 0.0)
            }
        }
    }

    fun stopTracking() {
        railLookupJob?.cancel()
        railLookupJob = null
        idleCheckJob?.cancel()
        idleCheckJob = null
        deadReckoningJob?.cancel()
        deadReckoningJob = null
        activityConfirmJob?.cancel()
        activityConfirmJob = null
        // Dismiss any lingering activity prompts so the shade is clean once
        // tracking ends — covers both the auto-start "What are you doing?"
        // notification and the manual-mode mismatch quick-switch prompt.
        (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.apply {
            cancel(ActivityConfirmReceiver.CONFIRM_NOTIFICATION_ID)
            cancel(MANUAL_MISMATCH_NOTIFICATION_ID)
        }
        manualMismatchCandidate = null
        manualMismatchSinceMs = 0L
        manualMismatchLastPromptMs = 0L
        _leanActivityHint.value = null
        _currentActivity.value = ActivityType.IDLE
        locationService.setFlyingMode(false)
        smoothedSpeed = 0f
        _currentSpeed.value = 0f
        locationCollectJob?.cancel()
        sensorCollectJob?.cancel()
        sensorWarmUpJob?.cancel()
        locationCollectJob = null
        sensorCollectJob = null
        sensorWarmUpJob = null
        sensorService.resetPatternAnalyzer()  // Reset for next session; warm-up will re-fill
        locationService.stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Pre-feed accelerometer during GPS warm-up so pattern recognition is ready at Start. */
    fun startSensorWarmUp() {
        if (sensorWarmUpJob?.isActive == true) return
        sensorWarmUpJob = lifecycleScope.launch {
            sensorService.getSensorUpdates().collect {
                // Collecting triggers internal addSample to patternAnalyzer
            }
        }
        android.util.Log.d("TrackingService", "Sensor warm-up started (accelerometer pre-feed)")
    }

    fun stopSensorWarmUp() {
        sensorWarmUpJob?.cancel()
        sensorWarmUpJob = null
        android.util.Log.d("TrackingService", "Sensor warm-up stopped")
    }

    /**
     * Scale raw summed GPS segments toward typical true path length (zig-zag / multipath overcount).
     * Step-based distance when GPS is stale is not scaled.
     */
    private fun scaledGpsDistanceMeters(rawMeters: Double): Double {
        if (!rawMeters.isFinite() || rawMeters <= 0.0) return 0.0
        return rawMeters * GPS_PATH_DISTANCE_SCALE
    }

    /** Auto-stop and save session when idle timeout is reached. */
    private fun performAutoStopAndSave() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        val stats = _sessionStats.value
        val startMs = sessionStartTimeMs
        val routePath = getRoutePath()
        val accelSamples = getAccelerometerSamples()
        val adjustedStats = adjustStatsForSimplifiedPath(stats, routePath)
        val statsWithRoute = adjustedStats.copy(routePath = routePath)
        val app = applicationContext as KineticEcoApplication
        val appCtx = app.applicationContext

        userPrefsManager.setPendingResumeAfterIdleAutoStop(true)
        stopTracking()

        try {
            AutoStartMonitorService.start(appCtx)
        } catch (e: Exception) {
            android.util.Log.e("TrackingService", "Auto-stop: could not re-arm auto-start monitor", e)
        }

        if (userId != null && userId.isNotEmpty()) {
            app.applicationScope.launch(Dispatchers.IO) {
                try {
                    SessionManager(applicationContext).saveSession(
                        userId,
                        statsWithRoute,
                        startMs,
                        accelSamples
                    )
                    android.util.Log.d("TrackingService", "Auto-stop: session saved successfully")
                } catch (e: Exception) {
                    android.util.Log.e("TrackingService", "Auto-stop: failed to save session", e)
                }
            }
        }
    }

    fun setManualActivityMode(activity: ActivityType?) {
        _manualActivityMode.value = activity
        _leanActivityHint.value = null
        if (activity == null) {
            leanHintDismissedAtMs = 0L
        }
        if (activity != null) {
            _currentActivity.value = activity
            // Reset persistence tracking when manually selecting an activity
            activityStartTime = System.currentTimeMillis()
            activityDurationSeconds = 0
            activityHistory.clear()
        }
        // Whenever the manual-mode pin changes (set, replaced, or cleared),
        // discard any in-flight mismatch detection — the user's just answered
        // the question we'd have prompted them about.
        manualMismatchCandidate = null
        manualMismatchSinceMs = 0L
        manualMismatchLastPromptMs = 0L
        dismissManualMismatchNotification()
    }

    fun dismissLeanActivityHint() {
        _leanActivityHint.value = null
        leanHintDismissedAtMs = System.currentTimeMillis()
    }
    
    /**
     * Reload user's physical profile from preferences
     * Call this after the user updates their profile in settings
     */
    fun reloadPhysicalProfile() {
        userPhysicalProfile = userPrefsManager.loadPhysicalProfile()
        android.util.Log.d("TrackingService", "Physical profile reloaded: $userPhysicalProfile")
    }

    fun reloadVehicleProfile() {
        vehicleProfile = userPrefsManager.loadVehicleProfile()
        android.util.Log.d("TrackingService", "Vehicle profile reloaded: $vehicleProfile")
    }

    fun resetSession() {
        _sessionDuration.value = 0L
        _sessionDistance.value = 0.0
        _sessionSteps.value = 0
        _sessionStats.value = SessionStats()
        _currentActivity.value = ActivityType.IDLE
        idleStartTimeMs = 0L
        motorLowSpeedSinceMs = 0L
        lastDrivingBandMs = 0L
        pedestrianToMotorRiseSinceMs = 0L
        lastPosition = null
        previousAltitude = 0.0
        lastUpdateTime = 0
        smoothedSpeed = 0f
        _currentSpeed.value = 0f
        activityHistory.clear()
        lastStepCount = 0
        stepCountAtLastGps = 0
        currentAcceleration = 0f
        currentRotationRate = 0f
        totalElevationGain = 0.0
        totalElevationLoss = 0.0
        startingAltitude = null
        stoppingAltitude = null
        minAltitude = null
        maxAltitude = null
        kmMilestones.clear()
        sessionTopSpeedMps = 0.0
        pathPoints.clear()
        lastPathRecordTime = 0
        lastRefinedActivity = ActivityType.IDLE
        locationService.setFlyingMode(false)
        accelerometerSamples.clear()
        lastAccelSampleTimeMs = 0
        kalmanFilter.reset()
        flightTracker.reset()

        pocketLeanEstimator.reset()
        leanMotorPromoSinceMs = 0L
        leanBikePromoSinceMs = 0L
        _leanActivityHint.value = null
        leanHintDismissedAtMs = 0L
        
        // Reset activity persistence tracking
        activityStartTime = System.currentTimeMillis()
        activityDurationSeconds = 0
        sessionStartTimeMs = 0
    }

    /** Get session start time in ms (when user started tracking) - for correct session date. */
    fun getSessionStartTimeMs(): Long = sessionStartTimeMs
    
    /** Get accelerometer samples collected during tracking (1 sample/sec) for Firestore analytics. */
    fun getAccelerometerSamples(): List<AccelerometerSample> = accelerometerSamples.toList()

    /** Get recorded route path for map display (called when saving session). */
    fun getRoutePath(): List<RoutePoint> {
        val list = pathPoints.toMutableList()
        // Ensure last known position is included for accurate route end
        lastPosition?.let { pos ->
            if (list.isEmpty() || list.last().latitude != pos.latitude || list.last().longitude != pos.longitude) {
                list.add(
                    RoutePoint(
                        pos.latitude,
                        pos.longitude,
                        lastRefinedActivity,
                        pos.altitude?.takeIf { it.isFinite() }
                    )
                )
            }
        }
        return list
    }

    /**
     * Record a route point if first point, or position changed, or interval elapsed (even when stationary).
     * Ensures route displays even when autodetect shows IDLE (slow movement) or GPS reports delayed speed.
     */
    private fun recordPathPointIfNeeded(
        lat: Double,
        lon: Double,
        altitudeMeters: Double?,
        refinedActivity: ActivityType,
        now: Long
    ) {
        val isFirstPoint = pathPoints.isEmpty()
        val positionChanged = isFirstPoint ||
            pathPoints.last().latitude != lat ||
            pathPoints.last().longitude != lon
        val intervalElapsed = isFirstPoint || (now - lastPathRecordTime) >= PATH_RECORD_INTERVAL_MS
        if (positionChanged && intervalElapsed) {
            pathPoints.add(
                RoutePoint(
                    lat,
                    lon,
                    refinedActivity,
                    altitudeMeters?.takeIf { it.isFinite() }
                )
            )
            lastPathRecordTime = now
            // Update sessionStats with route so live view shows during tracking
            _sessionStats.value = _sessionStats.value.copy(routePath = getRoutePath())
        }
    }

    private fun updateSensors(data: SensorData) {
        currentAcceleration = data.acceleration
        currentRotationRate = data.rotationRate

        if (sensorService.hasGyroscope()) {
            pocketLeanEstimator.addSample(data.timestamp, data.gyroX, data.gyroY, data.gyroZ, data.accelX, data.accelY)
        }
        val now = System.currentTimeMillis()

        // Sample accelerometer at 1 Hz for Firestore analytics
        if (data.isAccelerometerReading && (now - lastAccelSampleTimeMs) >= ACCEL_SAMPLE_INTERVAL_MS) {
            lastAccelSampleTimeMs = now
            accelerometerSamples.add(AccelerometerSample(
                timestampMs = now,
                x = data.accelX,
                y = data.accelY,
                z = data.accelZ,
                magnitude = data.acceleration
            ))
        }
        
        // Layer 1 (movement detection): Pedometer detects steps → person is moving → IDLE → WALKING
        // Guard: Don't override to WALKING when speed < 0.5 km/h (user preference: treat as IDLE)
        if (data.stepCount > lastStepCount && _manualActivityMode.value == null) {
            val stepDelta = data.stepCount - lastStepCount
            val speedKmh = _currentSpeed.value * 3.6f
            if (_currentActivity.value == ActivityType.IDLE && speedKmh >= 0.5f) {
                _currentActivity.value = ActivityType.WALKING
                activityHistory.clear()
                activityHistory.add(ActivityType.WALKING)
                activityStartTime = now
                android.util.Log.d("TrackingService", "Layer1: Steps detected (+$stepDelta) → IDLE → WALKING")
            }
        }
        
        // Track steps - count for WALKING and RUNNING; use step-based distance when GPS is weak
        if (data.stepCount > lastStepCount) {
            val stepDelta = data.stepCount - lastStepCount
            lastStepCount = data.stepCount
            lastStepTimestamp = now
            
            val statsActivity = activityForNonGpsStats()
            if (statsActivity == ActivityType.WALKING || statsActivity == ActivityType.RUNNING) {
                _sessionSteps.value += stepDelta
                updateStepsInStats(stepDelta, statsActivity)
                // Step-based distance fallback when GPS hasn't updated (urban/indoor)
                val gpsStale = lastUpdateTime > 0 && (now - lastGpsDistanceUpdateMs) > STEP_BASED_GAP_THRESHOLD_MS
                if (gpsStale) {
                    val stepLength = if (statsActivity == ActivityType.RUNNING) {
                        STEP_LENGTH_RUNNING
                    } else {
                        STEP_LENGTH_WALKING_STALE
                    }
                    val distanceDelta = stepDelta * stepLength
                    _sessionDistance.value += distanceDelta
                    stepCountAtLastGps = lastStepCount
                    val estSpeed = getActivitySpeed(statsActivity).toDouble()
                    updateStats(distanceDelta, statsActivity, estSpeed, 0.0, 0.0)
                }
                android.util.Log.d("TrackingService", 
                    "Steps: +$stepDelta (Total: ${_sessionSteps.value}) - Activity: $statsActivity")
            }
        }
    }
    
    private fun updateStepsInStats(stepDelta: Int, activity: ActivityType) {
        val currentStats = _sessionStats.value
        val breakdown = currentStats.breakdown.toMutableMap()
        val currentBreakdown = breakdown[activity] ?: ActivityBreakdown()
        
        breakdown[activity] = ActivityBreakdown(
            time = currentBreakdown.time,
            distance = currentBreakdown.distance,
            steps = currentBreakdown.steps + stepDelta
        )
        
        _sessionStats.value = withElevationAndMilestones(currentStats.copy(
            totalSteps = currentStats.totalSteps + stepDelta,
            breakdown = breakdown
        ))
    }
    
    /**
     * When step cadence clearly indicates walking, GPS speed can still spike
     * (mountains, multipath, pocket bounce). Cap speed before classification
     * so UI/activity match real motion; skip when motion looks vehicular
     * (accelerometer SMOOTH pattern).
     *
     * **Foot-traffic detection** combines two signals:
     *  - **Recent steps**: a step in the last [PEDESTRIAN_RECENT_STEP_WINDOW_MS]
     *    is a strong, instant indicator the user is on foot (the step counter
     *    works fine indoors and is far more reliable than urban-canyon GPS).
     *  - **Session cadence**: total steps / total session minutes — the
     *    steady-state baseline that survives brief gaps in the step stream.
     * Either being active is enough to apply the cap, dropping the previous
     * "must have ≥10 session steps" warm-up gap that left the first ~15 s of
     * walking unprotected.
     *
     * **Cap selection** is a single threshold instead of two overlapping
     * windows — at 130 spm we treat the user as jogging/running and let the
     * looser run-cap apply; below that we use the tighter walk-cap. Fixes the
     * gap at 95 ≤ session_spm < 130 where brisk walkers were previously only
     * shielded by the run-cap (4.2 m/s) — high enough to still be reclassified
     * as RUNNING and one tick later as DRIVING.
     */
    private fun applyPedestrianGpsSanityCap(
        speed: Float,
        now: Long,
        motionPattern: MotionPattern,
        altitudeM: Double,
        horizontalAccuracyM: Float
    ): Float {
        if (_manualActivityMode.value != null) return speed
        if (sessionStartTimeMs <= 0L) return speed
        if (motionPattern == MotionPattern.SMOOTH) return speed

        val elapsedSec = ((now - sessionStartTimeMs) / 1000.0).coerceAtLeast(1.0)
        val steps = _sessionSteps.value
        val sessionSpm = (steps / (elapsedSec / 60.0)).toFloat()
        val recentlyStepped = lastStepTimestamp > 0L &&
            (now - lastStepTimestamp) < PEDESTRIAN_RECENT_STEP_WINDOW_MS

        // On-foot if either: a step came in very recently, OR the session has
        // a meaningful cadence baseline. PEDESTRIAN_MIN_STEPS_FOR_CAP is set
        // low (4) because the step counter is reliable indoors and we'd rather
        // start protecting early than miss the first urban-canyon spike.
        val onFoot = recentlyStepped ||
            (steps >= PEDESTRIAN_MIN_STEPS_FOR_CAP && sessionSpm > 30f)
        if (!onFoot) return speed

        val poorGps = horizontalAccuracyM > 20f
        val highAltitude = altitudeM.isFinite() && altitudeM > 1200.0

        // Tighter caps when GPS is weak or at high altitude — multipath /
        // satellite geometry both make speed less trustworthy.
        val walkCap = when {
            highAltitude && poorGps -> 2.0f
            highAltitude -> 2.3f
            poorGps -> 2.2f
            else -> 2.6f
        }
        val runCap = when {
            highAltitude && poorGps -> 3.2f
            highAltitude -> 3.6f
            else -> 4.2f
        }

        // Only the session-average cadence reliably distinguishes walk from
        // run; recent steps alone don't (one rapid step burst can mimic
        // either). At ≥130 spm we treat the user as running.
        val likelyRunning = sessionSpm >= 130f
        val cap = if (likelyRunning) runCap else walkCap

        if (speed >= SpeedThresholds.RUNNING_MIN.toFloat() && speed > cap) {
            android.util.Log.d(
                "TrackingService",
                "Pedestrian sanity (${if (likelyRunning) "run" else "walk"}): " +
                    "spm=${sessionSpm.toInt()} recent=$recentlyStepped → " +
                    "cap ${cap * 3.6f} km/h vs GPS ${speed * 3.6f} km/h"
            )
            return cap
        }
        return speed
    }

    private fun validateSpeed(speed: Float, activity: ActivityType, altitude: Double?): Float {
        // Flying-specific speed validation
        if (activity == ActivityType.FLYING) {
            val isAtCruise = (altitude ?: 0.0) > 8000.0
            val maxSpeed = if (isAtCruise) {
                333.0f  // 1200 km/h at cruise altitude
            } else {
                150.0f  // 540 km/h during takeoff/landing
            }
            
            if (speed > maxSpeed) {
                android.util.Log.w("TrackingService", 
                    "Implausible flying speed: ${speed * 3.6f} km/h at ${altitude ?: 0}m altitude")
                return smoothedSpeed  // Keep previous
            }
            return speed
        }
        
        // Ground activities - standard validation
        val maxSpeed = MAX_REALISTIC_SPEEDS[activity] ?: 50.0f
        if (speed > maxSpeed) {
            android.util.Log.d("TrackingService", 
                "Speed ${speed * 3.6f} km/h exceeds max for $activity, capping to ${maxSpeed * 3.6f} km/h")
            return maxSpeed
        }
        return speed
    }
    
    private fun smoothSpeedWithOutlierRejection(newSpeed: Float, activity: ActivityType): Float {
        // First reading
        if (smoothedSpeed == 0f) {
            smoothedSpeed = newSpeed
            return newSpeed
        }
        
        val maxChange = when {
            activity == ActivityType.FLYING -> MAX_SPEED_CHANGE_FLYING
            activity == ActivityType.WALKING || activity == ActivityType.IDLE ->
                MAX_SPEED_CHANGE_WALKING
            else -> MAX_SPEED_CHANGE
        }
        
        // Reject outliers (sudden massive speed changes)
        val speedChange = Math.abs(newSpeed - smoothedSpeed)
        if (speedChange > maxChange) {
            android.util.Log.d("TrackingService", 
                "Rejecting speed outlier: ${newSpeed * 3.6f} km/h (change: ${speedChange * 3.6f} km/h, max: ${maxChange * 3.6f} km/h)")
            return smoothedSpeed // Keep previous speed
        }
        
        // Walking: smoother (alpha low) to damp GPS spikes; driving: more responsive; flying: stable mid-band
        val alpha = when (activity) {
            ActivityType.FLYING -> 0.4f
            ActivityType.WALKING, ActivityType.IDLE -> 0.35f
            ActivityType.RUNNING -> 0.55f
            else -> 0.85f
        }
        smoothedSpeed = (alpha * newSpeed) + ((1 - alpha) * smoothedSpeed)
        return smoothedSpeed
    }

    /**
     * Anti-spike gate for promoting a pedestrian activity (IDLE/WALKING/RUNNING/
     * CYCLING) into a motor mode (DRIVING/EV/TRAIN/FLYING). Single-tick GPS
     * multipath spikes in urban canyons can briefly push smoothed speed above
     * [SpeedThresholds.DRIVING_MIN] — before this gate, that one fix was enough
     * to hard-switch the user from "walking" to "driving" in the UI.
     *
     * The candidate motor activity must be re-proposed for at least
     * [PEDESTRIAN_TO_MOTOR_PROMOTE_MS] of wall-clock time before the switch is
     * accepted. If any tick during that window drops back below the boundary
     * (typical of multipath spikes — they last 1–2 ticks at most) the latency
     * counter resets.
     *
     * Bypassed when the accelerometer reports SMOOTH motion (a real vehicle —
     * promote immediately) or when the user is in manual activity mode.
     *
     * Symmetrical with [applyStickyMotorActivity], which guards the *exit* from
     * motor modes; together they damp both sides of the boundary.
     */
    private fun applyPedestrianToMotorPromotionLatency(
        refinedActivity: ActivityType,
        now: Long
    ): ActivityType {
        if (_manualActivityMode.value != null) {
            pedestrianToMotorRiseSinceMs = 0L
            return refinedActivity
        }

        val cur = _currentActivity.value
        val curIsPedestrian = cur == ActivityType.IDLE ||
            cur == ActivityType.WALKING ||
            cur == ActivityType.RUNNING ||
            cur == ActivityType.CYCLING
        val newIsMotor = refinedActivity == ActivityType.DRIVING ||
            refinedActivity == ActivityType.ELECTRIC_VEHICLE ||
            refinedActivity == ActivityType.MOTORCYCLE ||
            refinedActivity == ActivityType.TRAIN ||
            refinedActivity == ActivityType.FLYING

        if (!curIsPedestrian || !newIsMotor) {
            pedestrianToMotorRiseSinceMs = 0L
            return refinedActivity
        }

        // Real vehicles produce a SMOOTH accelerometer signature within
        // ~3–5 s of starting to drive; promote immediately when we see one so
        // legitimate vehicle starts aren't delayed.
        if (sensorService.getMotionPattern() == MotionPattern.SMOOTH) {
            pedestrianToMotorRiseSinceMs = 0L
            return refinedActivity
        }

        if (pedestrianToMotorRiseSinceMs == 0L) {
            pedestrianToMotorRiseSinceMs = now
        }
        val sustainedFor = now - pedestrianToMotorRiseSinceMs
        return if (sustainedFor < PEDESTRIAN_TO_MOTOR_PROMOTE_MS) {
            android.util.Log.d(
                "TrackingService",
                "Promotion latency: holding $cur (candidate $refinedActivity sustained ${sustainedFor}ms / required ${PEDESTRIAN_TO_MOTOR_PROMOTE_MS}ms)"
            )
            cur
        } else {
            pedestrianToMotorRiseSinceMs = 0L
            refinedActivity
        }
    }

    /**
     * Hysteresis for motor modes: brief low-speed stretches (traffic, parking) do not drop to walk/run
     * until speed stays below the driving band for [STICKY_RECENT_DRIVING_MS] and then [MOTOR_LOW_SPEED_EXIT_MS].
     */
    private fun applyStickyMotorActivity(refinedActivity: ActivityType, speed: Float, now: Long): ActivityType {
        val cur = _currentActivity.value
        val isMotor = cur == ActivityType.DRIVING || cur == ActivityType.ELECTRIC_VEHICLE ||
            cur == ActivityType.MOTORCYCLE || cur == ActivityType.TRAIN
        val aboveDriving = speed >= SpeedThresholds.DRIVING_MIN

        if (aboveDriving) {
            lastDrivingBandMs = now
            motorLowSpeedSinceMs = 0L
            return refinedActivity
        }

        if (!isMotor) {
            motorLowSpeedSinceMs = 0L
            lastDrivingBandMs = 0L
            return refinedActivity
        }

        if (lastDrivingBandMs == 0L) {
            lastDrivingBandMs = now
        }

        val recentDrivingBand = (now - lastDrivingBandMs) <= STICKY_RECENT_DRIVING_MS
        if (recentDrivingBand) {
            motorLowSpeedSinceMs = 0L
            return cur
        }

        if (motorLowSpeedSinceMs == 0L) {
            motorLowSpeedSinceMs = now
        }
        val lowFor = now - motorLowSpeedSinceMs
        return if (lowFor < MOTOR_LOW_SPEED_EXIT_MS) {
            cur
        } else {
            refinedActivity
        }
    }

    private fun scheduleRailCorridorLookupIfDue(
        refinedActivity: ActivityType,
        lat: Double,
        lon: Double,
        now: Long
    ) {
        if (!_isTracking.value) return
        if (_manualActivityMode.value != null) return
        if (refinedActivity == ActivityType.FLYING) return

        val minIntervalMs = 52_000L
        val minMoveM = 90.0
        val since = now - lastRailScheduleWallMs
        val moved =
            if (lastRailScheduleLat != null && lastRailScheduleLon != null) {
                locationService.calculateDistance(lastRailScheduleLat!!, lastRailScheduleLon!!, lat, lon)
            } else Double.MAX_VALUE
        if (railLookupJob?.isActive == true) return
        if (lastRailScheduleWallMs > 0L && since < minIntervalMs && moved < minMoveM) return

        lastRailScheduleWallMs = now
        lastRailScheduleLat = lat
        lastRailScheduleLon = lon

        railLookupJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val d = RailwayCorridorDetector.fetchMinDistanceToRailMeters(lat, lon)
                if (!isActive) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (!_isTracking.value) return@withContext
                    railMinDistanceM = d
                    railSampleAtMs = System.currentTimeMillis()
                    android.util.Log.d(
                        "TrackingService",
                        "Rail corridor OSM: minDistM=" + (d?.let { "%.1f".format(it) } ?: "null")
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!isActive) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (_isTracking.value) {
                        railMinDistanceM = null
                        railSampleAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    /**
     * Promotes [ActivityType.DRIVING] / EV → [ActivityType.TRAIN] when the filtered GPS fix
     * lies within a mapped railway corridor (OpenStreetMap via Overpass) and speed is already
     * in the motor band. Requires two consecutive qualifying readings to avoid spur noise.
     */
    private fun applyRailwayTrainDetection(
        refinedActivity: ActivityType,
        speed: Float,
        now: Long,
        lat: Double,
        lon: Double,
        horizontalAccuracyM: Float
    ): ActivityType {
        if (_manualActivityMode.value != null) return refinedActivity
        if (refinedActivity == ActivityType.FLYING) return refinedActivity

        scheduleRailCorridorLookupIfDue(refinedActivity, lat, lon, now)

        val freshWindowMs = 120_000L
        val sampleFresh = railSampleAtMs > 0L && (now - railSampleAtMs) <= freshWindowMs
        val staleUnknown = railSampleAtMs == 0L || (now - railSampleAtMs) > freshWindowMs

        val accBuffer = min(horizontalAccuracyM.toDouble(), 80.0).coerceAtLeast(0.0)
        val corridorLimitM = 40.0 + accBuffer * 0.9
        val d = railMinDistanceM

        val onCorridor = sampleFresh && d != null && d <= corridorLimitM
        val offCorridorConfirmed = sampleFresh && d != null && d > corridorLimitM + 18.0

        if (_currentActivity.value == ActivityType.TRAIN) {
            if (offCorridorConfirmed) {
                trainCorridorPositiveStreak = 0
                return if (speed >= SpeedThresholds.DRIVING_MIN.toFloat()) {
                    ActivityType.DRIVING
                } else refinedActivity
            }
            if (staleUnknown) {
                if (speed * 3.6f < 10f) return refinedActivity
                return ActivityType.TRAIN
            }
            return ActivityType.TRAIN
        }

        if (!onCorridor) {
            trainCorridorPositiveStreak = 0
            return refinedActivity
        }

        if (speed < SpeedThresholds.DRIVING_MIN.toFloat()) {
            trainCorridorPositiveStreak = 0
            return refinedActivity
        }

        if (refinedActivity != ActivityType.DRIVING && refinedActivity != ActivityType.ELECTRIC_VEHICLE) {
            trainCorridorPositiveStreak = 0
            return refinedActivity
        }

        trainCorridorPositiveStreak = (trainCorridorPositiveStreak + 1).coerceAtMost(50)
        return if (trainCorridorPositiveStreak >= 2) ActivityType.TRAIN else refinedActivity
    }

    /**
     * Pocket IMU: promote into [ActivityType.MOTORCYCLE] from car-like motor modes when
     * cornering-like gyro + lateral accel streaks match, and reinforce [ActivityType.CYCLING]
     * in the running-speed band when lean matches. Runs after rail detection so train wins on corridor.
     */
    private fun applyTwoWheelLeanPromotion(refinedActivity: ActivityType, speed: Float, now: Long): ActivityType {
        if (_manualActivityMode.value != null) return refinedActivity
        if (refinedActivity == ActivityType.FLYING || refinedActivity == ActivityType.TRAIN) return refinedActivity
        if (!sensorService.hasGyroscope()) return refinedActivity

        val score = pocketLeanEstimator.corneringScore(now)

        val motorCandidate =
            refinedActivity == ActivityType.DRIVING || refinedActivity == ActivityType.ELECTRIC_VEHICLE
        if (motorCandidate && speed >= SpeedThresholds.DRIVING_MIN) {
            if (score >= PocketTwoWheelLeanEstimator.PROMOTE_CORNER_SCORE_THRESHOLD) {
                if (leanMotorPromoSinceMs == 0L) leanMotorPromoSinceMs = now
                if (now - leanMotorPromoSinceMs >= leanPromoHoldMs) {
                    leanBikePromoSinceMs = 0L
                    return ActivityType.MOTORCYCLE
                }
            } else {
                leanMotorPromoSinceMs = 0L
            }
        } else {
            leanMotorPromoSinceMs = 0L
        }

        val bikeCandidate = refinedActivity == ActivityType.RUNNING || refinedActivity == ActivityType.CYCLING
        if (bikeCandidate &&
            speed >= SpeedThresholds.CYCLING_MIN &&
            speed < SpeedThresholds.DRIVING_MIN
        ) {
            if (score >= PocketTwoWheelLeanEstimator.PROMOTE_CORNER_SCORE_THRESHOLD) {
                if (leanBikePromoSinceMs == 0L) leanBikePromoSinceMs = now
                if (now - leanBikePromoSinceMs >= leanPromoHoldMs) {
                    leanMotorPromoSinceMs = 0L
                    return ActivityType.CYCLING
                }
            } else {
                leanBikePromoSinceMs = 0L
            }
        } else {
            leanBikePromoSinceMs = 0L
        }

        return refinedActivity
    }

    private fun evaluateLeanManualHint(speed: Float, now: Long, manual: ActivityType) {
        if (!sensorService.hasGyroscope()) return
        if (manual == ActivityType.MOTORCYCLE || manual == ActivityType.CYCLING) {
            if (_leanActivityHint.value != null) _leanActivityHint.value = null
            return
        }
        if (now - leanHintDismissedAtMs < leanHintCooldownMs) return

        val score = pocketLeanEstimator.corneringScore(now)
        if (score < PocketTwoWheelLeanEstimator.HINT_CORNER_SCORE_THRESHOLD) return

        val hint = when {
            (manual == ActivityType.DRIVING || manual == ActivityType.ELECTRIC_VEHICLE) &&
                speed >= SpeedThresholds.DRIVING_MIN -> ActivityType.MOTORCYCLE
            (manual == ActivityType.RUNNING || manual == ActivityType.WALKING) &&
                speed >= SpeedThresholds.CYCLING_MIN &&
                speed < SpeedThresholds.DRIVING_MIN -> ActivityType.CYCLING
            else -> null
        }
        if (hint != null && hint != manual && _leanActivityHint.value != hint) {
            _leanActivityHint.value = hint
        }
    }

    private fun updateLocation(position: GeoPosition) {
        // === STEP 1: Apply Kalman Filter for GPS smoothing ===
        val location = android.location.Location("gps").apply {
            latitude = position.latitude
            longitude = position.longitude
            speed = position.speed
            altitude = position.altitude ?: 0.0
            accuracy = position.accuracy
            time = position.timestamp
        }
        
        val filtered = kalmanFilter.update(location)
        
        // Update position: filtered lat/lon for distance, raw speed for display
        _currentPosition.value = position.copy(
            latitude = filtered.latitude,
            longitude = filtered.longitude,
            speed = position.speed,
            accuracy = filtered.accuracy
        )
        
        val now = System.currentTimeMillis()
        val timeDelta = if (lastUpdateTime > 0) (now - lastUpdateTime) / 1000.0 else 0.0
        
        // Get altitude: GPS first, barometric fallback when GPS altitude missing or poor (e.g. flying)
        val gpsAltitude = position.altitude
        val currentAltitude = when {
            gpsAltitude != null && gpsAltitude.isFinite() -> gpsAltitude
            sensorService.hasBarometer() -> sensorService.getBarometricAltitude(gpsAltitude)
            else -> 0.0
        }
        /** Only for route points — omit when neither GPS nor baro gives a usable value (avoid storing 0 as fake altitude). */
        val routeAltitudeMeters: Double? = when {
            gpsAltitude != null && gpsAltitude.isFinite() -> gpsAltitude
            sensorService.hasBarometer() -> sensorService.getBarometricAltitude(gpsAltitude).takeIf { it.isFinite() }
            else -> null
        }
        
        // Track starting/stopping/min/max altitude for session summary (GPS or barometric)
        if (currentAltitude.isFinite()) {
            if (startingAltitude == null) startingAltitude = currentAltitude
            stoppingAltitude = currentAltitude
            minAltitude = if (minAltitude == null) currentAltitude else minOf(minAltitude!!, currentAltitude)
            maxAltitude = if (maxAltitude == null) currentAltitude else maxOf(maxAltitude!!, currentAltitude)
        }
        
        // === STEP 2: Update Flight Tracker ===
        val flightState = if (timeDelta > 0) {
            flightTracker.updatePhase(
                position.speed,
                currentAltitude,
                previousAltitude,
                timeDelta.toFloat()
            )
        } else {
            flightTracker.updatePhase(position.speed, currentAltitude, previousAltitude, 1.0f)
        }
        
        // Adjust Kalman filter process noise based on flight phase
        kalmanFilter.setProcessNoise(flightTracker.getRecommendedProcessNoise())
        
        // Update GPS mode if flying status changed
        if (flightState.isFlying && _currentActivity.value != ActivityType.FLYING) {
            locationService.setFlyingMode(true)
        } else if (!flightState.isFlying && _currentActivity.value == ActivityType.FLYING) {
            locationService.setFlyingMode(false)
        }
        
        // Compute distance delta early for speed fallback
        val distanceDelta = if (lastPosition != null && timeDelta > 0.01) {
            locationService.calculateDistance(
                lastPosition!!.latitude,
                lastPosition!!.longitude,
                filtered.latitude,
                filtered.longitude
            )
        } else 0.0
        val distanceBasedSpeed = if (timeDelta > 0.1 && distanceDelta > 0.5) {
            (distanceDelta / timeDelta).toFloat()
        } else 0f
        
        // Speed pipeline: raw GPS first, then Kalman fallback, then distance-based fallback
        var speed = position.speed.coerceAtLeast(0f)
        val kalmanSpeed = filtered.speed.coerceAtLeast(0f)
        val gpsUnreliable = speed < GPS_SPEED_UNRELIABLE_MAX || position.accuracy > POOR_ACCURACY_METERS
        if (gpsUnreliable) {
            if (kalmanSpeed > GPS_SPEED_UNRELIABLE_MAX) {
                speed = kalmanSpeed
            } else if (distanceBasedSpeed > GPS_SPEED_UNRELIABLE_MAX) {
                speed = distanceBasedSpeed
            } else if (smoothedSpeed > SUSPICIOUS_ZERO_HOLD_SPEED) {
                speed = smoothedSpeed  // Reject suspicious 0: hold last when clearly moving
            }
        }
        
        // Get motion pattern for smart classification
        val motionPattern = sensorService.getMotionPattern()
        val hasAccelerometer = sensorService.hasAccelerometer()
        
        speed = applyPedestrianGpsSanityCap(
            speed = speed,
            now = now,
            motionPattern = motionPattern,
            altitudeM = currentAltitude,
            horizontalAccuracyM = position.accuracy
        )
        
        // === STEP 3: Classify activity with altitude awareness ===
        val preliminaryActivity = if (_manualActivityMode.value != null) {
            _manualActivityMode.value!!
        } else {
            locationService.classifyActivityWithAltitude(
                speed,
                currentAltitude,
                previousAltitude,
                motionPattern,
                hasAccelerometer
            )
        }
        
        // Apply activity-specific speed cap BEFORE smoothing
        speed = validateSpeed(speed, preliminaryActivity, currentAltitude)

        // Sensor-based speed adjustments (only for ground activities)
        // GUARD: Never zero/cap when speed > 3 km/h - user is clearly moving
        if (preliminaryActivity != ActivityType.FLYING && speed < IDLE_OVERRIDE_THRESHOLD) {
            when (preliminaryActivity) {
                ActivityType.IDLE -> {
                    if (currentAcceleration < 0.15f && currentRotationRate < 0.1f) {
                        speed = 0f
                    }
                }
                ActivityType.WALKING, ActivityType.RUNNING -> {
                    if (currentAcceleration < 0.5f) {
                        speed = Math.min(speed, 1.0f)
                    }
                }
                ActivityType.CYCLING, ActivityType.MOTORCYCLE, ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE, ActivityType.TRAIN -> {
                    if (currentAcceleration < 0.05f && speed < 1.0f) {
                        speed = 0f
                    }
                }
                else -> {}
            }
        }

        // Apply EMA smoothing with outlier rejection
        speed = smoothSpeedWithOutlierRejection(speed, preliminaryActivity)
        _currentSpeed.value = speed
        
        // Reclassify with smoothed speed
        var refinedActivity = if (_manualActivityMode.value != null) {
            _manualActivityMode.value!!
        } else {
            locationService.classifyActivityWithAltitude(
                speed,
                currentAltitude,
                previousAltitude,
                motionPattern,
                hasAccelerometer
            )
        }
        
        // Speed gates: Strict boundaries (user preference - immediate, consistent switches)
        // When manual activity is selected, skip gates so all data goes to the chosen category only
        if (_manualActivityMode.value == null) {
            refinedActivity = when {
                speed < SpeedThresholds.IDLE_SPEED_MAX -> ActivityType.IDLE
                speed >= SpeedThresholds.DRIVING_MIN -> {
                    // >= 18 km/h: Never RUNNING/WALKING/IDLE (strict gate)
                    if (refinedActivity == ActivityType.FLYING) ActivityType.FLYING
                    else ActivityType.DRIVING
                }
                else -> enforceActivitySpeedConsistency(refinedActivity, speed, currentAltitude)
            }
            refinedActivity = applyStickyMotorActivity(refinedActivity, speed, now)
            refinedActivity = applyPedestrianToMotorPromotionLatency(refinedActivity, now)
            refinedActivity = applyRailwayTrainDetection(
                refinedActivity,
                speed,
                now,
                filtered.latitude,
                filtered.longitude,
                position.accuracy
            )
            refinedActivity = applyTwoWheelLeanPromotion(refinedActivity, speed, now)
        } else {
            leanMotorPromoSinceMs = 0L
            leanBikePromoSinceMs = 0L
            if (_leanActivityHint.value != null) _leanActivityHint.value = null
        }
        lastRefinedActivity = refinedActivity

        // Manual-mode shadow classification: while the user has pinned a
        // specific activity, run the auto-classifier in the background and
        // prompt them if it sustainedly disagrees with their choice.
        val manual = _manualActivityMode.value
        if (manual != null) {
            val shadow = computeShadowAutoActivity(
                speed = speed,
                currentAltitude = currentAltitude,
                previousAltitude = previousAltitude,
                motionPattern = motionPattern,
                hasAccelerometer = hasAccelerometer,
                now = now
            )
            evaluateManualOverrideMismatch(shadow, manual, now)
            evaluateLeanManualHint(speed, now, manual)
        }
        
        sessionTopSpeedMps = maxOf(sessionTopSpeedMps, speed.toDouble())
        
        updateActivityWithDebounce(refinedActivity)
        
        // Log flight phase for debugging
        if (refinedActivity == ActivityType.FLYING) {
            android.util.Log.d("TrackingService", 
                "Flying: Phase=${flightState.phase}, Alt=${currentAltitude.toInt()}m, Speed=${(speed * 3.6f).toInt()} km/h")
        }
        
        if (lastPosition != null) {
            // distanceDelta already computed above for speed fallback
            
            // Elevation: do not treat cabin/cruise GPS altitude drift as "climbing" — that inflated calorie burn on flights.
            var elevationGainDelta = 0.0
            var elevationLossDelta = 0.0
            if (refinedActivity != ActivityType.FLYING) {
                val elevationDelta = currentAltitude - previousAltitude
                if (elevationDelta > 0) {
                    elevationGainDelta = elevationDelta
                    totalElevationGain += elevationDelta
                } else if (elevationDelta < 0) {
                    elevationLossDelta = Math.abs(elevationDelta)
                    totalElevationLoss += Math.abs(elevationDelta)
                }
            }
            
            val isActuallyMoving = speed > 0.15f || currentAcceleration > 0.2f
            val baseMeet = distanceDelta >= 1.0 || speed >= SpeedThresholds.WALKING_MIN ||
                (distanceDelta >= 0.5 && speed > 0.15f)
            val meetsDistanceThreshold = if (refinedActivity == ActivityType.WALKING) {
                baseMeet && (
                    distanceDelta >= WALKING_GPS_MIN_SEGMENT_M ||
                        speed >= SpeedThresholds.WALKING_MIN ||
                        (distanceDelta >= 0.9 && speed > 0.2f)
                    )
            } else {
                baseMeet
            }

            var dd = distanceDelta
            val accLimit = if (refinedActivity == ActivityType.WALKING) {
                MAX_ACCURACY_METERS_WALKING_DISTANCE
            } else {
                MAX_ACCURACY_METERS_FOR_DISTANCE
            }
            if (refinedActivity != ActivityType.FLYING && position.accuracy > accLimit) {
                dd = 0.0
            } else if (refinedActivity == ActivityType.WALKING && dd > 0) {
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                val speedCap = CAP_WALKING_SPEED_MPS * timeDelta
                // If no new steps this GPS interval, stepCap would be ~slack only and caps every
                // segment (~2–4m) — massive undercount. Only blend-cap when we observed steps.
                dd = if (stepsThisInterval > 0) {
                    val stepCap = stepsThisInterval * STEP_LENGTH_WALKING + STEP_GPS_BLEND_SLACK_M
                    minOf(dd, stepCap, speedCap)
                } else {
                    minOf(dd, speedCap)
                }
            } else if (refinedActivity == ActivityType.RUNNING && dd > 0) {
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                val speedCap = CAP_RUNNING_SPEED_MPS * timeDelta
                dd = if (stepsThisInterval > 0) {
                    val stepCap = stepsThisInterval * STEP_LENGTH_RUNNING + STEP_GPS_BLEND_SLACK_M
                    minOf(dd, stepCap, speedCap)
                } else {
                    minOf(dd, speedCap)
                }
            }

            if (isActuallyMoving && meetsDistanceThreshold && dd > 0) {
                val ddScaled = scaledGpsDistanceMeters(dd)
                _sessionDistance.value += ddScaled
                lastGpsDistanceUpdateMs = now
                stepCountAtLastGps = lastStepCount
                updateStats(ddScaled, refinedActivity, speed.toDouble(), elevationGainDelta, elevationLossDelta)
                checkAndShowKmMilestoneNotification()
                // Record route point on movement (in addition to time interval)
                recordPathPointIfNeeded(filtered.latitude, filtered.longitude, routeAltitudeMeters, refinedActivity, now)
            }
        }
        
        lastPosition = position.copy(
            latitude = filtered.latitude,
            longitude = filtered.longitude
        )
        previousAltitude = currentAltitude
        lastUpdateTime = now
        deadReckonStartMs = 0L  // Reset - we got GPS, no longer in gap
        
        // Record path point: first point immediately, then on position change or every PATH_RECORD_INTERVAL_MS
        // This ensures route displays even when autodetect shows IDLE (slow movement) or GPS is delayed
        recordPathPointIfNeeded(filtered.latitude, filtered.longitude, routeAltitudeMeters, refinedActivity, now)
        
        // Update notification
        updateNotification()
    }

    /**
     * Layer 2: Enforce speed-activity consistency using per-activity thresholds (speed only).
     * - Too fast: speed >= upper bound of activity range → reclassify by speed
     * - Too slow: speed < lower bound of activity range → reclassify by speed
     * - At boundary: treat as inconsistent and correct
     * Altitude is NOT used - high altitude (e.g. mountains) does not force FLYING.
     * Bounds use SpeedThresholds (Android: IDLE<0.5, WALKING<2, RUNNING<18, DRIVING 18-180, FLYING 180+ km/h)
     */
    private fun enforceActivitySpeedConsistency(
        activity: ActivityType,
        speed: Float,
        @Suppress("UNUSED_PARAMETER") altitude: Double
    ): ActivityType {
        val (lowerMps, upperMps) = getActivitySpeedBounds(activity) ?: return activity
        val tooFast = speed >= upperMps
        val tooSlow = speed < lowerMps
        if (!tooFast && !tooSlow) return activity
        
        val corrected = reclassifyBySpeed(speed)
        android.util.Log.d("TrackingService", 
            "Layer2: $activity @ ${speed * 3.6f} km/h (${if (tooFast) "too fast" else "too slow"} for range [$lowerMps, $upperMps)) → $corrected")
        return corrected
    }
    
    /** Bounds for Layer2 consistency. TRAIN overlaps DRIVING/EV speeds; railway geometry may auto-select TRAIN. */
    private fun getActivitySpeedBounds(activity: ActivityType): Pair<Float, Float>? = when (activity) {
        ActivityType.IDLE -> Pair(0f, SpeedThresholds.WALKING_MIN.toFloat())           // [0, 0.5)
        ActivityType.WALKING -> Pair(SpeedThresholds.WALKING_MIN.toFloat(), SpeedThresholds.RUNNING_MIN.toFloat())  // [0.5, 2)
        ActivityType.RUNNING -> Pair(SpeedThresholds.RUNNING_MIN.toFloat(), SpeedThresholds.DRIVING_MIN.toFloat()) // [2, 5) = 7–18 km/h
        ActivityType.CYCLING -> Pair(SpeedThresholds.CYCLING_MIN.toFloat(), SpeedThresholds.DRIVING_MIN.toFloat()) // [4, 5) m/s = 14–18 km/h
        ActivityType.TRAIN -> Pair(SpeedThresholds.DRIVING_MIN.toFloat(), 97f)
        ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE, ActivityType.MOTORCYCLE ->
            Pair(SpeedThresholds.DRIVING_MIN.toFloat(), SpeedThresholds.FLYING_MIN.toFloat())  // road motor band
        ActivityType.FLYING -> Pair(SpeedThresholds.FLYING_MIN.toFloat(), Float.MAX_VALUE)  // [50, ∞) = 180+ km/h
    }
    
    /** Reclassify activity purely by speed (jump to activity whose range contains speed). */
    private fun reclassifyBySpeed(speed: Float): ActivityType = when {
        speed < SpeedThresholds.WALKING_MIN -> ActivityType.IDLE
        speed < SpeedThresholds.RUNNING_MIN -> ActivityType.WALKING
        speed < SpeedThresholds.DRIVING_MIN -> ActivityType.RUNNING  // Android: running absorbs cycling
        speed < SpeedThresholds.FLYING_MIN -> ActivityType.DRIVING
        else -> ActivityType.FLYING
    }

    /**
     * Activity for step distance / step breakdown when GPS is stale. Must align with [lastRefinedActivity]
     * and speed so session summary matches map colors (route uses refined GPS, not debounced UI state).
     */
    private fun activityForNonGpsStats(): ActivityType {
        if (_manualActivityMode.value != null) return _manualActivityMode.value!!
        val speed = _currentSpeed.value
        if (speed >= SpeedThresholds.DRIVING_MIN) {
            if (speed >= SpeedThresholds.FLYING_MIN) return ActivityType.FLYING
            return if (lastRefinedActivity == ActivityType.MOTORCYCLE) ActivityType.MOTORCYCLE else ActivityType.DRIVING
        }
        val gpsAgeMs = if (lastUpdateTime > 0L) System.currentTimeMillis() - lastUpdateTime else Long.MAX_VALUE
        if (gpsAgeMs < 15_000L) return lastRefinedActivity
        return _currentActivity.value
    }
    
    /**
     * Check if two activities are compatible (likely to transition between).
     * Some transitions are highly unlikely in real life (e.g., driving -> running).
     */
    private fun areActivitiesCompatible(from: ActivityType, to: ActivityType): Boolean {
        // Same activity is always compatible
        if (from == to) return true
        
        // IDLE is compatible with everything (starting/stopping any activity)
        if (from == ActivityType.IDLE || to == ActivityType.IDLE) return true
        
        // Define incompatible transitions (highly unlikely in real life)
        val incompatiblePairs = setOf(
            // Can't go from high-speed activities directly to low-speed without stopping
            Pair(ActivityType.DRIVING, ActivityType.WALKING),
            Pair(ActivityType.DRIVING, ActivityType.RUNNING),
            Pair(ActivityType.ELECTRIC_VEHICLE, ActivityType.WALKING),
            Pair(ActivityType.ELECTRIC_VEHICLE, ActivityType.RUNNING),
            Pair(ActivityType.TRAIN, ActivityType.WALKING),
            Pair(ActivityType.TRAIN, ActivityType.RUNNING),
            Pair(ActivityType.TRAIN, ActivityType.CYCLING),
            Pair(ActivityType.TRAIN, ActivityType.MOTORCYCLE),
            Pair(ActivityType.FLYING, ActivityType.WALKING),
            Pair(ActivityType.FLYING, ActivityType.RUNNING),
            Pair(ActivityType.FLYING, ActivityType.CYCLING),
            Pair(ActivityType.FLYING, ActivityType.MOTORCYCLE),

            Pair(ActivityType.MOTORCYCLE, ActivityType.WALKING),
            Pair(ActivityType.MOTORCYCLE, ActivityType.RUNNING),
            Pair(ActivityType.MOTORCYCLE, ActivityType.CYCLING),
            Pair(ActivityType.CYCLING, ActivityType.MOTORCYCLE),

            // Can't jump from cycling to driving without a transition
            Pair(ActivityType.CYCLING, ActivityType.DRIVING),
            Pair(ActivityType.CYCLING, ActivityType.ELECTRIC_VEHICLE),
            Pair(ActivityType.CYCLING, ActivityType.TRAIN),
            
            // Can't go from running to driving/cycling without stopping
            Pair(ActivityType.RUNNING, ActivityType.DRIVING),
            Pair(ActivityType.RUNNING, ActivityType.ELECTRIC_VEHICLE),
            Pair(ActivityType.RUNNING, ActivityType.CYCLING),
            Pair(ActivityType.RUNNING, ActivityType.MOTORCYCLE),
            Pair(ActivityType.RUNNING, ActivityType.TRAIN),
        )
        
        // Check if this pair is incompatible (check both directions)
        return !incompatiblePairs.contains(Pair(from, to)) && 
               !incompatiblePairs.contains(Pair(to, from))
    }
    
    /**
     * Get typical speed for an activity (for change magnitude calculation).
     */
    private fun getActivitySpeed(activity: ActivityType): Float {
        return when (activity) {
            ActivityType.IDLE -> 0f
            ActivityType.WALKING -> 1.4f      // ~5 km/h
            ActivityType.RUNNING -> 3.5f      // ~12.5 km/h
            ActivityType.CYCLING -> 5.5f      // ~20 km/h
            ActivityType.MOTORCYCLE -> 17f    // ~60 km/h road typical
            ActivityType.TRAIN -> 27.8f       // ~100 km/h (typical rail)
            ActivityType.DRIVING -> 15f       // ~54 km/h
            ActivityType.ELECTRIC_VEHICLE -> 15f
            ActivityType.FLYING -> 150f       // ~540 km/h
        }
    }

    private fun updateActivityWithDebounce(activity: ActivityType) {
        if (_manualActivityMode.value == null) {
            when (activity) {
                ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE, ActivityType.MOTORCYCLE, ActivityType.TRAIN -> {
                    if (_currentActivity.value != activity) {
                        activityStartTime = System.currentTimeMillis()
                        activityDurationSeconds = 0
                        idleStartTimeMs = 0L
                        _currentActivity.value = activity
                        android.util.Log.d("TrackingService", "Activity immediate: → $activity (motor mode, no debounce)")
                    }
                    activityHistory.clear()
                    activityHistory.add(activity)
                    activityHistory.add(activity)
                    return
                }
                ActivityType.FLYING -> {
                    if (_currentActivity.value != activity) {
                        activityStartTime = System.currentTimeMillis()
                        activityDurationSeconds = 0
                        idleStartTimeMs = 0L
                        _currentActivity.value = activity
                        android.util.Log.d("TrackingService", "Activity immediate: → FLYING")
                    }
                    activityHistory.clear()
                    activityHistory.add(activity)
                    activityHistory.add(activity)
                    return
                }
                else -> { /* debounced below */ }
            }
            // Cannot be "Running" below running minimum speed — debounce often leaves RUNNING from the first noisy GPS ticks.
            val spd = _currentSpeed.value
            if (_currentActivity.value == ActivityType.RUNNING &&
                spd < SpeedThresholds.RUNNING_MIN.toFloat() &&
                activity != ActivityType.RUNNING
            ) {
                val t = System.currentTimeMillis()
                activityStartTime = t
                activityDurationSeconds = 0
                if (activity == ActivityType.IDLE) idleStartTimeMs = t else idleStartTimeMs = 0L
                _currentActivity.value = activity
                activityHistory.clear()
                activityHistory.add(activity)
                activityHistory.add(activity)
                android.util.Log.d(
                    "TrackingService",
                    "Activity immediate: speed ${spd * 3.6f} km/h < running min → $activity (clear stale RUNNING)"
                )
                return
            }
        }
        activityHistory.add(activity)
        if (activityHistory.size > ACTIVITY_HISTORY_SIZE) {
            activityHistory.removeAt(0)
        }
        
        // Calculate how long the current activity has been ongoing
        val currentTime = System.currentTimeMillis()
        activityDurationSeconds = (currentTime - activityStartTime) / 1000
        
        // Only update if we have enough readings
        if (activityHistory.size >= 2) {
            val mostCommon = activityHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            
            if (mostCommon == null || mostCommon == _currentActivity.value) {
                return  // No change needed
            }
            
            val consistentCount = activityHistory.count { it == mostCommon }
            val currentActivity = _currentActivity.value
            
            // Relaxed debounce for first N seconds to converge quickly at start
            val secondsSinceTrackingStart = (currentTime - sessionStartTimeMs) / 1000
            val isStartupPhase = secondsSinceTrackingStart < STARTUP_DEBOUNCE_SECONDS
            
            // Calculate activity change magnitude
            val isSignificantChange = (
                (currentActivity == ActivityType.IDLE && mostCommon != ActivityType.IDLE) ||
                (currentActivity != ActivityType.IDLE && mostCommon == ActivityType.IDLE) ||
                Math.abs(getActivitySpeed(currentActivity) - getActivitySpeed(mostCommon)) > 2f
            )
            
            // Check if activities are compatible
            val isCompatible = areActivitiesCompatible(currentActivity, mostCommon)
            
            // Activity Persistence Logic: The longer an activity continues, the harder to switch
            // Exceptions: Immediate switch (1 reading) for threshold crossings
            val isAdjacentTransition = (currentActivity == ActivityType.WALKING && mostCommon == ActivityType.RUNNING) ||
                (currentActivity == ActivityType.RUNNING && mostCommon == ActivityType.WALKING) ||
                (currentActivity == ActivityType.RUNNING && mostCommon == ActivityType.DRIVING) ||
                (currentActivity == ActivityType.DRIVING && mostCommon == ActivityType.RUNNING) ||
                (currentActivity == ActivityType.DRIVING && mostCommon == ActivityType.MOTORCYCLE) ||
                (currentActivity == ActivityType.MOTORCYCLE && mostCommon == ActivityType.DRIVING) ||
                (currentActivity == ActivityType.ELECTRIC_VEHICLE && mostCommon == ActivityType.MOTORCYCLE) ||
                (currentActivity == ActivityType.MOTORCYCLE && mostCommon == ActivityType.ELECTRIC_VEHICLE)
            var requiredConsistency = when {
                isStartupPhase -> 1  // First 10s: fast convergence
                mostCommon == ActivityType.IDLE -> 1  // Speed < 0.5 km/h → IDLE immediately
                isAdjacentTransition -> 1  // WALKING↔RUNNING, RUNNING↔DRIVING → immediate switch
                activityDurationSeconds > 120 -> {
                    android.util.Log.d("TrackingService",
                        "Activity persistence: $currentActivity ongoing for ${activityDurationSeconds}s - requiring 3/3 consistency")
                    3
                }
                else -> if (isSignificantChange) 2 else 3
            }

            // Incompatible transitions require ALL readings to be consistent, regardless of duration
            if (!isCompatible && mostCommon != ActivityType.IDLE) {
                requiredConsistency = 3
                android.util.Log.d("TrackingService", 
                    "Incompatible transition: $currentActivity -> $mostCommon - requiring 3/3 consistency")
            }
            
            // Check if we have enough consistent readings to switch
            if (consistentCount >= requiredConsistency) {
                // Reset activity start time when switching
                activityStartTime = currentTime
                activityDurationSeconds = 0
                if (mostCommon == ActivityType.IDLE) idleStartTimeMs = currentTime
                else idleStartTimeMs = 0

                _currentActivity.value = mostCommon
                android.util.Log.d("TrackingService", 
                    "Activity changed: $currentActivity -> $mostCommon ($consistentCount/${activityHistory.size} consistent, " +
                    "duration: ${(currentTime - activityStartTime) / 1000}s, compatible: $isCompatible)")
            } else {
                android.util.Log.d("TrackingService", 
                    "Activity change rejected: $currentActivity -> $mostCommon ($consistentCount/$requiredConsistency required, " +
                    "duration: ${activityDurationSeconds}s, compatible: $isCompatible)")
            }
        }
        // When history < 2: do NOT accept on first reading - require 2 consistent readings
        // to avoid noisy initial GPS from switching away from IDLE too soon.
    }

    /** Merge elevation, km milestones, and top speed into session stats (for session summary). */
    private fun withElevationAndMilestones(stats: SessionStats): SessionStats = stats.copy(
        elevationGain = totalElevationGain,
        elevationLoss = totalElevationLoss,
        startingAltitude = startingAltitude,
        stoppingAltitude = stoppingAltitude,
        minAltitude = minAltitude,
        maxAltitude = maxAltitude,
        kmMilestones = kmMilestones.toList(),
        topSpeedMps = sessionTopSpeedMps
    )
    
    private fun updateStats(
        distanceDelta: Double, 
        activity: ActivityType,
        speedMps: Double = 0.0,
        elevationGainDelta: Double = 0.0,
        elevationLossDelta: Double = 0.0
    ) {
        val currentStats = _sessionStats.value
        val breakdown = currentStats.breakdown.toMutableMap()
        val currentBreakdown = breakdown[activity] ?: ActivityBreakdown()
        
        // Preserve existing steps when updating time and distance
        breakdown[activity] = ActivityBreakdown(
            time = currentBreakdown.time + 1,
            distance = currentBreakdown.distance + distanceDelta,
            steps = currentBreakdown.steps // Preserve existing step count
        )
        
        val (emissions, conserved) = sessionManager.calculateCO2(distanceDelta, activity, vehicleProfile)
        
        // Use advanced calorie calculation with speed and elevation
        val caloriesDelta = sessionManager.calculateCalories(
            duration = 1L,
            activity = activity,
            speedMps = if (speedMps > 0) speedMps else null,
            elevationGain = if (activity == ActivityType.FLYING) 0.0 else elevationGainDelta,
            elevationLoss = if (activity == ActivityType.FLYING) 0.0 else elevationLossDelta,
            userProfile = userPhysicalProfile
        )
        
        _sessionStats.value = withElevationAndMilestones(currentStats.copy(
            totalDistance = currentStats.totalDistance + distanceDelta,
            co2Emissions = currentStats.co2Emissions + emissions,
            co2Conserved = currentStats.co2Conserved + conserved,
            caloriesBurned = currentStats.caloriesBurned + caloriesDelta,
            breakdown = breakdown
        ))
    }

    private fun startTimer() {
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (_isTracking.value) {
                    _sessionDuration.value += 1
                    val currentStats = _sessionStats.value
                    // When manual mode is set, use it for breakdown so all data goes to the chosen category only
                    val activity = _manualActivityMode.value ?: _currentActivity.value
                    
                    val breakdown = currentStats.breakdown.toMutableMap()
                    val currentBreakdown = breakdown[activity] ?: ActivityBreakdown()
                    
                    // Preserve existing steps when updating time
                    breakdown[activity] = ActivityBreakdown(
                        time = currentBreakdown.time + 1,
                        distance = currentBreakdown.distance,
                        steps = currentBreakdown.steps // Preserve step count
                    )
                    
                    val caloriesDelta = sessionManager.calculateCalories(
                        duration = 1L,
                        activity = activity,
                        userProfile = userPhysicalProfile
                    )
                    
                    _sessionStats.value = withElevationAndMilestones(currentStats.copy(
                        totalDuration = currentStats.totalDuration + 1,
                        caloriesBurned = currentStats.caloriesBurned + caloriesDelta,
                        breakdown = breakdown
                    ))
                }
            }
        }
    }

    private fun createNotification(): Notification {
        ensureTrackingChannel()
        return buildTrackingNotification(contentText = "Recording your activity...")
    }

    /** Idempotent setup of the foreground tracking channel — safe to call from
     *  both the initial [createNotification] path and the live [updateNotification]
     *  path.
     *
     *  Importance is **always** [NotificationManager.IMPORTANCE_DEFAULT] so the
     *  shade renders the full layout with the Save / Discard action row inline.
     *  When the user has notification sounds disabled we suppress sound +
     *  vibration at the channel level (and `setSilent(true)` on the builder)
     *  rather than dropping the channel to LOW, because LOW collapses the
     *  notification and forces the user to expand it just to see the actions. */
    private fun ensureTrackingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val soundsEnabled = userPrefsManager.getNotificationSoundsEnabled()
        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            "Kinetic Tracking",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows real-time tracking status"
            setShowBadge(false)
            enableVibration(soundsEnabled)
            setSound(
                if (soundsEnabled) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null,
                null
            )
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Single source of truth for the foreground tracking notification —
     * shared by [createNotification] (the very first post for `startForeground`)
     * and [updateNotification] (the per-tick refresh during tracking).
     *
     * Both action buttons are wired here:
     *  - **Stop & save** routes back into the service via [ACTION_STOP_AND_SAVE]
     *    so the same persistence path runs whether the user taps Stop in-app or
     *    on the notification shade.
     *  - **Discard** routes via [ACTION_DISCARD] for accidental auto-starts —
     *    stops tracking without writing anything to Firestore.
     */
    private fun buildTrackingNotification(contentText: String): Notification {
        val soundsEnabled = userPrefsManager.getNotificationSoundsEnabled()
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openAppPi = PendingIntent.getActivity(
            this, REQ_OPEN_APP, Intent(this, MainActivity::class.java), pendingFlags
        )

        val stopSaveIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP_AND_SAVE
        }
        val stopSavePi = PendingIntent.getService(
            this, REQ_STOP_SAVE, stopSaveIntent, pendingFlags
        )

        val discardIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_DISCARD
        }
        val discardPi = PendingIntent.getService(
            this, REQ_DISCARD, discardIntent, pendingFlags
        )

        val largeIcon = getAppIconBitmap()
        val builder = NotificationCompat.Builder(this, TRACKING_CHANNEL_ID)
            .setContentTitle("Kinetic Tracking")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .apply { largeIcon?.let { setLargeIcon(it) } }
            .setOngoing(true)
            .setContentIntent(openAppPi)
            .setOnlyAlertOnce(true)
            // Drop the timestamp row — it eats vertical space in the shade and
            // can push the action buttons below the fold on tall devices.
            .setShowWhen(false)
            // Action icons are no-ops on phones from API 24+ (only the label
            // shows in the shade), but supplying a sensible system icon keeps
            // the Wear OS / lock-screen rendering reasonable.
            // Labels are intentionally short ("Save" / "Discard") so both fit
            // inline in the collapsed notification on launchers that ration
            // horizontal space (Pixel, Samsung One UI, MIUI).
            .addAction(android.R.drawable.ic_menu_save, "Save", stopSavePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Discard", discardPi)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // FOREGROUND_SERVICE_IMMEDIATE bypasses the 10s "minimised" delay
            // that Android 12+ applies to short-lived foreground services so
            // the full notification (with action buttons) is visible the
            // moment tracking starts.
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (!soundsEnabled) {
            builder.setSilent(true)
        }
        return builder.build()
    }

    /**
     * Check if we've crossed a distance milestone (1 km in metric, 1 mile in imperial).
     * Records intervals for session summary — no standalone notification is posted (product choice).
     */
    private fun checkAndShowKmMilestoneNotification() {
        val metersPerUnit = userPrefsManager.getMetersPerUnit()
        val currentDistanceM = _sessionDistance.value
        val currentSegment = (currentDistanceM / metersPerUnit).toInt()
        if (currentSegment <= 0 || currentSegment <= lastNotifiedKm) return
        
        val currentDurationSec = _sessionDuration.value
        val secondsForThisSegment = if (lastNotifiedKm == 0) {
            currentDurationSec  // First unit = total duration so far
        } else {
            currentDurationSec - durationAtLastKm
        }
        
        // Always log for session summary (km field stores segment index)
        kmMilestones.add(KmMilestone(km = currentSegment, secondsForKm = secondsForThisSegment))

        lastNotifiedKm = currentSegment
        durationAtLastKm = currentDurationSec
    }

    private fun updateNotification() {
        if (!_isTracking.value) return

        val metersPerUnit = userPrefsManager.getMetersPerUnit()
        val unitLabel = userPrefsManager.getDistanceUnitLabel()
        val distanceInUnits = _sessionDistance.value / metersPerUnit
        val durationMin = _sessionDuration.value / 60
        val contentText = String.format(
            "%.2f %s • %d min • %s",
            distanceInUnits, unitLabel, durationMin,
            _currentActivity.value.name.lowercase()
        )

        val notification = buildTrackingNotification(contentText = contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show session complete notification (with sound when enabled).
     * Call from ViewModel before stopTracking when session is saved.
     */
    fun showSessionCompleteNotification(stats: SessionStats) {
        val soundsEnabled = userPrefsManager.getNotificationSoundsEnabled()
        val channelId = "session_summary_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Session Summary",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notification when a tracking session is saved"
                setShowBadge(true)
                enableVibration(soundsEnabled)
                setSound(if (soundsEnabled) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val metersPerUnit = userPrefsManager.getMetersPerUnit()
        val unitLabel = userPrefsManager.getDistanceUnitLabel()
        val distanceStr = String.format("%.2f %s", stats.totalDistance / metersPerUnit, unitLabel)
        val durationMin = stats.totalDuration / 60
        val title = "Session saved"
        val body = "$distanceStr in ${durationMin} min • ${stats.caloriesBurned.toInt()} kcal"

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon = getAppIconBitmap()
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .apply { largeIcon?.let { setLargeIcon(it) } }
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (!soundsEnabled) {
            builder.setSilent(true)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SESSION_SUMMARY_NOTIFICATION_ID, builder.build())
    }

    /**
     * Brief, auto-dismissing notification posted after the user taps **Discard**
     * on the foreground notification. Lives on its own low-importance channel
     * so it never makes noise / vibrates, and clears itself after a few seconds
     * so the shade isn't cluttered with stale "you discarded a session"
     * receipts.
     */
    private fun showDiscardConfirmationNotification() {
        val channelId = "tracking_actions_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Tracking actions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Confirms that a tracking session was discarded"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val openAppPi = PendingIntent.getActivity(
            this, REQ_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Session discarded")
            .setContentText("Tracking stopped without saving.")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPi)
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(DISCARD_NOTIFICATION_TIMEOUT_MS)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DISCARD_CONFIRMATION_NOTIFICATION_ID, builder.build())
    }

    /**
     * Get app icon as Bitmap for notification large icon.
     * Handles adaptive icons (API 26+) by drawing the drawable to a bitmap.
     */
    private fun getAppIconBitmap(): Bitmap? {
        val size = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height).coerceAtLeast(96)
        val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        const val NOTIFICATION_ID = 101
        const val SESSION_SUMMARY_NOTIFICATION_ID = 103
        /** ID for the brief "Session discarded" toast-style notification. */
        const val DISCARD_CONFIRMATION_NOTIFICATION_ID = 104

        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"
        /** Foreground-notification action: save the in-flight session and stop. */
        const val ACTION_STOP_AND_SAVE = "ACTION_STOP_AND_SAVE"
        /** Foreground-notification action: stop tracking and throw the session
         *  away (used when the auto-start step counter triggers unintentionally). */
        const val ACTION_DISCARD = "ACTION_DISCARD"
        /** Manual-mode mismatch quick-switch: pin the carried [ActivityType]
         *  via [setManualActivityMode]. Carried in [EXTRA_TARGET_ACTIVITY]. */
        const val ACTION_MANUAL_SWITCH = "ACTION_MANUAL_SWITCH"
        /** Manual-mode mismatch dismissal: keep the user's pinned activity
         *  and start the cooldown so we don't re-prompt immediately. */
        const val ACTION_MANUAL_KEEP = "ACTION_MANUAL_KEEP"
        /** Extra carrying an [ActivityType.name] for [ACTION_MANUAL_SWITCH]. */
        const val EXTRA_TARGET_ACTIVITY = "extra_target_activity"

        /** PendingIntent request codes — kept distinct so flag updates on one
         *  action don't accidentally clobber another. */
        private const val REQ_OPEN_APP = 0
        private const val REQ_STOP_SAVE = 401
        private const val REQ_DISCARD = 402
        /** Activity-confirm body tap + "Confirm in app" action → opens the
         *  in-app activity selector. Distinct from [REQ_OPEN_APP] because
         *  the activity-confirm flow carries a deep-link extra and we don't
         *  want FLAG_UPDATE_CURRENT to overwrite the bare-open intent used
         *  by other notifications. */
        private const val REQ_CONFIRM_OPEN_APP = 403
        /** Activity-confirm "Keep auto" action → dismisses without changes. */
        private const val REQ_CONFIRM_KEEP = 404
        /** Manual-mode mismatch "Switch to <auto>" quick action. */
        private const val REQ_MANUAL_SWITCH = 405
        /** Manual-mode mismatch "Keep <manual>" quick action. */
        private const val REQ_MANUAL_KEEP = 406

        /** Channel ID for the persistent foreground tracking notification.
         *
         *  v2 bump: the original `tracking_channel` was created with
         *  IMPORTANCE_LOW whenever the user disabled notification sounds,
         *  which makes Android render the shade entry in a compact form
         *  that hides the action row until the user expands it. Existing
         *  channels can't have their importance raised programmatically,
         *  so the only way to give every user a default-importance shade
         *  entry (Save / Discard visible inline) is to migrate to a fresh
         *  channel ID. */
        private const val TRACKING_CHANNEL_ID = "tracking_channel_v2"

        /** Auto-dismiss the "Session discarded" notification after ~4 s. */
        private const val DISCARD_NOTIFICATION_TIMEOUT_MS = 4_000L

        /** ~15–20% typical overcount from summed GPS chords vs smooth path; tune in code if needed. */
        private const val GPS_PATH_DISTANCE_SCALE = 0.83

        /** Sustained below [SpeedThresholds.DRIVING_MIN] before allowing switch to walk/run/idle (ms). */
        private const val MOTOR_LOW_SPEED_EXIT_MS = 300_000L

        /** After this long without speed in the driving band, [MOTOR_LOW_SPEED_EXIT_MS] countdown applies. */
        private const val STICKY_RECENT_DRIVING_MS = 120_000L

        /** Delay after auto-start before the "What are you doing?" confirm notification fires. */
        private const val ACTIVITY_CONFIRM_DELAY_MS = 30_000L

        /**
         * How long the auto-classifier must keep disagreeing with the manual
         * activity choice before we prompt the user with a quick-switch
         * notification. Short enough to react when someone forgets they
         * picked "Walking" and got into a car; long enough to ride through
         * brief deviations (a parking-lot walk before the engine starts).
         */
        private const val MANUAL_MISMATCH_PROMPT_DELAY_MS = 30_000L

        /**
         * Cooldown after a "Keep <manual>" dismissal (or after we just
         * prompted) before the manual-mode mismatch notification can fire
         * again — keeps the shade quiet if the user really did mean their
         * pinned choice.
         */
        private const val MANUAL_MISMATCH_COOLDOWN_MS = 15 * 60_000L

        /**
         * Standalone notification id for the manual-mode mismatch prompt.
         * Distinct from [ActivityConfirmReceiver.CONFIRM_NOTIFICATION_ID] so
         * the two flows can coexist without one cancelling the other.
         */
        const val MANUAL_MISMATCH_NOTIFICATION_ID = 4002

        /**
         * How long high speed must persist before we let a pedestrian activity
         * promote to a motor mode (DRIVING/EV/TRAIN/FLYING). Long enough to
         * filter typical GPS multipath spikes (1–2 ticks ≈ 1–2 s in urban
         * canyons), short enough not to delay legitimate vehicle starts —
         * a real car ride sustains driving-band speed for far longer than 4 s.
         */
        private const val PEDESTRIAN_TO_MOTOR_PROMOTE_MS = 4_000L

        /**
         * If the step counter ticked within this window, treat the user as
         * actively on foot for the purposes of [applyPedestrianGpsSanityCap],
         * regardless of long-term session cadence. Catches the first urban-
         * canyon GPS spike before the 10-step warm-up of the previous logic
         * would have allowed any cap to take effect.
         */
        private const val PEDESTRIAN_RECENT_STEP_WINDOW_MS = 5_000L

        /**
         * Minimum session-step count before the cadence-based pedestrian cap
         * is allowed to kick in via the session-SPM path. Recent-step
         * detection has its own faster path; this is just the floor for the
         * "long-term cadence" branch.
         */
        private const val PEDESTRIAN_MIN_STEPS_FOR_CAP = 4
    }

    // ── Activity-confirm notification ─────────────────────────────────────────

    /**
     * Posts the "What are you doing?" notification a short while after auto-start.
     *
     * UX contract (intentional minimalism):
     *  • Body tap            → opens the app, lands on the Tracker tab, and pops
     *                          the full in-app activity selector. This is the
     *                          primary path because the in-app selector shows
     *                          richer labels/icons than a notification action row
     *                          and lets the user reconsider without time pressure.
     *  • "Confirm in app"    → identical to the body tap. Provided as an explicit
     *                          action button for users who don't realise the body
     *                          itself is tappable on their launcher / OEM skin.
     *  • "Keep auto"         → dismisses without changing anything; tracking
     *                          continues in auto-detect mode.
     *
     * The deep link is delivered via [MainActivity.EXTRA_OPEN_ACTIVITY_SELECTOR],
     * which MainActivity consumes in onNewIntent / onCreate and routes through a
     * Compose LaunchedEffect.
     */
    private fun showActivityConfirmNotification() {
        val channelId = "activity_confirm_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Activity Confirmation",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Asks which activity you are doing after auto-start"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT

        // Body tap + "Confirm in app" both target MainActivity with the
        // selector deep-link extra. SINGLE_TOP combined with the manifest
        // launchMode lets MainActivity reuse its existing instance and route
        // the request through onNewIntent.
        val openSelectorIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_ACTIVITY_SELECTOR, true)
        }
        val openSelectorPi = android.app.PendingIntent.getActivity(
            this, REQ_CONFIRM_OPEN_APP, openSelectorIntent, flags
        )
        val confirmAction = androidx.core.app.NotificationCompat.Action.Builder(
            0, "Confirm in app", openSelectorPi
        ).build()

        // Quick dismiss: ActivityConfirmReceiver already handles the "KEEP"
        // sentinel — leaves manual-mode unchanged and cancels the notification.
        val keepIntent = Intent(this, ActivityConfirmReceiver::class.java).apply {
            action = ActivityConfirmReceiver.ACTION_CONFIRM_ACTIVITY
            putExtra(ActivityConfirmReceiver.EXTRA_ACTIVITY_TYPE, "KEEP")
        }
        val keepPi = android.app.PendingIntent.getBroadcast(this, REQ_CONFIRM_KEEP, keepIntent, flags)
        val keepAction = androidx.core.app.NotificationCompat.Action.Builder(0, "Keep auto", keepPi).build()

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("What are you doing?")
            .setContentText("Tap to choose your activity in the app")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openSelectorPi)
            .setAutoCancel(true)
            .addAction(confirmAction)
            .addAction(keepAction)

        nm.notify(ActivityConfirmReceiver.CONFIRM_NOTIFICATION_ID, builder.build())
    }

    /**
     * Run the auto-classifier as if no manual override were set, so we can
     * compare against the pinned activity in [evaluateManualOverrideMismatch].
     *
     * Mirrors the speed-gate envelope of the non-manual branch in
     * [updateLocation] (IDLE / DRIVING band) but skips the sticky-motor and
     * promotion-latency filters: the 30 s persistence window in
     * [evaluateManualOverrideMismatch] already serves the same anti-spike
     * purpose, and re-running them here would just add hysteresis we'd have
     * to reason about twice.
     */
    private fun computeShadowAutoActivity(
        speed: Float,
        currentAltitude: Double,
        previousAltitude: Double,
        motionPattern: MotionPattern,
        hasAccelerometer: Boolean,
        @Suppress("UNUSED_PARAMETER") now: Long
    ): ActivityType {
        val raw = locationService.classifyActivityWithAltitude(
            speed,
            currentAltitude,
            previousAltitude,
            motionPattern,
            hasAccelerometer
        )
        return when {
            speed < SpeedThresholds.IDLE_SPEED_MAX -> ActivityType.IDLE
            speed >= SpeedThresholds.DRIVING_MIN -> {
                if (raw == ActivityType.FLYING) ActivityType.FLYING else ActivityType.DRIVING
            }
            else -> raw
        }
    }

    /**
     * State machine that decides whether to prompt the user about a sustained
     * disagreement between [shadow] (what the auto-classifier thinks) and
     * [manual] (what they pinned via [setManualActivityMode]). Called once per
     * location tick from [updateLocation] while a manual override is active.
     *
     * Rules:
     *  - Same activity                → reset, no prompt.
     *  - Shadow == IDLE while pinned to a moving activity → reset.
     *    Walking/driving naturally have stationary moments (red lights,
     *    breaks, phone-checking pauses) that we don't want to flag.
     *  - Within [MANUAL_MISMATCH_COOLDOWN_MS] of the last prompt/dismissal
     *    → no-op.
     *  - Shadow flipped to a new candidate → restart the timer for it.
     *  - Same candidate has held for [MANUAL_MISMATCH_PROMPT_DELAY_MS]
     *    → post the quick-switch notification and start the cooldown.
     */
    private fun evaluateManualOverrideMismatch(
        shadow: ActivityType,
        manual: ActivityType,
        now: Long
    ) {
        if (shadow == manual || shadow == ActivityType.IDLE) {
            manualMismatchCandidate = null
            manualMismatchSinceMs = 0L
            return
        }
        if (manualMismatchLastPromptMs != 0L &&
            now - manualMismatchLastPromptMs < MANUAL_MISMATCH_COOLDOWN_MS) {
            return
        }
        if (manualMismatchCandidate != shadow) {
            manualMismatchCandidate = shadow
            manualMismatchSinceMs = now
            return
        }
        if (now - manualMismatchSinceMs >= MANUAL_MISMATCH_PROMPT_DELAY_MS) {
            showManualOverrideConfirmNotification(autoActivity = shadow, manualActivity = manual)
            manualMismatchLastPromptMs = now
            manualMismatchCandidate = null
            manualMismatchSinceMs = 0L
        }
    }

    /**
     * Quick-switch notification for the "user is in manual mode but the auto-
     * classifier disagrees" case. Two inline actions:
     *  - **Switch to <auto>** → calls [setManualActivityMode] for the
     *    detected activity via [ACTION_MANUAL_SWITCH] on this service.
     *  - **Keep <manual>** → leaves the manual pin intact, starts the
     *    cooldown via [ACTION_MANUAL_KEEP].
     *
     * Body tap falls back to the in-app activity selector — same deep link
     * as [showActivityConfirmNotification] — so users who want to pick a
     * non-detected activity (e.g. correcting Driving → Cycling) have a
     * one-tap path that doesn't require dismissing the notification first.
     */
    private fun showManualOverrideConfirmNotification(
        autoActivity: ActivityType,
        manualActivity: ActivityType
    ) {
        val channelId = "activity_change_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Activity Change Detection",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Asks if you've switched activity while in manual mode"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val autoLabel = activityDisplayName(autoActivity)
        val manualLabel = activityDisplayName(manualActivity)

        val openSelectorIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_ACTIVITY_SELECTOR, true)
        }
        val openSelectorPi = PendingIntent.getActivity(
            this, REQ_CONFIRM_OPEN_APP, openSelectorIntent, flags
        )

        val switchIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_MANUAL_SWITCH
            putExtra(EXTRA_TARGET_ACTIVITY, autoActivity.name)
        }
        val switchPi = PendingIntent.getService(this, REQ_MANUAL_SWITCH, switchIntent, flags)
        val switchAction = NotificationCompat.Action.Builder(
            0, "Switch to $autoLabel", switchPi
        ).build()

        val keepIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_MANUAL_KEEP
        }
        val keepPi = PendingIntent.getService(this, REQ_MANUAL_KEEP, keepIntent, flags)
        val keepAction = NotificationCompat.Action.Builder(
            0, "Keep $manualLabel", keepPi
        ).build()

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Activity changed?")
            .setContentText("Looks like you're ${autoLabel.lowercase()} — switch from ${manualLabel.lowercase()}?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openSelectorPi)
            .setAutoCancel(true)
            .addAction(switchAction)
            .addAction(keepAction)

        nm.notify(MANUAL_MISMATCH_NOTIFICATION_ID, builder.build())
    }

    /** Cancel any in-flight manual-mode mismatch prompt — called when the
     *  user resolves the question (via "Switch", "Keep", a fresh manual
     *  selection, or stop tracking). */
    private fun dismissManualMismatchNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)
            ?.cancel(MANUAL_MISMATCH_NOTIFICATION_ID)
    }

    private fun activityDisplayName(t: ActivityType): String = when (t) {
        ActivityType.IDLE -> "Idle"
        ActivityType.WALKING -> "Walking"
        ActivityType.RUNNING -> "Running"
        ActivityType.CYCLING -> "Cycling"
        ActivityType.MOTORCYCLE -> "Motorcycle"
        ActivityType.TRAIN -> "Train"
        ActivityType.DRIVING -> "Driving"
        ActivityType.ELECTRIC_VEHICLE -> "EV"
        ActivityType.FLYING -> "Flying"
    }
}
