package Kinetic_Eco.Tracker.services

import Kinetic_Eco.Tracker.data.SessionPlausibility
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
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.firebase.auth.FirebaseAuth
import Kinetic_Eco.Tracker.KineticEcoApplication
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.filters.KalmanFilter
import Kinetic_Eco.Tracker.filters.FlightTracker
import Kinetic_Eco.Tracker.filters.AltitudeFilter
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.util.adjustStatsForSimplifiedPath
import kotlin.math.min

class TrackingService : LifecycleService() {
    private lateinit var locationService: LocationService
    private lateinit var sensorService: SensorService
    private lateinit var sessionManager: SessionManager
    private lateinit var userPrefsManager: UserPreferencesManager
    /** Ellipsoidal→MSL altitude correction (no-op until the EGM96 grid asset is present). */
    private lateinit var geoidService: GeoidService
    private var userPhysicalProfile: UserPhysicalProfile? = null
    private var vehicleProfile: VehicleProfile = VehicleProfile.DEFAULT
    
    // Advanced filtering and flight tracking
    private val kalmanFilter = KalmanFilter()
    private val flightTracker = FlightTracker()
    /** Spike-rejecting, smoothed altitude used for session summary, route elevation, and gain/loss. */
    private val altitudeFilter = AltitudeFilter()

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

    // Manual-activity mismatch nudge: when sensors disagree with the pinned mode
    private var manualMismatchSinceMs: Long = 0L
    /** 0 = never sent, -1 = user dismissed, >0 = epoch when notification was posted. */
    private var manualMismatchNotifiedAtMs: Long = 0L
    private var manualMismatchSuggestedActivity: ActivityType? = null

    private var lastPosition: GeoPosition? = null
    private var previousAltitude: Double = 0.0
    /** Previous *smoothed* altitude, used for elevation gain/loss with a deadband (noise-free). */
    private var previousSmoothedAltitude: Double? = null
    private var lastUpdateTime: Long = 0
    private var smoothedSpeed: Float = 0f
    /** Rolling (timestampMs, cumulative step count) samples for instantaneous walking cadence. */
    private val cadenceSamples = ArrayDeque<Pair<Long, Int>>()
    // Counts consecutive ticks where the new reading was rejected as an outlier vs.
    // smoothedSpeed. A single spike is fully damped, but if the EMA itself is the
    // poisoned value (e.g. inflated during an indoor multipath stretch), this lets
    // it step back toward reality instead of being permanently stuck.
    private var consecutiveSpeedOutliers = 0
    // Ring buffer of raw GPS speeds (before EMA) used by the consistency gate in
    // applyPedestrianToMotorPromotionLatency to detect indoor GPS bounce.
    private val speedSampleBuf = ArrayDeque<Float>()
    // Set to System.currentTimeMillis() when tracking is cold-started via IN_VEHICLE
    // transition; allows faster motor promotion without the pedestrian-seed requirement.
    private var coldStartVehicleMs = 0L
    /** Manages the session-scoped Activity Recognition subscription (see [ActivityTransitionManager]). */
    private var sessionActivityManager: ActivityTransitionManager? = null
    /**
     * Wall-clock time of the last confident IN_VEHICLE reading from Activity Recognition.
     *
     * Independent of both GPS and the accelerometer heuristics, so it can break a tie when
     * those two agree with each other but disagree with reality — the exact failure that let a
     * moving car be classified IDLE. Read via [isRecentVehicleRecognition].
     */
    @Volatile private var lastVehicleRecognitionMs = 0L
    /** Whether we've already shown the EV-confirm prompt this session. */
    private var evConfirmPromptShownThisSession = false
    /** Consecutive GPS fix count with accuracy ≤ GPS_MOTOR_ACCURACY_GATE_M (requires 2 before motor promotion). */
    private var consecutiveCleanGpsReadings = 0
    /** Rolling accuracy readings used to detect rapid outdoor→indoor degradation. */
    private val accuracyHistory = ArrayDeque<Float>()
    /** Non-zero while an outdoor→indoor GPS transition is active; cleared after [INDOOR_TRANSITION_TTL_MS]. */
    private var indoorTransitionDetectedMs = 0L
    /** Bearing (degrees, 0–360) of the last accepted GPS displacement vector. */
    private var lastBearingDeg: Float? = null
    /** Count of consecutive heading flips (>45 °) — high counts indicate GPS drift, not real movement. */
    private var headingJitterCount = 0
    /** Raw GPS speed of the previous fix — used to detect a frozen/locked speed value. */
    private var lastRawGpsSpeed = Float.NaN
    /** How many consecutive fixes have returned the exact same raw GPS speed. */
    private var consecutiveIdenticalGpsSpeeds = 0
    /** Timestamp (ms) when sensors first confirmed STILL; 0 if not currently still. */
    private var stillConfirmedSinceMs = 0L
    /** Timestamp (ms) when sensors first confirmed near-zero motion while GPS reported speed; 0 if not active. */
    private var idleSensorLockSinceMs = 0L
    /** Anchor lat/lon for GPS-hover detection — the fix that opened the current "parked" window. */
    private var hoverAnchorLat: Double? = null
    private var hoverAnchorLon: Double? = null
    /** Timestamp (ms) the hover anchor was set. */
    private var hoverAnchorSinceMs: Long = 0L
    /** Consecutive fixes at or worse than [INDOOR_ACCURACY_DEGRADED_M] — arms indoor mode with no good→bad edge. */
    private var poorAccuracyStreak = 0
    /** Recent filtered positions (ms, lat, lon) backing the poor-accuracy displacement cross-check. */
    private val displacementWindow = ArrayDeque<Triple<Long, Double, Double>>()

    private val _evConfirmPrompt = MutableStateFlow(false)
    val evConfirmPrompt: StateFlow<Boolean> = _evConfirmPrompt.asStateFlow()

    private val activityHistory = mutableListOf<ActivityType>()
    private val ACTIVITY_HISTORY_SIZE = 6
    
    // Activity persistence tracking - how long current activity has been ongoing
    private var activityStartTime: Long = System.currentTimeMillis()
    private var activityDurationSeconds: Long = 0

    // Session start time - when user clicked Start (used for correct session date)
    private var sessionStartTimeMs: Long = 0

    /** Disk snapshot of the in-flight session, so a kill mid-trip is recoverable. */
    private val checkpointStore by lazy { SessionCheckpointStore(applicationContext) }
    /** Timer ticks since the last snapshot; see [SessionCheckpointStore.CHECKPOINT_INTERVAL_SECONDS]. */
    private var ticksSinceCheckpoint = 0

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

    /** Seconds after tracking start where we use relaxed debounce (requiredConsistency=1) for faster convergence */
    private val STARTUP_DEBOUNCE_SECONDS = 10

    // Auto-stop on idle: when we transitioned to IDLE
    private var idleStartTimeMs: Long = 0

    // ── Travel-based idle detection ──────────────────────────────────────────
    //
    // The idle auto-stop used to key on `_currentActivity == IDLE`, so any single non-IDLE
    // tick reset the timer. That made it defeatable by anything that fakes an activity label —
    // GPS drift reclassified by speed, or step events from a phone being shaken or jostled —
    // and a session could then run indefinitely banking phantom distance. Net displacement is
    // the one thing none of those can fake, so the timer now runs on ground covered.
    /** Anchor position for the current no-travel window; null until the first fix. */
    private var idleAnchorLat: Double? = null
    private var idleAnchorLon: Double? = null
    /** When the device was last found to have travelled meaningfully. 0 = not yet established. */
    private var noTravelSinceMs: Long = 0L
    /** Recent per-poll displacements from the idle anchor; travel must be sustained across it. */
    private val idleTravelRing = ArrayDeque<Double>()

    // ── Step corroboration ───────────────────────────────────────────────────
    //
    // A hardware pedometer counts oscillation, not travel: shaking or jostling a phone
    // produces step events indistinguishable from walking. Left uncorroborated those steps
    // suppress the sensor idle lock and mint step-based distance. Real gait moves the device
    // over the ground; shaking does not, so each window of steps is checked against the
    // displacement it should have produced.
    private var stepCorrobLat: Double? = null
    private var stepCorrobLon: Double? = null
    private var stepCorrobAnchorSteps: Int = 0
    private var stepCorrobSinceMs: Long = 0L
    /** False once a window of steps has produced far too little ground movement to be gait. */
    @Volatile private var stepsCorroborated: Boolean = true

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
    
    // Top speed (raw GPS max) for session summary
    private var sessionTopSpeedMps = 0.0

    // Multi-mode segment tracking
    private val segmentList = mutableListOf<ActivitySegment>()
    private var segmentActivity: ActivityType = ActivityType.IDLE
    private var segmentStartTimeMs = 0L
    private var segmentStartDistanceM = 0.0

    // Route path for map display - record points when tracking
    private val pathPoints = mutableListOf<RoutePoint>()
    private var lastPathRecordTime: Long = 0
    private val PATH_RECORD_INTERVAL_MS = 1500L // Record every ~1.5s; ensures route shows even when stationary or slow (autodetect)

    /** Same activity as route segment colors (post GPS refinement). Step/dead-reck stats must not use debounced [_currentActivity] alone. */
    private var lastRefinedActivity: ActivityType = ActivityType.IDLE

    // Accelerometer samples for Firestore analytics (1 sample/sec during tracking)
    private val accelerometerSamples = mutableListOf<AccelerometerSample>()
    private var lastAccelSampleTimeMs: Long = 0
    private val ACCEL_SAMPLE_INTERVAL_MS = 1000L
    
    // Speed validation constants (in m/s) - Enhanced for flying
    //
    // These express *physical plausibility* for an activity, and are applied to the
    // preliminary classification — which is derived from the very speed being validated and
    // is therefore sometimes wrong. A cap below the threshold needed to leave its own state
    // is a one-way trap: IDLE was capped at 0.5 m/s, well under DRIVING_MIN (5.0), so a
    // vehicle misclassified as IDLE for even one tick had its speed clamped into the idle
    // band and could never climb back out. IDLE now carries the same ground-vehicle ceiling
    // as DRIVING: nothing about a stationary *verdict* makes 30 km/h physically impossible,
    // and genuine idle drift is already handled by the STILL, hover, jitter, displacement and
    // sensor-idle-lock gates downstream.
    private val MAX_REALISTIC_SPEEDS = mapOf(
        ActivityType.IDLE to 50.0f,             // plausibility only — see note above
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
    /** Outliers rejected this many ticks in a row are treated as a poisoned EMA baseline
     *  (not a one-off spike) and the EMA steps toward the new reading instead of freezing. */
    private val SPEED_OUTLIER_RECOVERY_TICKS = 2

    // Fallback thresholds: use alt sources when GPS speed is unreliable
    private val GPS_SPEED_UNRELIABLE_MAX = 0.5f  // m/s - treat as unreliable below this
    private val POOR_ACCURACY_METERS = 80f       // Use Kalman/fallback when accuracy worse
    /** GPS accuracy above which motor-mode promotion is blocked unless sensors confirm a vehicle (SMOOTH pattern). */
    private val GPS_MOTOR_ACCURACY_GATE_M = 30f
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
     * Doppler speed below which a sub-accuracy position step is treated as noise rather than
     * travel. Set under walking pace on purpose: anything genuinely moving faster than this
     * reports it, and anything slower is walking, which the pedometer veto already covers.
     */
    private val GPS_NOISE_SPEED_FLOOR_MPS = 1.0f
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
        geoidService = GeoidService(this)
        // Pre-load the geoid grid off the main thread so the first altitude fix doesn't pay the
        // ~2 MB asset read (and so a missing grid is a one-time no-op, not a per-fix check).
        lifecycleScope.launch(Dispatchers.IO) { geoidService.ensureLoaded() }
        
        // Load user's physical profile for accurate calorie calculations
        userPhysicalProfile = userPrefsManager.loadPhysicalProfile()
        android.util.Log.d("TrackingService", "Physical profile loaded: $userPhysicalProfile")
        vehicleProfile = userPrefsManager.loadVehicleProfile()
        android.util.Log.d("TrackingService", "Vehicle profile loaded: $vehicleProfile")

        startTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                if (intent.getBooleanExtra(EXTRA_COLD_START_VEHICLE, false)) {
                    coldStartVehicleMs = System.currentTimeMillis()
                    android.util.Log.d("TrackingService", "Cold-start vehicle flag set")
                }
                startTracking()
            }
            ACTION_STOP_TRACKING -> stopTracking()
            ACTION_STOP_AND_SAVE -> stopAndSaveFromNotification()
            ACTION_DISCARD -> discardFromNotification()
            ACTION_PROCESS_ACTIVITY_RESULT -> handleSessionActivityResult(intent)
            ACTION_SWITCH_TO_AUTO_DETECT -> {
                setManualActivityMode(null)
                cancelActivityHintNotification()
            }
            ACTION_DISMISS_ACTIVITY_HINT -> {
                cancelActivityHintNotification()
                // -1 = dismissed; auto-switch timer still runs toward 2-minute mark
                manualMismatchNotifiedAtMs = -1L
            }
            // START_STICKY restart after the process was killed. Every accumulator
            // is back at zero here, so without this the trip in progress is simply
            // gone — no crash, no log, nothing for the user to notice until they
            // look for a session that never appeared.
            null -> resumeFromCheckpointIfAny()
        }
        return Service.START_STICKY
    }

    /**
     * Recover an in-flight session after a sticky restart.
     *
     * A recent snapshot is resumed — tracking continues with the banked distance,
     * route and segments intact, losing only whatever accrued since the last
     * write. An older one is treated as an abandoned trip: the user has long
     * since walked away, so resuming would splice two unrelated journeys
     * together. Those are handed to [SessionRecovery] to be saved as a completed
     * session instead of resumed.
     */
    private fun resumeFromCheckpointIfAny() {
        if (_isTracking.value) return
        lifecycleScope.launch {
            val checkpoint = checkpointStore.read() ?: return@launch
            val age = System.currentTimeMillis() - checkpoint.savedAtMs
            if (age > SessionCheckpointStore.RESUME_MAX_AGE_MS) {
                android.util.Log.w(
                    "TrackingService",
                    "Checkpoint is ${age / 60_000}min old — salvaging as a finished session rather than resuming"
                )
                SessionRecovery.salvage(applicationContext)
                return@launch
            }
            restoreFromCheckpoint(checkpoint)
            startTracking(resuming = true)
        }
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
        val segments = getFinalSegments()
        val adjustedStats = adjustStatsForSimplifiedPath(rawStats, routePath)
        val statsWithRoute = adjustedStats.copy(routePath = routePath, segments = segments)
        val app = applicationContext as KineticEcoApplication

        // Judged on the stats that carry the route, not the raw ones — the drift rule needs
        // geometry, and rawStats has none.
        SessionPlausibility.reasonToDiscard(statsWithRoute)?.let { reason ->
            android.util.Log.d(
                "TrackingService",
                "Stop&save from notification: discarding session ($reason, " +
                    "${statsWithRoute.totalDistance}m path)"
            )
            stopTracking()
            return
        }

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

    /**
     * @param resuming true when continuing a session recovered from a checkpoint
     *   after the service was killed. Detector state is still cold-started — that
     *   is wanted — but the session accumulators (start time, route, segments,
     *   altitudes, top speed) are left alone instead of being zeroed, because
     *   [restoreFromCheckpoint] has just populated them.
     */
    private fun startTracking(resuming: Boolean = false) {
        if (_isTracking.value) return
        userPrefsManager.setPendingResumeAfterIdleAutoStop(false)

        // Cancel any previous collectors to prevent pedometer carry-over between sessions
        locationCollectJob?.cancel()
        sensorCollectJob?.cancel()
        sensorWarmUpJob?.cancel()
        idleCheckJob?.cancel()
        deadReckoningJob?.cancel()
        locationCollectJob = null
        sensorCollectJob = null
        sensorWarmUpJob = null
        idleCheckJob = null
        deadReckoningJob = null
        idleStartTimeMs = 0
        idleAnchorLat = null
        idleAnchorLon = null
        idleTravelRing.clear()
        noTravelSinceMs = 0L
        stepCorrobLat = null
        stepCorrobLon = null
        stepCorrobAnchorSteps = 0
        stepCorrobSinceMs = 0L
        stepsCorroborated = true
        motorLowSpeedSinceMs = 0L
        lastDrivingBandMs = 0L
        pedestrianToMotorRiseSinceMs = 0L
        speedSampleBuf.clear()
        evConfirmPromptShownThisSession = false
        consecutiveCleanGpsReadings = 0
        lastBearingDeg = null
        headingJitterCount = 0
        lastRawGpsSpeed = Float.NaN
        consecutiveIdenticalGpsSpeeds = 0
        stillConfirmedSinceMs = 0L
        idleSensorLockSinceMs = 0L
        hoverAnchorLat = null
        hoverAnchorLon = null
        hoverAnchorSinceMs = 0L
        // coldStartVehicleMs is intentionally NOT reset here — it is set in onStartCommand
        // before startTracking() is called, so clearing it here would erase the flag.
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
        manualMismatchSinceMs = 0L
        manualMismatchNotifiedAtMs = 0L
        manualMismatchSuggestedActivity = null
        cancelActivityHintNotification()
        _currentActivity.value = ActivityType.IDLE
        locationService.setFlyingMode(false)
        _isTracking.value = true

        // Call startForeground immediately after marking active — avoids FGS timeout if later init is slow.
        // Guarded: on Android 12+ this throws ForegroundServiceStartNotAllowedException when started from
        // the background, and on Android 14+ it throws SecurityException/MissingForegroundServiceType when
        // location permission isn't held. Neither is recoverable here — abort the start cleanly instead of
        // letting the process crash (the failure is reported as a non-fatal so we can see it in Crashlytics).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notification = createNotification()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } catch (e: Exception) {
                android.util.Log.e("TrackingService", "startForeground failed: ${e.javaClass.simpleName}", e)
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e)
                _isTracking.value = false
                isActivelyTracking = false
                stopSelf()
                return
            }
        }
        isActivelyTracking = true

        lastUpdateTime = System.currentTimeMillis()
        lastPosition = null
        previousAltitude = 0.0
        smoothedSpeed = 0f
        consecutiveSpeedOutliers = 0
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
        altitudeFilter.reset()
        previousSmoothedAltitude = null
        cadenceSamples.clear()
        sensorService.resetBarometerCalibration()
        
        // Reset activity persistence tracking
        activityStartTime = System.currentTimeMillis()
        activityDurationSeconds = 0

        lastPathRecordTime = 0
        lastRefinedActivity = ActivityType.IDLE
        if (!resuming) {
            // Record session start for correct date when saving (e.g. session started at 11pm, saved at 12am next day)
            sessionStartTimeMs = System.currentTimeMillis()

            sessionTopSpeedMps = 0.0
            pathPoints.clear()
            segmentList.clear()
            segmentActivity = ActivityType.IDLE
            segmentStartTimeMs = System.currentTimeMillis()
            segmentStartDistanceM = 0.0
            startingAltitude = null
            stoppingAltitude = null
            minAltitude = null
            maxAltitude = null
        }
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

        // Independent vehicle evidence for the duration of the session — see
        // ActivityTransitionManager.registerSessionActivityUpdates.
        lastVehicleRecognitionMs = 0L
        sessionActivityManager = ActivityTransitionManager(this).also {
            it.registerSessionActivityUpdates()
        }

        // Auto-stop on idle: poll often enough that we do not overshoot the user's timeout by a full minute
        idleCheckJob = lifecycleScope.launch {
            while (isActive && _isTracking.value) {
                delay(15_000)
                if (!_isTracking.value) break
                // Idle is judged by ground covered, not by the activity label. A label can be
                // faked by drift reclassified on speed, or by step events from a shaken phone;
                // displacement from the anchor cannot. Anything that genuinely travels clears
                // IDLE_TRAVEL_RADIUS_M within one poll interval many times over.
                val pos = lastPosition
                val nowMs = System.currentTimeMillis()
                val anchorLat = idleAnchorLat
                val anchorLon = idleAnchorLon

                if (pos == null) {
                    // No fix yet — nothing to judge. Don't start counting until we can.
                    noTravelSinceMs = 0L
                } else if (anchorLat == null || anchorLon == null) {
                    idleAnchorLat = pos.latitude
                    idleAnchorLon = pos.longitude
                    noTravelSinceMs = nowMs
                } else {
                    val movedM = locationService.calculateDistance(
                        anchorLat, anchorLon, pos.latitude, pos.longitude
                    )
                    // The radius scales with the fix's own error, because a 40 m "excursion"
                    // measured on a 45 m fix is noise, not travel. Indoors this device drifts
                    // hundreds of metres from a fixed point, so a flat radius re-anchored the
                    // clock constantly and the auto-stop never fired.
                    val accM = pos.accuracy.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 20.0
                    val travelThreshold = maxOf(
                        IDLE_TRAVEL_RADIUS_M, accM * IDLE_TRAVEL_ACCURACY_FACTOR
                    )
                    // Drift oscillates around a point; travel keeps going. Requiring the device
                    // to stay beyond the radius for consecutive polls separates the two — a
                    // single excursion that comes straight back is not travel.
                    idleTravelRing.addLast(movedM)
                    while (idleTravelRing.size > IDLE_TRAVEL_CONFIRM_POLLS) idleTravelRing.removeFirst()
                    val sustainedTravel = idleTravelRing.size >= IDLE_TRAVEL_CONFIRM_POLLS &&
                        idleTravelRing.min() >= travelThreshold

                    // Debug-only: this fires every 15 s for the whole session, which is far too
                    // chatty for production, and R8 is not configured to strip Log.d.
                    if (Kinetic_Eco.Tracker.BuildConfig.DEBUG) android.util.Log.d("TrackingService",
                        "Idle poll: ${movedM.toInt()}m from anchor (need ${travelThreshold.toInt()}m " +
                            "x${IDLE_TRAVEL_CONFIRM_POLLS}, acc=${accM.toInt()}m), " +
                            "noTravelFor=${if (noTravelSinceMs == 0L) 0 else (nowMs - noTravelSinceMs) / 1000}s, " +
                            "activity=${_currentActivity.value}")

                    if (sustainedTravel) {
                        // Real travel: re-anchor here and restart the clock.
                        idleAnchorLat = pos.latitude
                        idleAnchorLon = pos.longitude
                        idleTravelRing.clear()
                        noTravelSinceMs = nowMs
                        idleStartTimeMs = 0
                    } else {
                        if (noTravelSinceMs == 0L) noTravelSinceMs = nowMs
                        idleStartTimeMs = noTravelSinceMs
                        val idleMinutes = userPrefsManager.getIdleStopMinutes()
                        val elapsedMs = nowMs - noTravelSinceMs
                        if (elapsedMs >= idleMinutes * 60 * 1000L) {
                            android.util.Log.d("TrackingService",
                                "Auto-stop: no travel for $idleMinutes minutes " +
                                    "(${movedM.toInt()}m from anchor, activity=${_currentActivity.value})")
                            performAutoStopAndSave()
                            break
                        }
                    }
                }
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
                val distanceDelta = scaledGpsDistanceMeters(smoothedSpeed * deltaSec, activity)
                _sessionDistance.value += distanceDelta
                updateStats(distanceDelta, activity, smoothedSpeed.toDouble(), 0.0, 0.0)
            }
        }
    }

    fun stopTracking() {
        // Drop the checkpoint here rather than in resetSession(), because this is
        // the one choke point every stop path passes through — the two notification
        // actions (stop-and-save, discard) never call resetSession(). Leaving it
        // would let SessionRecovery re-save the trip on next launch: a duplicate
        // after a save, and a resurrection of a session the user deliberately
        // discarded. The interactive paths persist from locals captured before
        // this call, so clearing now does not endanger the save in flight.
        ticksSinceCheckpoint = 0
        lifecycleScope.launch { checkpointStore.clear() }

        // If this is a manual stop (NOT an idle-timeout auto-stop), record the timestamp so
        // AutoStartMonitorService can suppress auto-restart for 30 seconds.
        if (!userPrefsManager.getPendingResumeAfterIdleAutoStop()) {
            userPrefsManager.setManualStopMs(System.currentTimeMillis())
        }
        // Dump the Kalman residual/gap statistics before anything resets the filter — this is
        // the data a future innovation gate would be sized from. Measurement only.
        kalmanFilter.getDiagnostics()?.let {
            android.util.Log.d("TrackingService", "Kalman session stats: $it")
        }

        railLookupJob?.cancel()
        railLookupJob = null
        idleCheckJob?.cancel()
        idleCheckJob = null
        deadReckoningJob?.cancel()
        deadReckoningJob = null
        sessionActivityManager?.unregisterSessionActivityUpdates()
        sessionActivityManager = null
        lastVehicleRecognitionMs = 0L
        _leanActivityHint.value = null
        _isTracking.value = false
        isActivelyTracking = false
        _currentActivity.value = ActivityType.IDLE
        locationService.setFlyingMode(false)
        smoothedSpeed = 0f
        consecutiveSpeedOutliers = 0
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
    private fun scaledGpsDistanceMeters(rawMeters: Double, activity: ActivityType = ActivityType.DRIVING): Double {
        if (!rawMeters.isFinite() || rawMeters <= 0.0) return 0.0
        val scale = when (activity) {
            ActivityType.WALKING, ActivityType.RUNNING -> GPS_SCALE_WALKING
            ActivityType.CYCLING, ActivityType.MOTORCYCLE -> GPS_SCALE_CYCLING
            ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE,
            ActivityType.TRAIN, ActivityType.FLYING -> GPS_SCALE_DRIVING
            else -> GPS_PATH_DISTANCE_SCALE
        }
        return rawMeters * scale
    }

    /** Auto-stop and save session when idle timeout is reached. */
    private fun performAutoStopAndSave() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        val stats = _sessionStats.value
        val startMs = sessionStartTimeMs
        val routePath = getRoutePath()
        val accelSamples = getAccelerometerSamples()
        // Capture segments BEFORE stopTracking() resets the segment state (segmentStartTimeMs → 0).
        // Without this, idle auto-stopped sessions persist with an empty segment list and the
        // post-session Mode timeline never appears — unlike the manual Stop paths, which already do this.
        val segments = getFinalSegments()
        val adjustedStats = adjustStatsForSimplifiedPath(stats, routePath)
        val statsWithRoute = adjustedStats.copy(routePath = routePath, segments = segments)
        val app = applicationContext as KineticEcoApplication
        val appCtx = app.applicationContext

        userPrefsManager.setPendingResumeAfterIdleAutoStop(true)
        stopTracking()
        // Clear the finished session's live metrics so duration/distance/steps return to zero, matching
        // both manual-stop paths (which call resetSession()). Without this the Tracker screen keeps
        // showing the last duration after an idle auto-stop until a new session starts. Safe here because
        // everything the async save needs (stats, startMs, routePath, segments, accelSamples) was already
        // copied into locals above before stopTracking().
        resetSession()

        try {
            AutoStartMonitorService.start(appCtx)
        } catch (e: Exception) {
            android.util.Log.e("TrackingService", "Auto-stop: could not re-arm auto-start monitor", e)
        }

        if (userId != null && userId.isNotEmpty()) {
            val discardReason = SessionPlausibility.reasonToDiscard(statsWithRoute)
            if (discardReason != null) {
                android.util.Log.d(
                    "TrackingService",
                    "Auto-stop: discarding session ($discardReason, ${statsWithRoute.totalDistance}m path)"
                )
            } else {
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
    }

    fun setManualActivityMode(activity: ActivityType?) {
        _manualActivityMode.value = activity
        _leanActivityHint.value = null
        manualMismatchSinceMs = 0L
        manualMismatchNotifiedAtMs = 0L
        manualMismatchSuggestedActivity = null
        cancelActivityHintNotification()
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
    }

    fun dismissLeanActivityHint() {
        _leanActivityHint.value = null
        leanHintDismissedAtMs = System.currentTimeMillis()
    }

    fun dismissEvConfirmPrompt() {
        _evConfirmPrompt.value = false
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

    /**
     * Snapshot the accumulators for [SessionCheckpoint].
     *
     * Must be called from the same dispatcher that mutates the session lists (the
     * timer's main dispatcher). The `toList()` copies are what make the write
     * safe to hand to an IO thread afterwards — serialising the live mutable
     * lists off-thread would race with location updates appending to them.
     */
    private fun captureCheckpoint(): SessionCheckpoint = SessionCheckpoint(
        sessionStartTimeMs = sessionStartTimeMs,
        savedAtMs = System.currentTimeMillis(),
        stats = _sessionStats.value,
        routePath = pathPoints.toList(),
        segments = segmentList.toList(),
        openSegmentActivity = segmentActivity,
        openSegmentStartTimeMs = segmentStartTimeMs,
        openSegmentStartDistanceM = segmentStartDistanceM
    )

    /** Capture now on the caller's thread, then persist off it. */
    private fun writeCheckpoint() {
        if (!_isTracking.value) return
        val snapshot = captureCheckpoint()
        ticksSinceCheckpoint = 0
        lifecycleScope.launch { checkpointStore.write(snapshot) }
    }

    /**
     * Restore a snapshot into the live accumulators after the service was killed
     * and restarted by START_STICKY.
     *
     * Detector state is intentionally not restored — see [SessionCheckpoint].
     * Classification warms up again from scratch while the banked distance,
     * steps, route and segments carry on from where they were.
     */
    private fun restoreFromCheckpoint(checkpoint: SessionCheckpoint) {
        sessionStartTimeMs = checkpoint.sessionStartTimeMs
        _sessionStats.value = checkpoint.stats
        _sessionDuration.value = checkpoint.stats.totalDuration
        _sessionDistance.value = checkpoint.stats.totalDistance
        _sessionSteps.value = checkpoint.stats.totalSteps

        pathPoints.clear()
        pathPoints.addAll(checkpoint.routePath)
        segmentList.clear()
        segmentList.addAll(checkpoint.segments)
        segmentActivity = checkpoint.openSegmentActivity
        segmentStartTimeMs = checkpoint.openSegmentStartTimeMs
        segmentStartDistanceM = checkpoint.openSegmentStartDistanceM

        totalElevationGain = checkpoint.stats.elevationGain
        totalElevationLoss = checkpoint.stats.elevationLoss
        startingAltitude = checkpoint.stats.startingAltitude
        minAltitude = checkpoint.stats.minAltitude
        maxAltitude = checkpoint.stats.maxAltitude
        sessionTopSpeedMps = checkpoint.stats.topSpeedMps

        val lostSeconds = (System.currentTimeMillis() - checkpoint.savedAtMs) / 1000
        android.util.Log.w(
            "TrackingService",
            "Recovered session from checkpoint: ${checkpoint.distanceMeters}m banked, " +
                "~${lostSeconds}s lost since last snapshot"
        )
    }

    fun resetSession() {
        // The session is over — whatever happens next must not inherit its state.
        ticksSinceCheckpoint = 0
        lifecycleScope.launch { checkpointStore.clear() }
        _sessionDuration.value = 0L
        _sessionDistance.value = 0.0
        _sessionSteps.value = 0
        _sessionStats.value = SessionStats()
        _currentActivity.value = ActivityType.IDLE
        idleStartTimeMs = 0L
        idleAnchorLat = null
        idleAnchorLon = null
        idleTravelRing.clear()
        noTravelSinceMs = 0L
        stepCorrobLat = null
        stepCorrobLon = null
        stepCorrobAnchorSteps = 0
        stepCorrobSinceMs = 0L
        stepsCorroborated = true
        motorLowSpeedSinceMs = 0L
        lastDrivingBandMs = 0L
        pedestrianToMotorRiseSinceMs = 0L
        speedSampleBuf.clear()
        evConfirmPromptShownThisSession = false
        consecutiveCleanGpsReadings = 0
        accuracyHistory.clear()
        indoorTransitionDetectedMs = 0L
        lastBearingDeg = null
        headingJitterCount = 0
        lastRawGpsSpeed = Float.NaN
        consecutiveIdenticalGpsSpeeds = 0
        stillConfirmedSinceMs = 0L
        idleSensorLockSinceMs = 0L
        hoverAnchorLat = null
        hoverAnchorLon = null
        hoverAnchorSinceMs = 0L
        poorAccuracyStreak = 0
        displacementWindow.clear()
        _evConfirmPrompt.value = false
        coldStartVehicleMs = 0L
        lastPosition = null
        previousAltitude = 0.0
        lastUpdateTime = 0
        smoothedSpeed = 0f
        consecutiveSpeedOutliers = 0
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
        sessionTopSpeedMps = 0.0
        pathPoints.clear()
        lastPathRecordTime = 0
        lastRefinedActivity = ActivityType.IDLE
        segmentList.clear()
        segmentActivity = ActivityType.IDLE
        segmentStartTimeMs = 0L
        segmentStartDistanceM = 0.0
        locationService.setFlyingMode(false)
        accelerometerSamples.clear()
        lastAccelSampleTimeMs = 0
        kalmanFilter.reset()
        flightTracker.reset()
        altitudeFilter.reset()
        previousSmoothedAltitude = null
        cadenceSamples.clear()

        pocketLeanEstimator.reset()
        leanMotorPromoSinceMs = 0L
        leanBikePromoSinceMs = 0L
        _leanActivityHint.value = null
        leanHintDismissedAtMs = 0L
        manualMismatchSinceMs = 0L
        manualMismatchNotifiedAtMs = 0L
        manualMismatchSuggestedActivity = null
        cancelActivityHintNotification()

        // Reset activity persistence tracking
        activityStartTime = System.currentTimeMillis()
        activityDurationSeconds = 0
        sessionStartTimeMs = 0
    }

    /** Get session start time in ms (when user started tracking) - for correct session date. */
    fun getSessionStartTimeMs(): Long = sessionStartTimeMs
    
    /** Get accelerometer samples collected during tracking (1 sample/sec) for Firestore analytics. */
    fun getAccelerometerSamples(): List<AccelerometerSample> = accelerometerSamples.toList()

    /** Returns completed segments plus the still-open current segment (closed to now). */
    fun getFinalSegments(): List<ActivitySegment> {
        val result = segmentList.toMutableList()
        if (segmentStartTimeMs > 0L) {
            val now = System.currentTimeMillis()
            val segDurationMs = now - segmentStartTimeMs
            val segDistance = _sessionDistance.value - segmentStartDistanceM
            // Always close the open segment if the session had any meaningful time or distance,
            // even if below the mid-session filter (we want at least one segment per session).
            if (segDurationMs >= 5_000L || segDistance >= 10.0 || result.isEmpty()) {
                result.add(ActivitySegment(
                    type = segmentActivity,
                    startTime = segmentStartTimeMs,
                    endTime = now,
                    distance = segDistance.coerceAtLeast(0.0),
                    avgSpeed = if (segDurationMs > 0) segDistance / (segDurationMs / 1000.0) else 0.0
                ))
            }
        }
        android.util.Log.d("TrackingService", "getFinalSegments: ${result.size} segments, segmentStartTimeMs=$segmentStartTimeMs")
        return result
    }

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
        now: Long,
        horizontalAccuracyM: Float
    ) {
        // Admit a point only on a fix good enough to have counted toward distance.
        //
        // These two rules had diverged: distance zeroes its delta above accLimit, while the
        // route recorded every fix regardless. The map and the elevation profile were therefore
        // drawn from geometry the app's own distance maths had already rejected as untrustworthy
        // — an indoor session measured 418 m of credited distance against 2.45 km of drawn path,
        // so the elevation chart's x-axis read nearly six times the trip's actual length, and
        // scatter the distance total never saw was still inflating SessionPlausibility's
        // displacement check.
        //
        // FLYING is exempt for the same reason it is exempt downstream: cabin fixes are poor by
        // nature and the route is the only record of the flight path.
        val accLimit = if (refinedActivity == ActivityType.WALKING) {
            MAX_ACCURACY_METERS_WALKING_DISTANCE
        } else {
            MAX_ACCURACY_METERS_FOR_DISTANCE
        }
        val accuracyAdmits = refinedActivity == ActivityType.FLYING ||
            !horizontalAccuracyM.isFinite() ||
            horizontalAccuracyM <= accLimit
        // Never drop the very first point: it anchors the route, and an empty route is worse
        // than one that starts on a mediocre fix.
        if (!accuracyAdmits && pathPoints.isNotEmpty()) return

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
            val isFirstStepEvent = lastStepCount == 0
            lastStepCount = data.stepCount
            // Align stepCountAtLastGps on the first event of a session so downstream deltas
            // start from the same baseline.
            //
            // (An earlier comment here said data.stepCount is the raw boot-total and jumps to
            // e.g. 52,847 on the first event. It isn't: SensorService subtracts `initialSteps`
            // and emits a session-relative count starting at 0. The alignment is still correct,
            // the stated reason was not.)
            if (isFirstStepEvent) stepCountAtLastGps = lastStepCount
            lastStepTimestamp = now
            
            val statsActivity = activityForNonGpsStats()
            val isPedestrian = statsActivity == ActivityType.WALKING || statsActivity == ActivityType.RUNNING
            val isMotorised = statsActivity == ActivityType.DRIVING ||
                statsActivity == ActivityType.ELECTRIC_VEHICLE ||
                statsActivity == ActivityType.MOTORCYCLE ||
                statsActivity == ActivityType.CYCLING ||
                statsActivity == ActivityType.TRAIN ||
                statsActivity == ActivityType.FLYING

            // Credit the hardware pedometer to the session total whenever we're not in a
            // vehicle — even if classification briefly reads IDLE (e.g. the first second of
            // a walk, or a momentary pause). Gating this strictly to WALKING/RUNNING dropped
            // real steps from the total and made step counts read low.
            //
            // The motorised exclusion is for road vibration registering as steps in a car. It
            // trusts the classifier, which indoors is exactly what cannot be trusted: an indoor
            // walk misclassified as DRIVING by drift had its real steps thrown away. So the
            // exclusion now applies only when the vehicle classification has independent
            // support — Activity Recognition, or a recent genuine driving-band speed. A
            // DRIVING label reached purely from drift no longer suppresses real steps.
            val motorisedForReal = isMotorised && (
                isRecentVehicleRecognition() ||
                    (lastDrivingBandMs > 0L &&
                        (System.currentTimeMillis() - lastDrivingBandMs) < STICKY_RECENT_DRIVING_MS)
                )
            if (!motorisedForReal) {
                _sessionSteps.value += stepDelta
            }

            if (isPedestrian) {
                updateStepsInStats(stepDelta, statsActivity)
                // Step-based distance fallback when GPS hasn't updated (urban/indoor)
                val gpsStale = lastUpdateTime > 0 && (now - lastGpsDistanceUpdateMs) > STEP_BASED_GAP_THRESHOLD_MS
                // Only credit step-based distance when the steps have been shown to move the
                // device over the ground. Otherwise a shaken phone mints distance fastest
                // exactly when GPS is unavailable to contradict it.
                if (gpsStale && stepsCorroborated) {
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
    /**
     * Record a (timestamp, cumulative-step-count) sample and drop those older than
     * [CADENCE_WINDOW_MS], so [recentCadenceSpm] can read an *instantaneous* cadence. Session-average
     * cadence is dragged down by idle periods and understates the current pace; a short rolling
     * window reflects what the user is doing right now, which is what a speed ceiling needs.
     */
    private fun recordCadenceSample(now: Long) {
        cadenceSamples.addLast(now to _sessionSteps.value)
        while (cadenceSamples.size > 1 && now - cadenceSamples.first().first > CADENCE_WINDOW_MS) {
            cadenceSamples.removeFirst()
        }
    }

    /** Instantaneous cadence in steps/min over the rolling window, or null if not enough data. */
    private fun recentCadenceSpm(now: Long): Float? {
        val oldest = cadenceSamples.firstOrNull() ?: return null
        val spanMs = now - oldest.first
        if (spanMs < 2_000L) return null  // need a couple of seconds to be meaningful
        val stepDelta = _sessionSteps.value - oldest.second
        if (stepDelta < CADENCE_MIN_STEPS) return null
        return stepDelta / (spanMs / 60_000f)
    }

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

        val elapsedSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000.0).coerceAtLeast(1.0)
        val steps = _sessionSteps.value
        val sessionSpm = (steps / (elapsedSec / 60.0)).toFloat()
        val stepWindowMs = if (indoorTransitionDetectedMs > 0L) INDOOR_STEP_WINDOW_MS else PEDESTRIAN_RECENT_STEP_WINDOW_MS
        // Wall clock: lastStepTimestamp comes from updateSensors, not from a GPS fix.
        val recentlyStepped = lastStepTimestamp > 0L &&
            (System.currentTimeMillis() - lastStepTimestamp) < stepWindowMs

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

        // Cadence-derived walking ceiling: catches GPS speed inflated *within* the walking band
        // (e.g. a true 3 km/h read as 6 km/h) that the absolute cap below never sees, because such
        // a reading is under RUNNING_MIN. Uses instantaneous cadence × stride with generous headroom
        // (stride varies with pace), floored so a low session pace can't clip a normal walk. Distance
        // is unaffected — it's step-capped separately downstream — so this only corrects the shown
        // speed and speed-based classification.
        if (!likelyRunning) {
            val cadenceSpm = recentCadenceSpm(now)
            if (cadenceSpm != null) {
                val cadenceSpeed = (cadenceSpm / 60f) * STEP_LENGTH_WALKING.toFloat()
                val cadenceCeiling = (cadenceSpeed * CADENCE_SPEED_HEADROOM)
                    .coerceAtLeast(CADENCE_CEILING_FLOOR_MPS)
                if (speed > cadenceCeiling) {
                    android.util.Log.d(
                        "TrackingService",
                        "Pedestrian cadence cap: cadence=${cadenceSpm.toInt()} spm → " +
                            "~${cadenceSpeed * 3.6f} km/h, ceiling ${cadenceCeiling * 3.6f} km/h vs GPS ${speed * 3.6f} km/h"
                    )
                    return cadenceCeiling
                }
            }
        }

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
    
    /**
     * Re-evaluates whether recent step events are backed by ground movement.
     *
     * Runs on each GPS fix. Once [STEP_CORROB_MIN_STEPS] have accumulated since the anchor, the
     * net displacement over that window is divided by the step count: real walking clears
     * [STEP_CORROB_MIN_M_PER_STEP] comfortably (a normal stride is 0.6–0.8 m even along a
     * curve), while a shaken or jostled phone produces essentially none.
     *
     * The window is deliberately short — a few dozen steps, twenty-odd metres of real walking —
     * so a genuine loop that returns to its start is never mistaken for shaking. It measures
     * local progress, not whether the trip ends where it began.
     *
     * A window that ages out without enough steps re-anchors without a verdict, so a stationary
     * user is not judged on a handful of stray counts.
     */
    private fun updateStepCorroboration(lat: Double, lon: Double, now: Long) {
        if (!sensorService.hasStepCounter()) return
        val anchorLat = stepCorrobLat
        val anchorLon = stepCorrobLon
        if (anchorLat == null || anchorLon == null) {
            stepCorrobLat = lat
            stepCorrobLon = lon
            stepCorrobAnchorSteps = lastStepCount
            stepCorrobSinceMs = now
            return
        }
        val stepsInWindow = (lastStepCount - stepCorrobAnchorSteps).coerceAtLeast(0)
        if (stepsInWindow < STEP_CORROB_MIN_STEPS) {
            // Not enough evidence yet. Age the window out so a long quiet stretch doesn't leave
            // a stale anchor that later produces a misleadingly large displacement.
            if (now - stepCorrobSinceMs > STEP_CORROB_WINDOW_MS) {
                stepCorrobLat = lat
                stepCorrobLon = lon
                stepCorrobAnchorSteps = lastStepCount
                stepCorrobSinceMs = now
            }
            return
        }
        val moved = locationService.calculateDistance(anchorLat, anchorLon, lat, lon)
        val perStep = moved / stepsInWindow
        val corroborated = perStep >= STEP_CORROB_MIN_M_PER_STEP
        if (corroborated != stepsCorroborated) {
            android.util.Log.d("TrackingService",
                "Step corroboration ${if (corroborated) "restored" else "LOST"}: " +
                    "$stepsInWindow steps moved ${moved.toInt()}m " +
                    "(${"%.2f".format(perStep)} m/step, need ${STEP_CORROB_MIN_M_PER_STEP})")
        }
        stepsCorroborated = corroborated
        stepCorrobLat = lat
        stepCorrobLon = lon
        stepCorrobAnchorSteps = lastStepCount
        stepCorrobSinceMs = now
    }

    /** The ground-motor activity set — shared so the several places that test it cannot drift apart. */
    private fun isMotorActivity(activity: ActivityType): Boolean =
        activity == ActivityType.DRIVING ||
            activity == ActivityType.ELECTRIC_VEHICLE ||
            activity == ActivityType.MOTORCYCLE ||
            activity == ActivityType.TRAIN

    /**
     * True while Activity Recognition has recently reported IN_VEHICLE with enough confidence
     * to be worth trusting over the local heuristics.
     *
     * The window is generous relative to the 10 s update cadence: AR reports the *most probable*
     * activity, and it legitimately drops to UNKNOWN or STILL for a cycle or two at a traffic
     * stop without the user having left the car.
     */
    private fun isRecentVehicleRecognition(): Boolean =
        lastVehicleRecognitionMs > 0L &&
            (System.currentTimeMillis() - lastVehicleRecognitionMs) < VEHICLE_RECOGNITION_TTL_MS

    /**
     * Records a confident IN_VEHICLE reading from the session-scoped Activity Recognition
     * subscription. Deliberately does nothing else — this is evidence for the gates that ask
     * for it, not a classification decision in its own right.
     */
    private fun handleSessionActivityResult(intent: Intent) {
        // A delivery arriving after the session ended means the subscription outlived it
        // (OS kill between stopTracking and unregister). Tear it down rather than leaving a
        // stream running against a service that has no use for it.
        if (!_isTracking.value) {
            // stopTracking() has already nulled the field, so build a manager to cancel with —
            // the subscription is keyed by PendingIntent, not by instance.
            (sessionActivityManager ?: ActivityTransitionManager(this))
                .unregisterSessionActivityUpdates()
            sessionActivityManager = null
            return
        }
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val probable = result.mostProbableActivity
        if (probable.type == DetectedActivity.IN_VEHICLE &&
            probable.confidence >= VEHICLE_RECOGNITION_MIN_CONFIDENCE
        ) {
            lastVehicleRecognitionMs = System.currentTimeMillis()
            android.util.Log.d("TrackingService",
                "Activity Recognition: IN_VEHICLE (confidence ${probable.confidence}%)")
        }
    }

    /**
     * Clean consecutive fixes required before [speed] may seed a zeroed EMA.
     *
     * A pedestrian-band seed needs one fix — it is cheap to be wrong about and decays quickly.
     * A motor-band seed is not: it sets [MAX_SPEED_CHANGE] as the outlier window for every
     * subsequent tick, so a single indoor phantom latches and holds long after the spike that
     * produced it. Charge it the same evidence the classifier charges for calling something
     * DRIVING.
     */
    private fun cleanFixesToSeed(speed: Float): Int =
        if (speed >= SpeedThresholds.DRIVING_MIN.toFloat()) MOTOR_SPEED_SEED_CLEAN_FIXES else 1

    /**
     * Caps a motor-band speed claimed on a poor-accuracy fix at what the device's actual
     * displacement over the last few seconds can support.
     *
     * The distinction this draws is the one that separates indoor drift from travel: drift
     * oscillates around a point, so instantaneous speed spikes while *net* displacement stays
     * near zero. Real travel covers ground. Doppler speed alone cannot tell the two apart, and
     * above [GPS_MOTOR_ACCURACY_GATE_M] the raw value was being forwarded untouched — the
     * classifier already treats fixes in that band as untrustworthy, but nothing was applying
     * the same scepticism to the number on the speedometer.
     *
     * Deliberately narrow: good fixes, sub-driving speeds, and manual mode all pass through
     * unchanged, and the cap never clamps below [DISPLACEMENT_CAP_FLOOR_MPS].
     */
    private fun applyPoorAccuracyDisplacementCap(
        speed: Float,
        now: Long,
        horizontalAccuracyM: Float
    ): Float {
        if (_manualActivityMode.value != null) return speed
        if (horizontalAccuracyM <= GPS_MOTOR_ACCURACY_GATE_M) return speed
        if (speed < SpeedThresholds.DRIVING_MIN.toFloat()) return speed

        val oldest = displacementWindow.firstOrNull() ?: return speed
        val newest = displacementWindow.lastOrNull() ?: return speed
        val spanSec = (now - oldest.first) / 1000.0
        if (spanSec < DISPLACEMENT_MIN_SPAN_SEC) return speed

        val netMeters = locationService.calculateDistance(
            oldest.second, oldest.third, newest.second, newest.third
        )
        val ceiling = ((netMeters / spanSec).toFloat() * DISPLACEMENT_SPEED_HEADROOM)
            .coerceAtLeast(DISPLACEMENT_CAP_FLOOR_MPS)
        if (speed > ceiling) {
            android.util.Log.d(
                "TrackingService",
                "Displacement cap: ${horizontalAccuracyM.toInt()}m fix claims " +
                    "${(speed * 3.6f).toInt()} km/h but only ${netMeters.toInt()}m covered in " +
                    "${spanSec.toInt()}s → ${(ceiling * 3.6f).toInt()} km/h"
            )
            return ceiling
        }
        return speed
    }

    /**
     * Per-tick outlier window, shared by the display and EMA paths so a spike is suppressed
     * identically in both.
     *
     * Branch order matters and was previously wrong: the WALKING/IDLE branch sat above the
     * cold-start branch, so `smoothedSpeed < 1.0f` was unreachable — a phone in a stopped car
     * is classified IDLE, which meant the window at the moment of pulling away was
     * [MAX_SPEED_CHANGE_WALKING] (2.5 m/s). At 1 Hz that is a 2.5 m/s² acceleration limit, and
     * an ordinary brisk start exceeds it: the reading is rejected, `smoothedSpeed` stays put,
     * so the next tick's delta is *larger* and is rejected again. The activity stays IDLE
     * throughout, holding the window narrow — a self-reinforcing stall that let gentle
     * acceleration through and clamped anything faster.
     *
     * Cold start now wins, and a motor-band reading backed by [MOTOR_SPEED_SEED_CLEAN_FIXES]
     * clean fixes gets the full window — the same evidence bar [cleanFixesToSeed] already
     * charges for seeding the EMA in the motor band, so multipath still cannot buy its way in.
     *
     * The window is scaled by the actual fix interval, clamped so the 1 Hz case (the tuned
     * default) is unchanged at 1.0x and only genuinely delayed fixes widen it.
     */
    private fun outlierWindowFor(newSpeed: Float, activity: ActivityType, dtSec: Double): Float {
        val base = when {
            activity == ActivityType.FLYING -> MAX_SPEED_CHANGE_FLYING
            smoothedSpeed < 1.0f -> COLD_START_MAX_SPEED_CHANGE
            newSpeed >= SpeedThresholds.DRIVING_MIN.toFloat() &&
                consecutiveCleanGpsReadings >= MOTOR_SPEED_SEED_CLEAN_FIXES -> MAX_SPEED_CHANGE
            activity == ActivityType.WALKING || activity == ActivityType.IDLE -> MAX_SPEED_CHANGE_WALKING
            else -> MAX_SPEED_CHANGE
        }
        return base * dtSec.coerceIn(1.0, 3.0).toFloat()
    }

    // Returns outlier-gated raw speed for display (no EMA lag), matching the same outlier window
    // used by smoothSpeedWithOutlierRejection so spikes are suppressed in both paths.
    private fun rejectOutlierForDisplay(
        newSpeed: Float,
        activity: ActivityType,
        dtSec: Double = 1.0
    ): Float {
        // On the very first reading, only show speed when we have a clean GPS fix — same
        // guard as smoothSpeedWithOutlierRejection — so the speedometer doesn't flash an
        // indoor-phantom speed before the EMA has a valid baseline.
        if (smoothedSpeed == 0f) {
            return if (newSpeed >= SpeedThresholds.WALKING_MIN.toFloat() &&
                consecutiveCleanGpsReadings >= cleanFixesToSeed(newSpeed)
            ) newSpeed else 0f
        }
        val maxChange = outlierWindowFor(newSpeed, activity, dtSec)
        // Mirror the collapse rule in smoothSpeedWithOutlierRejection: a reading that drops to
        // a standstill is shown at once rather than held at the previous value. Without this
        // the dial keeps displaying a phantom speed while the EMA is being corrected, which is
        // the visible half of the same fault.
        if (newSpeed < smoothedSpeed && newSpeed < SpeedThresholds.WALKING_MIN.toFloat()) {
            return newSpeed
        }
        return if (Math.abs(newSpeed - smoothedSpeed) > maxChange) smoothedSpeed else newSpeed
    }

    private fun smoothSpeedWithOutlierRejection(
        newSpeed: Float,
        activity: ActivityType,
        dtSec: Double = 1.0
    ): Float {
        // First reading: only latch into smoothedSpeed when we have a plausible pace AND
        // at least one clean GPS fix (accuracy ≤ GPS_MOTOR_ACCURACY_GATE_M). Without the
        // accuracy guard, a phantom speed from cold GPS or indoor multipath gets latched as
        // the EMA baseline and takes ~15 ticks to decay back to true walking speed.
        if (smoothedSpeed == 0f) {
            val needed = cleanFixesToSeed(newSpeed)
            if (newSpeed >= SpeedThresholds.WALKING_MIN.toFloat() && consecutiveCleanGpsReadings >= needed) {
                smoothedSpeed = newSpeed
                consecutiveSpeedOutliers = 0
                return newSpeed
            }
            if (newSpeed >= SpeedThresholds.DRIVING_MIN.toFloat()) {
                android.util.Log.d("TrackingService",
                    "Refusing motor-band EMA seed: ${(newSpeed * 3.6f).toInt()} km/h with " +
                        "$consecutiveCleanGpsReadings clean fix(es), need $needed")
            }
            return 0f
        }

        val maxChange = outlierWindowFor(newSpeed, activity, dtSec)

        // Reject outliers (sudden massive speed changes)
        val speedChange = newSpeed - smoothedSpeed
        if (Math.abs(speedChange) > maxChange) {
            // Collapse to a standstill: accept it immediately, never ramp.
            //
            // The ramp below exists to converge on a new *higher* truth without letting a
            // single spike in. Applied downward to a near-zero reading it does real damage: a
            // multipath spike latches the EMA at a phantom speed, the true 0 then reads as the
            // outlier, and the EMA is walked back down one maxChange per tick — crediting
            // distance the whole way at a speed the device never travelled. Observed indoors on
            // a stationary phone: one 38 km/h spike, then 29 → 20 → 11 → 2 km/h over 18 s, all
            // of it counted. That ramp is the main generator of phantom indoor distance.
            //
            // Accepting a zero can only ever under-report, and a genuine stop should read as
            // stopped immediately anyway. A spurious zero during real travel is already handled
            // upstream, where a suspicious zero holds the previous speed while the sensors say
            // the user is moving — so anything reaching here has passed that check.
            //
            // Deliberately narrow: only a *downward* divergence landing below walking pace
            // snaps. Ordinary deceleration stays smoothed, and upward spikes stay gated.
            val collapsingToRest = speedChange < 0f &&
                newSpeed < SpeedThresholds.WALKING_MIN.toFloat()
            if (collapsingToRest) {
                android.util.Log.d("TrackingService",
                    "Speed collapse accepted without ramp: ${(smoothedSpeed * 3.6f).toInt()} → " +
                        "${(newSpeed * 3.6f).toInt()} km/h (ramping would credit phantom distance)")
                smoothedSpeed = newSpeed
                consecutiveSpeedOutliers = 0
                return smoothedSpeed
            }
            consecutiveSpeedOutliers++
            if (consecutiveSpeedOutliers >= SPEED_OUTLIER_RECOVERY_TICKS) {
                // The divergence has persisted across multiple ticks — smoothedSpeed
                // itself is likely the poisoned value (e.g. EMA drifted up during an
                // indoor multipath stretch). Step toward the new reading by maxChange
                // instead of rejecting forever, so it converges within a few ticks.
                smoothedSpeed += maxChange * Math.signum(speedChange)
                android.util.Log.d("TrackingService",
                    "Recovering from sustained speed divergence: stepping EMA toward ${newSpeed * 3.6f} km/h -> ${smoothedSpeed * 3.6f} km/h")
                return smoothedSpeed
            }
            android.util.Log.d("TrackingService",
                "Rejecting speed outlier: ${newSpeed * 3.6f} km/h (change: ${Math.abs(speedChange) * 3.6f} km/h, max: ${maxChange * 3.6f} km/h)")
            return smoothedSpeed // Keep previous speed
        }
        consecutiveSpeedOutliers = 0

        // Walking: smoother (alpha low) to damp GPS spikes; driving: responsive but not laggy on decel; flying: stable mid-band
        val alpha = when (activity) {
            ActivityType.FLYING -> 0.4f
            ActivityType.WALKING, ActivityType.IDLE -> 0.35f
            ActivityType.RUNNING -> 0.55f
            else -> 0.62f
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
        now: Long,
        horizontalAccuracyM: Float = POOR_ACCURACY_METERS
    ): ActivityType {
        if (_manualActivityMode.value != null) {
            pedestrianToMotorRiseSinceMs = 0L
            return refinedActivity
        }

        val cur = _currentActivity.value

        // GPS warm-up: the first ~15 s of a session is when chips most often emit a
        // spurious "jump" fix while acquiring lock (especially indoors/multipath) — a
        // single bad reading here can otherwise promote straight to DRIVING and then
        // get stuck via sticky-motor hysteresis. Hold the current activity until the
        // chip has had time to settle.
        // Wall clock: sessionStartTimeMs is stamped at startTracking, not from a GPS fix.
        if (sessionStartTimeMs > 0L &&
            (System.currentTimeMillis() - sessionStartTimeMs) < GPS_WARMUP_MS) {
            pedestrianToMotorRiseSinceMs = 0L
            return cur
        }

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

        // While an indoor GPS transition is active, require near-outdoor-quality
        // accuracy before allowing any motor promotion — indoor multipath can report
        // phantom speeds that look like driving even with the normal 30 m gate.
        val effectiveAccuracyGate = if (indoorTransitionDetectedMs > 0L) INDOOR_MOTOR_ACCURACY_GATE_M
                                    else GPS_MOTOR_ACCURACY_GATE_M

        // Real vehicles produce a SMOOTH accelerometer signature within ~3–5 s of
        // starting to drive; promote immediately when GPS also confirms outdoor
        // placement (good accuracy). Requiring both prevents indoor vibration
        // (HVAC, footsteps nearby) from causing a false SMOOTH while GPS drifts.
        if (sensorService.getMotionPattern() == MotionPattern.SMOOTH &&
            horizontalAccuracyM <= effectiveAccuracyGate) {
            pedestrianToMotorRiseSinceMs = 0L
            return refinedActivity
        }

        // Poor GPS with no SMOOTH sensor confirmation: GPS drift indoors (accuracy
        // typically 20–80 m) can produce apparent speeds that look like driving or
        // running. If accuracy is too weak to confirm vehicle-level displacement AND
        // the accelerometer doesn't see a vehicle signature, keep the pedestrian mode.
        if (horizontalAccuracyM > effectiveAccuracyGate) {
            pedestrianToMotorRiseSinceMs = 0L
            android.util.Log.d("TrackingService", "Motor promotion blocked: GPS ${horizontalAccuracyM}m > gate ${effectiveAccuracyGate}m${if (indoorTransitionDetectedMs > 0L) " (indoor)" else ""}")
            return cur
        }

        // Heading consistency gate: erratic direction changes signal GPS drift, not real vehicle movement
        if (headingJitterCount >= HEADING_JITTER_BLOCK_COUNT) {
            pedestrianToMotorRiseSinceMs = 0L
            android.util.Log.d("TrackingService", "Motor promotion blocked: heading jitter count=$headingJitterCount")
            return cur
        }

        // Require 2 consecutive GPS fixes with accuracy ≤ GPS_MOTOR_ACCURACY_GATE_M before promoting
        if (consecutiveCleanGpsReadings < 2) {
            pedestrianToMotorRiseSinceMs = 0L
            android.util.Log.d("TrackingService", "Motor promotion blocked: only $consecutiveCleanGpsReadings clean GPS reading(s), need 2")
            return cur
        }

        // Wall clock: coldStartVehicleMs is stamped from it at both assignment sites, whereas
        // `now` is the GPS fix timestamp.
        val coldStartActive = (coldStartVehicleMs > 0L &&
            (System.currentTimeMillis() - coldStartVehicleMs) < COLD_START_VEHICLE_WINDOW_MS) ||
            isRecentVehicleRecognition()

        // Speed consistency gate: the buffer must show a *settled* motor-band speed, not the
        // oscillation indoor GPS produces (e.g. 0→12→0→12 m/s, where the minimum is always
        // near zero). Skipped for confirmed vehicle context — cold-start or a live IN_VEHICLE
        // reading — because the car may legitimately be accelerating from rest.
        //
        // This used to demand that *every* sample clear DRIVING_MIN, i.e. eight consecutive
        // seconds never dipping below 18 km/h. Real city driving rarely offers that before the
        // next junction, so the gate blocked promotion on ordinary trips rather than only on
        // drift. Two ways to pass now, both of which drift fails:
        //   • a supermajority of samples above the threshold — tolerates one or two dips
        //     without tolerating an oscillation whose minimum is always near zero; or
        //   • a monotonically rising ramp, the pull-away signature (0→5→10→14 m/s) that the
        //     cold-start bypass was already written to accommodate but only granted in the
        //     first COLD_START_VEHICLE_WINDOW_MS of a session.
        if (!coldStartActive && speedSampleBuf.size >= SPEED_BUF_MIN_SAMPLES) {
            val aboveCount = speedSampleBuf.count { it >= SpeedThresholds.DRIVING_MIN.toFloat() }
            val required = Math.ceil(speedSampleBuf.size * SPEED_BUF_MOTOR_FRACTION.toDouble()).toInt()
            val isRisingRamp = speedSampleBuf.zipWithNext().all { (a, b) -> b >= a - RAMP_TOLERANCE_MPS } &&
                (speedSampleBuf.last() - speedSampleBuf.first()) >= RAMP_MIN_GAIN_MPS &&
                speedSampleBuf.last() >= SpeedThresholds.DRIVING_MIN.toFloat()
            if (aboveCount < required && !isRisingRamp) {
                pedestrianToMotorRiseSinceMs = 0L
                android.util.Log.d("TrackingService",
                    "Motor promotion blocked: GPS bouncing ($aboveCount/${speedSampleBuf.size} samples " +
                        "≥ DRIVING_MIN, need $required; not a rising ramp)")
                return cur
            }
        }

        // Cold-start vehicle: halve the promotion latency — Activity Recognition already
        // confirmed IN_VEHICLE context, so we need less GPS evidence.
        val effectiveLatencyMs = if (coldStartActive) {
            PEDESTRIAN_TO_MOTOR_PROMOTE_MS / 2
        } else {
            PEDESTRIAN_TO_MOTOR_PROMOTE_MS
        }

        if (pedestrianToMotorRiseSinceMs == 0L) {
            pedestrianToMotorRiseSinceMs = now
        }
        val sustainedFor = now - pedestrianToMotorRiseSinceMs
        return if (sustainedFor < effectiveLatencyMs) {
            android.util.Log.d(
                "TrackingService",
                "Promotion latency: holding $cur (candidate $refinedActivity sustained ${sustainedFor}ms / required ${effectiveLatencyMs}ms${if (coldStartActive) " [cold-start]" else ""})"
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
    private fun applyStickyMotorActivity(
        refinedActivity: ActivityType,
        speed: Float,
        now: Long,
        sensorStillOverride: Boolean = false
    ): ActivityType {
        val cur = _currentActivity.value
        val isMotor = isMotorActivity(cur)

        if (sensorStillOverride) {
            // Sensors have confirmed stationary for long enough — flush hysteresis timers
            // so the activity can drop out of DRIVING immediately.
            motorLowSpeedSinceMs = 0L
            lastDrivingBandMs = 0L
            if (isMotor && refinedActivity != cur) {
                // The vehicle just went IDLE after a confirmed stop (traffic light,
                // parking). Treat this like a cold-start vehicle context: when motion
                // resumes, speedSampleBuf would otherwise be full of stale zeros from
                // the stop (blocking the speed-consistency gate for ~SPEED_BUF_SIZE
                // ticks) and the full PEDESTRIAN_TO_MOTOR_PROMOTE_MS latency would apply
                // on top — together causing a long delay before DRIVING is detected
                // again even once GPS speed clearly shows the vehicle moving.
                speedSampleBuf.clear()
                // Wall clock, matching the onStartCommand assignment — coldStartVehicleMs is
                // compared against the wall clock, and `now` here is the GPS fix timestamp.
                coldStartVehicleMs = System.currentTimeMillis()
                android.util.Log.d("TrackingService", "Vehicle stopped (sensor-confirmed still): speed buffer cleared, cold-start window started for quick re-promotion")
            }
            return refinedActivity
        }
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
        // Two-wheeler auto-promotion disabled by user preference: lean/cornering signatures
        // no longer promote DRIVING → MOTORCYCLE or RUNNING → CYCLING. Motorised travel stays
        // classified as DRIVING; manual selection of MOTORCYCLE/CYCLING is unaffected.
        leanMotorPromoSinceMs = 0L
        leanBikePromoSinceMs = 0L
        return refinedActivity
    }

    private fun evaluateLeanManualHint(speed: Float, now: Long, manual: ActivityType) {
        _leanActivityHint.value = null

        // Flying and IDLE are intentional overrides — never second-guess them.
        if (manual == ActivityType.FLYING || manual == ActivityType.IDLE) return

        val isMotorManual = manual == ActivityType.DRIVING ||
            manual == ActivityType.ELECTRIC_VEHICLE ||
            manual == ActivityType.MOTORCYCLE ||
            manual == ActivityType.TRAIN
        val isPedestrianManual = manual == ActivityType.WALKING ||
            manual == ActivityType.RUNNING ||
            manual == ActivityType.CYCLING

        val isIdleSpeed  = speed < SpeedThresholds.IDLE_SPEED_MAX
        val isMotorSpeed = speed >= SpeedThresholds.DRIVING_MIN.toFloat()

        // Only flag clear, unambiguous mismatches.
        val mismatch = when {
            isIdleSpeed -> false                          // stopped/traffic-light — ignore
            isPedestrianManual && isMotorSpeed -> true   // pinned Walk/Run/Cycle but driving fast
            isMotorManual && !isMotorSpeed -> true       // pinned Drive/Train but moving slowly on foot
            else -> false
        }

        if (!mismatch) {
            // Mismatch resolved — clear clock but leave notification up so user can still act.
            manualMismatchSinceMs = 0L
            manualMismatchSuggestedActivity = null
            return
        }

        val suggested = if (isMotorSpeed) ActivityType.DRIVING else ActivityType.WALKING

        // Start or restart the clock when the suggested activity changes.
        if (manualMismatchSinceMs == 0L || manualMismatchSuggestedActivity != suggested) {
            manualMismatchSinceMs = now
            manualMismatchSuggestedActivity = suggested
            manualMismatchNotifiedAtMs = 0L
            cancelActivityHintNotification()
        }

        val elapsed = now - manualMismatchSinceMs

        // Auto-switch after 2 minutes of unresolved mismatch.
        if (elapsed >= MANUAL_MISMATCH_AUTO_SWITCH_MS) {
            android.util.Log.i("TrackingService",
                "Auto-switching from manual $manual to auto-detect after ${elapsed / 1000}s mismatch")
            setManualActivityMode(null)
            return
        }

        // Send a notification once after 30 s — skip if user already dismissed it this cycle.
        if (elapsed >= MANUAL_MISMATCH_NOTIFY_AFTER_MS && manualMismatchNotifiedAtMs == 0L) {
            showActivityHintNotification(manual, suggested)
            manualMismatchNotifiedAtMs = now
        }
    }

    private fun showActivityHintNotification(manual: ActivityType, suggested: ActivityType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ACTIVITY_HINT_CHANNEL_ID,
                "Activity Switch Suggestion",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Nudges you to switch when sensors detect a different movement"
                enableVibration(userPrefsManager.getNotificationSoundsEnabled())
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val manualName    = activityDisplayName(manual)
        val suggestedName = activityDisplayName(suggested)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val switchPi = PendingIntent.getService(
            this, REQ_SWITCH_TO_AUTO,
            Intent(this, TrackingService::class.java).apply { action = ACTION_SWITCH_TO_AUTO_DETECT },
            flags
        )
        val dismissPi = PendingIntent.getService(
            this, REQ_DISMISS_HINT,
            Intent(this, TrackingService::class.java).apply { action = ACTION_DISMISS_ACTIVITY_HINT },
            flags
        )
        val openAppPi = PendingIntent.getActivity(
            this, REQ_OPEN_APP,
            Intent(this, MainActivity::class.java),
            flags
        )

        val notification = NotificationCompat.Builder(this, ACTIVITY_HINT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Activity mismatch detected")
            .setContentText("Tracking as $manualName but you seem to be $suggestedName.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "You pinned tracking to $manualName, but sensors suggest $suggestedName. " +
                "Switch now, or the app will switch automatically in 2 minutes."
            ))
            .setContentIntent(openAppPi)
            .setAutoCancel(false)
            .addAction(0, "Switch to Auto-detect", switchPi)
            .addAction(0, "Keep $manualName", dismissPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ACTIVITY_HINT_NOTIFICATION_ID, notification)
    }

    private fun cancelActivityHintNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(ACTIVITY_HINT_NOTIFICATION_ID)
    }

    /** Compute forward bearing in degrees [0, 360) from (fromLat, fromLon) to (toLat, toLon). */
    private fun computeHeadingDeg(toLat: Double, toLon: Double, fromLat: Double, fromLon: Double): Float {
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        return ((Math.toDegrees(Math.atan2(y, x)).toFloat() + 360f) % 360f)
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
        
        // Time base for every gate below: the *fix's own* timestamp, not the moment the
        // callback ran. The location request sets maxUpdateDelayMillis above the interval, so
        // fixes can arrive batched — two or three delivered in the same millisecond. Under
        // wall-clock time those ticks each advanced the EMA and the ring buffers while every
        // duration-based gate (promotion latency, idle lock, hover confirm, sticky motor) saw
        // zero elapsed time, so the smoothers ran ahead of the timers that are supposed to
        // govern them. Fix timestamps are correctly spaced even when delivery is not.
        //
        // Falls back to wall clock if the provider reports a nonsensical time — some OEM
        // fused providers have shipped zero or epoch timestamps — and never moves backwards,
        // since a monotonic `now` is assumed throughout.
        val wallNow = System.currentTimeMillis()
        val fixTime = position.timestamp
        val now = if (fixTime > 0L && Math.abs(wallNow - fixTime) < FIX_TIME_TRUST_WINDOW_MS) {
            maxOf(fixTime, lastUpdateTime)
        } else {
            wallNow
        }

        updateStepCorroboration(filtered.latitude, filtered.longitude, now)

        // GPS hover detection: real travel covers ground over time. If fixes stay within
        // HOVER_RADIUS_M of an anchor point for HOVER_CONFIRM_MS, the device is parked —
        // any further "movement" is chip jitter, not travel. This catches drift the
        // sensor-STILL gate can miss (e.g. engine vibration keeps accelerometer variance
        // above the STILL threshold long after the vehicle has actually stopped).
        val anchorLat = hoverAnchorLat
        val anchorLon = hoverAnchorLon
        val isGpsHovering = if (anchorLat == null || anchorLon == null) {
            hoverAnchorLat = filtered.latitude
            hoverAnchorLon = filtered.longitude
            hoverAnchorSinceMs = now
            false
        } else if (locationService.calculateDistance(anchorLat, anchorLon, filtered.latitude, filtered.longitude) > HOVER_RADIUS_M) {
            // Genuine displacement — re-anchor here and start the window over.
            hoverAnchorLat = filtered.latitude
            hoverAnchorLon = filtered.longitude
            hoverAnchorSinceMs = now
            false
        } else {
            (now - hoverAnchorSinceMs) >= HOVER_CONFIRM_MS
        }

        // Update heading consistency and GPS accuracy streak
        lastPosition?.let { pos ->
            val bearing = computeHeadingDeg(filtered.latitude, filtered.longitude, pos.latitude, pos.longitude)
            val prev = lastBearingDeg
            lastBearingDeg = bearing
            if (prev != null) {
                val delta = Math.abs(((bearing - prev + 540f) % 360f) - 180f)
                if (delta > HEADING_JITTER_THRESHOLD_DEG) headingJitterCount = (headingJitterCount + 1).coerceAtMost(10)
                else headingJitterCount = (headingJitterCount - 1).coerceAtLeast(0)
            }
        }
        if (position.accuracy <= GPS_MOTOR_ACCURACY_GATE_M) {
            consecutiveCleanGpsReadings = (consecutiveCleanGpsReadings + 1).coerceAtMost(10)
        } else {
            consecutiveCleanGpsReadings = 0
        }

        if (position.accuracy >= INDOOR_ACCURACY_DEGRADED_M) {
            poorAccuracyStreak = (poorAccuracyStreak + 1).coerceAtMost(INDOOR_SUSTAINED_POOR_FIXES)
        } else {
            poorAccuracyStreak = 0
        }

        // Rolling positions for the poor-accuracy displacement cross-check.
        displacementWindow.addLast(Triple(now, filtered.latitude, filtered.longitude))
        while (displacementWindow.size > 1 && now - displacementWindow.first().first > DISPLACEMENT_WINDOW_MS) {
            displacementWindow.removeFirst()
        }

        // Outdoor→indoor transition detection: if accuracy was recently good and has
        // now degraded significantly, GPS multipath is likely. Clear the speed buffer
        // so stale outdoor speeds can't drive a DRIVING promotion indoors.
        accuracyHistory.addLast(position.accuracy)
        if (accuracyHistory.size > INDOOR_ACCURACY_HISTORY_SIZE) accuracyHistory.removeFirst()
        if (accuracyHistory.size >= INDOOR_ACCURACY_HISTORY_SIZE) {
            val recentBest = accuracyHistory.take(accuracyHistory.size - 2).minOrNull() ?: Float.MAX_VALUE
            if (recentBest <= INDOOR_ACCURACY_GOOD_M && position.accuracy >= INDOOR_ACCURACY_DEGRADED_M
                && indoorTransitionDetectedMs == 0L) {
                speedSampleBuf.clear()
                smoothedSpeed = 0f          // flush EMA so indoor phantom speeds don't persist
                consecutiveSpeedOutliers = 0
                consecutiveCleanGpsReadings = 0
                indoorTransitionDetectedMs = now
                android.util.Log.d("TrackingService",
                    "Indoor GPS transition: ${recentBest}m → ${position.accuracy}m, speed EMA + buffer cleared")
            }
        }

        // Indoor→outdoor re-emergence: once 3 consecutive clean fixes are seen while the
        // indoor flag is live, accuracy has recovered — flush the stale EMA immediately
        // so it doesn't take ~15 s to decay back to the user's actual walking pace.
        if (indoorTransitionDetectedMs > 0L && accuracyHistory.size >= 3) {
            val lastThree = accuracyHistory.toList().takeLast(3)
            if (lastThree.all { it <= INDOOR_ACCURACY_GOOD_M }) {
                speedSampleBuf.clear()
                smoothedSpeed = 0f
                consecutiveSpeedOutliers = 0
                consecutiveCleanGpsReadings = 0
                indoorTransitionDetectedMs = 0L
                android.util.Log.d("TrackingService", "Indoor→outdoor re-emergence: EMA speed flushed")
            }
        }

        if (indoorTransitionDetectedMs > 0L && (now - indoorTransitionDetectedMs) >= INDOOR_TRANSITION_TTL_MS) {
            indoorTransitionDetectedMs = 0L
        }

        // Indoors from the start: the transition detector above only fires on a good→bad accuracy
        // edge, so a session begun indoors — no clean outdoor fix to degrade from — never armed
        // indoor mode at all. Arm on sustained poor accuracy instead, and re-arm once the TTL
        // lapses while fixes are still indoor-quality. Placed after the TTL check so re-arming
        // costs no extra fix.
        if (indoorTransitionDetectedMs == 0L && poorAccuracyStreak >= INDOOR_SUSTAINED_POOR_FIXES) {
            indoorTransitionDetectedMs = now
            speedSampleBuf.clear()
            consecutiveSpeedOutliers = 0
            consecutiveCleanGpsReadings = 0
            // Only flush the EMA when it is carrying a motor-band value. Sustained poor accuracy
            // also describes walking under tree cover or in an urban canyon, and flushing there
            // would blank the speedometer every TTL cycle for a user who is genuinely moving.
            val flushedEma = smoothedSpeed >= SpeedThresholds.DRIVING_MIN.toFloat()
            if (flushedEma) smoothedSpeed = 0f
            android.util.Log.d("TrackingService",
                "Indoor GPS (sustained): $poorAccuracyStreak fixes ≥ ${INDOOR_ACCURACY_DEGRADED_M}m" +
                    "${if (flushedEma) ", motor-band speed EMA flushed" else ""}")
        }

        val timeDelta = if (lastUpdateTime > 0) (now - lastUpdateTime) / 1000.0 else 0.0

        // Get altitude: GPS first, barometric fallback when GPS altitude missing or poor (e.g. flying).
        // GPS altitude is ellipsoidal (WGS84) per the Android contract — convert to mean-sea-level via
        // the geoid separation so summary/route altitudes match maps. No-op until the EGM96 grid asset
        // is present. Applied here at the source so currentAltitude, the barometer calibration, and the
        // AltitudeFilter are all consistently in MSL. (A constant offset, so elevation gain/loss is
        // unaffected.)
        val gpsAltitude = position.altitude?.let {
            geoidService.toMeanSeaLevel(it, position.latitude, position.longitude)
        }
        val currentAltitude = when {
            gpsAltitude != null && gpsAltitude.isFinite() -> gpsAltitude
            sensorService.hasBarometer() -> sensorService.getBarometricAltitude(gpsAltitude)
            else -> 0.0
        }

        // Spike-rejected, smoothed altitude for everything the user sees (summary, route, elevation).
        // Feeds the raw GPS altitude (with its vertical accuracy) through AltitudeFilter; when GPS
        // altitude is absent the barometric fallback is passed with no vertical-accuracy claim so the
        // filter gates it on horizontal accuracy. currentAltitude (raw) is kept for flight-phase logic.
        val smoothedAltitude: Double? = if (gpsAltitude != null && gpsAltitude.isFinite()) {
            altitudeFilter.update(gpsAltitude, position.verticalAccuracy, position.accuracy)
        } else if (sensorService.hasBarometer()) {
            val baro = sensorService.getBarometricAltitude(gpsAltitude)
            if (baro.isFinite()) altitudeFilter.update(baro, null, position.accuracy) else altitudeFilter.current
        } else {
            altitudeFilter.current
        }
        /** Only for route points — omit when the filter has no trustworthy value yet (avoid storing fake altitude). */
        val routeAltitudeMeters: Double? = smoothedAltitude?.takeIf { it.isFinite() }

        // Track starting/stopping/min/max altitude for session summary from the SMOOTHED value, so a
        // single bad fix can never become the recorded min/max (the old "65 m instead of 1 m" bug).
        if (smoothedAltitude != null && smoothedAltitude.isFinite()) {
            if (startingAltitude == null) startingAltitude = smoothedAltitude
            stoppingAltitude = smoothedAltitude
            minAltitude = if (minAltitude == null) smoothedAltitude else minOf(minAltitude!!, smoothedAltitude)
            maxAltitude = if (maxAltitude == null) smoothedAltitude else maxOf(maxAltitude!!, smoothedAltitude)
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
        val distanceDelta = lastPosition?.takeIf { timeDelta > 0.01 }?.let { pos ->
            locationService.calculateDistance(
                pos.latitude,
                pos.longitude,
                filtered.latitude,
                filtered.longitude
            )
        } ?: 0.0
        val distanceBasedSpeed = if (timeDelta > 0.1 && distanceDelta > 0.5) {
            (distanceDelta / timeDelta).toFloat()
        } else 0f

        // Stale-fix detection: if the GPS timestamp is old the chip hasn't produced a
        // fresh fix, so any cached speed value is meaningless for the current moment.
        val fixIsStale = lastPosition != null && (now - position.timestamp) > GPS_FIX_STALE_MS
        // Locked-speed detection: GPS chips sometimes freeze the speed field at a
        // non-zero value across multiple fixes (common indoors). Reset the counter on
        // any value change.
        if (lastPosition != null) {
            if (position.speed == lastRawGpsSpeed) consecutiveIdenticalGpsSpeeds++
            else consecutiveIdenticalGpsSpeeds = 0
        }
        lastRawGpsSpeed = position.speed
        val speedIsLocked = consecutiveIdenticalGpsSpeeds >= GPS_IDENTICAL_SPEED_COUNT

        // Speed pipeline: raw GPS first, then Kalman fallback, then distance-based fallback
        // On the first fix (no previous position), the GPS speed field may be stale from a
        // prior session — ignore it so the first displayed speed is always 0.
        var speed = if (lastPosition == null) 0f else position.speed.coerceAtLeast(0f)
        val kalmanSpeed = filtered.speed.coerceAtLeast(0f)
        val gpsUnreliable = speed < GPS_SPEED_UNRELIABLE_MAX || position.accuracy > POOR_ACCURACY_METERS
        if (fixIsStale || speedIsLocked) {
            // Stale or frozen GPS — treat as zero; do not forward a cached speed value.
            speed = 0f
        } else if (gpsUnreliable) {
            if (kalmanSpeed > GPS_SPEED_UNRELIABLE_MAX) {
                speed = kalmanSpeed
            } else if (distanceBasedSpeed > GPS_SPEED_UNRELIABLE_MAX) {
                speed = distanceBasedSpeed
            } else if (smoothedSpeed > SUSPICIOUS_ZERO_HOLD_SPEED && sensorService.getSensorHint() != SensorHint.STILL) {
                speed = smoothedSpeed  // Hold last only when sensors confirm movement
            }
        }
        
        val motionPattern = sensorService.getMotionPattern()
        val sensorHint = sensorService.getSensorHint()
        val hasAccelerometer = sensorService.hasAccelerometer()

        // Track how long sensors have continuously confirmed STILL.
        if (sensorHint == SensorHint.STILL) {
            if (stillConfirmedSinceMs == 0L) stillConfirmedSinceMs = now
        } else {
            stillConfirmedSinceMs = 0L
        }
        // After STILL_MOTOR_OVERRIDE_MS of confirmed stillness, bypass sticky-motor
        // hysteresis so a stationary phone doesn't stay in DRIVING indefinitely.
        val sensorStillOverride = stillConfirmedSinceMs > 0L &&
            (now - stillConfirmedSinceMs) >= STILL_MOTOR_OVERRIDE_MS

        // Sensors confirm the user is stationary: GPS speed is noise — silence it.
        //
        // This used to zero unconditionally, on the reasoning that indoor GPS chips report
        // 20+ km/h through multipath and so a DRIVING_MIN escape hatch was a false one. But
        // STILL is variance-based, and a vehicle under steady power or at constant velocity
        // produces almost no variance (see SensorActivityClassifier.ACCEL_STILL_MEAN) — so an
        // unconditional zero silenced real driving too, which is unrecoverable: speed 0 →
        // IDLE → speed capped in the idle band → never crosses DRIVING_MIN again.
        //
        // The escape hatch is back, but it demands *vehicle context*, not a good accuracy
        // figure. An earlier version of this gate qualified on `accuracy <=
        // GPS_MOTOR_ACCURACY_GATE_M`, on the assumption that indoor multipath always reports
        // poor accuracy. Measured on a stationary phone indoors, that assumption is false: the
        // hatch opened ten times in one session on fixes reporting 10–28 m accuracy, one of
        // them claiming 49 km/h at 10 m. Each admitted phantom flipped IDLE → RUNNING, reset
        // the idle timer, and defeated the idle auto-stop — the session ran 831 s and banked
        // 1059 m without the phone ever moving.
        //
        // Accuracy cannot distinguish the two cases, so the gate asks a different question:
        // is there independent evidence a vehicle is involved? Activity Recognition reporting
        // IN_VEHICLE, or an established motor activity that was in the driving band recently.
        //
        // This bootstraps correctly. Pulling away from rest is real acceleration, which raises
        // the mean linear-acceleration magnitude and so is not classified STILL at all (see
        // SensorActivityClassifier.ACCEL_STILL_MEAN). The vehicle therefore reaches motor
        // context while accelerating, and the hatch then protects the constant-velocity cruise
        // that follows — the case it exists for. A phone sitting on a desk never enters motor
        // context and never gets an IN_VEHICLE reading, so it is always zeroed.
        if (_manualActivityMode.value == null && sensorHint == SensorHint.STILL) {
            val recentlyDriving = isMotorActivity(_currentActivity.value) &&
                lastDrivingBandMs > 0L &&
                (now - lastDrivingBandMs) < STICKY_RECENT_DRIVING_MS
            val vehicleRecognised = isRecentVehicleRecognition()
            val vehicleContext = recentlyDriving || vehicleRecognised
            // Still require the reading itself to be plausible, so vehicle context alone
            // cannot wave through an arbitrary spike.
            val plausibleReading = speed >= SpeedThresholds.DRIVING_MIN.toFloat() &&
                position.accuracy <= GPS_MOTOR_ACCURACY_GATE_M
            if (!vehicleContext || !plausibleReading) {
                speed = 0f
            } else {
                android.util.Log.d("TrackingService",
                    "STILL zeroing skipped: ${(speed * 3.6f).toInt()} km/h on a " +
                        "${position.accuracy.toInt()}m fix (" +
                        (if (vehicleRecognised) "AR: IN_VEHICLE" else "recent driving band") +
                        ") — steady motion reads as low variance")
            }
        }

        // GPS static jitter gate: catches the common case where SensorHint hasn't
        // converged yet (needs 5 s / 20 samples) or hand tremors keep variance above
        // the STILL threshold — but linear-acceleration magnitude is clearly too low to
        // be real walking motion. GPS chips routinely report 0.5–1.5 m/s of noise when
        // completely stationary; this kills that display artefact.
        // Guards: speed must be below true walking speed, no recent steps (ON_FOOT),
        // and below driving min so a car interior is never muted.
        if (_manualActivityMode.value == null &&
            speed < SpeedThresholds.WALKING_MIN.toFloat() * 2f &&  // < ~1 m/s (3.6 km/h)
            currentAcceleration < 0.30f &&   // no walking-step impulse
            currentRotationRate < 0.20f &&   // no real body rotation
            sensorHint != SensorHint.ON_FOOT) {
            speed = 0f
        }

        // Sustained GPS hover: a parked vehicle can keep accelerometer variance elevated
        // (engine idle, road vibration) long after it has actually stopped, which is exactly
        // when chip drift would otherwise rack up phantom distance while the map shows the
        // user circling the same spot.
        //
        // No longer an unconditional override. HOVER_RADIUS_M / HOVER_CONFIRM_MS (30 m / 90 s)
        // also describes a traffic queue: creeping under 30 m in a minute and a half is
        // ordinary in a jam, and zeroing there discarded real distance. Require the sensors not
        // to contradict it — a confirmed vehicle context, or a live accelerometer reading well
        // above the stillness floor, means the device is being carried by something moving and
        // the hover is a GPS artefact rather than a parked car.
        if (isGpsHovering) {
            val sensorsContradictHover = isRecentVehicleRecognition() ||
                currentAcceleration > HOVER_MOTION_CONTRADICTION_ACCEL
            if (!sensorsContradictHover) {
                speed = 0f
            } else {
                android.util.Log.d("TrackingService",
                    "Hover zeroing skipped: sensors report motion (accel=$currentAcceleration" +
                        "${if (isRecentVehicleRecognition()) ", AR: IN_VEHICLE" else ""}) — " +
                        "likely a traffic queue, not a parked vehicle")
            }
        }

        recordCadenceSample(now)
        speed = applyPedestrianGpsSanityCap(
            speed = speed,
            now = now,
            motionPattern = motionPattern,
            altitudeM = currentAltitude,
            horizontalAccuracyM = position.accuracy
        )
        
        val preliminaryActivity = if (_manualActivityMode.value != null) {
            _manualActivityMode.value!!
        } else {
            locationService.classifyActivityWithAltitude(
                speed, currentAltitude, previousAltitude,
                motionPattern, hasAccelerometer, sensorHint
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

        // Physics plausibility gate: reject readings where the implied acceleration
        // between consecutive fixes exceeds physical limits. GPS indoors can jump from
        // 0 → 40 km/h in one 1-second interval (~11 m/s²), which no ground vehicle
        // produces at that GPS sampling rate. Capped at MAX_PHYSICAL_ACCEL_MPS2.
        if (lastPosition != null && timeDelta in 0.1..4.0) {
            val impliedAccel = Math.abs(speed - smoothedSpeed) / timeDelta.toFloat()
            if (impliedAccel > MAX_PHYSICAL_ACCEL_MPS2 && smoothedSpeed < SpeedThresholds.DRIVING_MIN.toFloat()) {
                android.util.Log.d("TrackingService",
                    "Physics gate: implied ${impliedAccel.toInt()} m/s² (Δspeed=${((speed - smoothedSpeed) * 3.6f).toInt()} km/h in ${timeDelta.toInt()}s) — spike rejected")
                speed = smoothedSpeed
            }
        }

        // Poor-accuracy displacement cross-check: the last gate before the value is published.
        // Runs ahead of the ring-buffer record so the motor-promotion consistency gate sees the
        // capped value too — a claim the device's own track can't support should not count as
        // evidence for DRIVING either.
        speed = applyPoorAccuracyDisplacementCap(speed, now, position.accuracy)

        // Record raw speed (post-gate, pre-EMA) for the GPS consistency ring buffer
        // used by applyPedestrianToMotorPromotionLatency to veto indoor bounce patterns.
        if (speedSampleBuf.size >= SPEED_BUF_SIZE) speedSampleBuf.removeFirst()
        speedSampleBuf.addLast(speed)

        // Display: outlier-gated raw GPS speed (no EMA lag) for real-time feel.
        // Classification: EMA-smoothed speed for stable activity decisions.
        _currentSpeed.value = rejectOutlierForDisplay(speed, preliminaryActivity, timeDelta)
        speed = smoothSpeedWithOutlierRejection(speed, preliminaryActivity, timeDelta)

        // Reclassify with smoothed speed
        var refinedActivity = if (_manualActivityMode.value != null) {
            _manualActivityMode.value!!
        } else {
            locationService.classifyActivityWithAltitude(
                speed, currentAltitude, previousAltitude,
                motionPattern, hasAccelerometer, sensorHint
            )
        }
        
        // Speed gates: Strict boundaries (user preference - immediate, consistent switches)
        // When manual activity is selected, skip gates so all data goes to the chosen category only
        if (_manualActivityMode.value == null) {
            refinedActivity = when {
                speed < SpeedThresholds.IDLE_SPEED_MAX -> ActivityType.IDLE
                speed >= SpeedThresholds.DRIVING_MIN -> {
                    // >= 18 km/h: FLYING (altitude + speed confirmed), CYCLING (sensor-confirmed), or DRIVING
                    when (refinedActivity) {
                        ActivityType.FLYING -> if (speed >= FLYING_MIN_SPEED_MPS && currentAltitude > HIGH_ALTITUDE_M) {
                            ActivityType.FLYING
                        } else {
                            ActivityType.DRIVING
                        }
                        ActivityType.CYCLING -> ActivityType.CYCLING
                        else -> ActivityType.DRIVING
                    }
                }
                else -> enforceActivitySpeedConsistency(refinedActivity, speed, currentAltitude)
            }
            refinedActivity = applyStickyMotorActivity(refinedActivity, speed, now, sensorStillOverride)
            refinedActivity = applyPedestrianToMotorPromotionLatency(refinedActivity, now, position.accuracy)
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

        // Sensor-GPS agreement gate: if the phone has been near-motionless for
        // SENSOR_IDLE_LOCK_MS, keep the activity as IDLE regardless of what GPS speed
        // reports. This prevents GPS drift on a still phone from triggering phantom
        // WALKING/CYCLING transitions and accumulating false distance.
        //
        // The step-based branch is meaningless in a vehicle — there are never steps in a car,
        // so `no steps recently` is permanently true and the gate collapsed to `speed <
        // DRIVING_MIN`. Every dip under 18 km/h (junction, roundabout, jam, car park, tunnel)
        // hard-forced IDLE, silently defeating applyStickyMotorActivity's hysteresis, which
        // runs earlier and exists for exactly this case. Two guards fix that: an explicit
        // motor-context exemption, and requiring the accelerometer to *agree* rather than
        // treating it as a fallback only consulted on devices with no step counter.
        val inMotorContext = isMotorActivity(_currentActivity.value) &&
            lastDrivingBandMs > 0L && (now - lastDrivingBandMs) < STICKY_RECENT_DRIVING_MS
        val vehicleConfirmed = inMotorContext || isRecentVehicleRecognition()
        val stepsQuiet = if (sensorService.hasStepCounter()) {
            // Time-based: gate fires only when no step has been detected for SENSOR_IDLE_LOCK_MS.
            // A count-based check (stepsNow == 0) is unreliable because the dead-reckoning path
            // resets stepCountAtLastGps on every step event, making stepsNow always 0 at GPS
            // update time even during active walking — which forced IDLE mid-walk and broke route
            // continuity. lastStepTimestamp is immune to that synchronization problem.
            // wallNow, not now: lastStepTimestamp is stamped in updateSensors from the wall
            // clock, while `now` here is the GPS fix's own timestamp. Comparing the two mixes
            // clocks and biases the elapsed figure by the fix's age.
            //
            // Uncorroborated steps count as quiet: a shaken phone emits step events forever,
            // and without this the idle lock could never engage while it was being jostled.
            !stepsCorroborated || (wallNow - lastStepTimestamp) >= SENSOR_IDLE_LOCK_MS
        } else {
            true
        }
        // The accelerometer must corroborate stillness whenever it is available. Previously
        // this was an `else` branch reached only on step-counter-less devices, so on a normal
        // phone nothing checked whether the device was actually moving.
        val accelQuiet = !sensorService.hasAccelerometer() ||
            currentAcceleration < SENSOR_STILL_ACCEL_THRESHOLD
        val sensorsQuiet = _manualActivityMode.value == null &&
            refinedActivity != ActivityType.FLYING &&
            speed < SpeedThresholds.DRIVING_MIN.toFloat() &&
            !vehicleConfirmed &&
            stepsQuiet && accelQuiet
        if (sensorsQuiet) {
            if (idleSensorLockSinceMs == 0L) idleSensorLockSinceMs = now
        } else {
            idleSensorLockSinceMs = 0L
        }
        val sensorIdleLocked = idleSensorLockSinceMs > 0L &&
            (now - idleSensorLockSinceMs) >= SENSOR_IDLE_LOCK_MS
        if (sensorIdleLocked) {
            refinedActivity = ActivityType.IDLE
        }

        lastRefinedActivity = refinedActivity

        val manual = _manualActivityMode.value
        if (manual != null) {
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
            
            // Elevation: use the SMOOTHED altitude and a deadband so per-fix GPS altitude noise
            // (±several m each fix) no longer accumulates as phantom climb/descent. Only count a
            // change once it exceeds ELEVATION_MIN_DELTA_M, and re-baseline from the smoothed series.
            // Also: do not treat cabin/cruise GPS altitude drift as "climbing" (inflated flight calories).
            var elevationGainDelta = 0.0
            var elevationLossDelta = 0.0
            if (refinedActivity != ActivityType.FLYING && smoothedAltitude != null && smoothedAltitude.isFinite()) {
                val prevSmoothed = previousSmoothedAltitude
                if (prevSmoothed != null) {
                    val elevationDelta = smoothedAltitude - prevSmoothed
                    if (elevationDelta >= ELEVATION_MIN_DELTA_M) {
                        elevationGainDelta = elevationDelta
                        totalElevationGain += elevationDelta
                        previousSmoothedAltitude = smoothedAltitude
                    } else if (elevationDelta <= -ELEVATION_MIN_DELTA_M) {
                        elevationLossDelta = Math.abs(elevationDelta)
                        totalElevationLoss += Math.abs(elevationDelta)
                        previousSmoothedAltitude = smoothedAltitude
                    }
                    // Within the deadband: hold the baseline so small oscillations don't accumulate.
                } else {
                    previousSmoothedAltitude = smoothedAltitude
                }
            }
            
            val isActuallyMoving = !sensorIdleLocked && !isGpsHovering && (speed > 0.15f || currentAcceleration > 0.2f)
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
            }

            // A step shorter than the fix's own error radius carries no information: the phone
            // could equally have not moved. Doppler speed is far more robust to multipath than
            // differencing two positions, so a near-zero reported speed alongside a
            // sub-accuracy step means the device is sitting still — which is exactly the indoor
            // case where motor mode had no cap at all and 4 minutes on a table accumulated
            // 390 m. Walking and running already veto on the pedometer below; this closes the
            // same hole for every other mode.
            if (dd > 0 && refinedActivity != ActivityType.FLYING &&
                dd < position.accuracy && position.speed < GPS_NOISE_SPEED_FLOOR_MPS
            ) {
                dd = 0.0
            }

            // Physics cap — applies to EVERY mode, ahead of the pedestrian caps below.
            //
            // Walking and running already bound distance by steps and a speed ceiling, but no
            // other mode bounded it at all, and reported accuracy does not catch multipath: a
            // fix can claim 10 m accuracy while being hundreds of metres wrong. Measured on a
            // real walk that passed through a supermarket, ten teleports contributed 46% of the
            // session's polyline, the largest a single 243 m hop implying 148 km/h. Two of
            // those points were classified DRIVING, which put them beyond the pedestrian caps
            // entirely and charged the user 0.088 kg of emissions for a walk. Drift is
            // self-protecting that way: small jumps get capped, large ones get promoted out of
            // the cap's reach.
            //
            // The bound is the device's own recently sustained speed, taken as the median of
            // the raw-speed ring so one spike cannot lift it, times generous headroom. A floor
            // keeps genuine acceleration from a standstill from being clipped, and FLYING is
            // exempt as everywhere else.
            if (dd > 0 && refinedActivity != ActivityType.FLYING && timeDelta > 0.1) {
                val sustainedMps = if (speedSampleBuf.isEmpty()) 0f else {
                    val sorted = speedSampleBuf.sorted()
                    sorted[sorted.size / 2]
                }
                // The floor keeps a standstill start from being clipped, but a vehicle floor
                // applied to a walk is far too generous: at a 6 s fix interval it permits 47 m
                // per hop, so most teleports slip under it. Use the vehicle floor only when
                // there is independent evidence of a vehicle — the drift teleports that need
                // catching arrive labelled DRIVING without any such support, and would
                // otherwise buy the loosest bound by being wrong.
                val vehicleContext = isRecentVehicleRecognition() ||
                    (lastDrivingBandMs > 0L &&
                        (now - lastDrivingBandMs) < STICKY_RECENT_DRIVING_MS)
                val floorMps = if (vehicleContext) PHYSICS_CAP_FLOOR_MPS
                               else PHYSICS_CAP_FLOOR_PEDESTRIAN_MPS
                // Do not let the ceiling grow without limit as fixes get sparser. A long gap
                // between fixes means the receiver lost lock; it is not evidence the user
                // covered that whole interval's worth of ground. Left unbounded the cap is
                // loosest exactly when the data is least trustworthy — measured across 17 real
                // sessions, the drifting ones averaged 8–25 s between points against 4.5–5.9 s
                // for clean walks, so a 25 s gap was buying a 112 m allowance per hop.
                //
                // Beyond this bound, distance for the missing time is dead reckoning's job,
                // which has its own evidence and its own limits.
                val effectiveDt = minOf(timeDelta, PHYSICS_CAP_MAX_DT_SEC)
                val ceiling = maxOf(
                    sustainedMps * PHYSICS_CAP_HEADROOM,
                    floorMps
                ) * effectiveDt
                if (dd > ceiling) {
                    android.util.Log.d("TrackingService",
                        "Physics distance cap: ${dd.toInt()}m in ${"%.1f".format(timeDelta)}s " +
                            "(${(dd / timeDelta * 3.6).toInt()} km/h implied) vs sustained " +
                            "${(sustainedMps * 3.6f).toInt()} km/h → ${ceiling.toInt()}m " +
                            "[$refinedActivity, dt capped to ${effectiveDt.toInt()}s]")
                    dd = ceiling
                }
            }

            // Zero-step veto, for every mode without vehicle context.
            //
            // WALKING and RUNNING already discard a segment when the pedometer reports nothing,
            // on the reasoning that GPS-only movement is untrustworthy. That reasoning does not
            // stop at those two labels, but the check did — so a drift teleport classified
            // DRIVING or IDLE skipped it entirely. Across 17 recorded sessions the split was
            // unambiguous: every genuine walk carried 365–2160 steps, every false session
            // carried 0–56, yet the false ones banked up to 2306 m and 0.24 kg of emissions
            // because their label put them out of the veto's reach.
            //
            // Vehicle context is the exemption, since a car legitimately produces no steps.
            // Without it, no steps and no Doppler speed means no evidence of travel from any
            // source that multipath cannot fake.
            if (dd > 0 && sensorService.hasStepCounter() &&
                refinedActivity != ActivityType.FLYING &&
                refinedActivity != ActivityType.WALKING &&
                refinedActivity != ActivityType.RUNNING
            ) {
                val vehicleContext = isRecentVehicleRecognition() ||
                    (lastDrivingBandMs > 0L &&
                        (now - lastDrivingBandMs) < STICKY_RECENT_DRIVING_MS)
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                if (!vehicleContext && stepsThisInterval == 0 &&
                    position.speed < GPS_NOISE_SPEED_FLOOR_MPS
                ) {
                    android.util.Log.d("TrackingService",
                        "Zero-step veto: dropped ${dd.toInt()}m as $refinedActivity — " +
                            "no steps, no vehicle context, Doppler ${position.speed} m/s")
                    dd = 0.0
                }
            }

            if (dd > 0 && refinedActivity == ActivityType.WALKING) {
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                // When the device has a step counter and it reports nothing, GPS-only speed is
                // unreliable (drift, being driven, early warm-up) — discard the segment entirely.
                if (stepsThisInterval == 0 && sensorService.hasStepCounter()) {
                    dd = 0.0
                } else {
                    val speedCap = CAP_WALKING_SPEED_MPS * timeDelta
                    dd = if (stepsThisInterval > 0) {
                        val stepCap = stepsThisInterval * STEP_LENGTH_WALKING + STEP_GPS_BLEND_SLACK_M
                        minOf(dd, stepCap, speedCap)
                    } else {
                        minOf(dd, speedCap)
                    }
                }
            } else if (refinedActivity == ActivityType.RUNNING && dd > 0) {
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                if (stepsThisInterval == 0 && sensorService.hasStepCounter()) {
                    dd = 0.0
                } else {
                    val speedCap = CAP_RUNNING_SPEED_MPS * timeDelta
                    dd = if (stepsThisInterval > 0) {
                        val stepCap = stepsThisInterval * STEP_LENGTH_RUNNING + STEP_GPS_BLEND_SLACK_M
                        minOf(dd, stepCap, speedCap)
                    } else {
                        minOf(dd, speedCap)
                    }
                }
            }

            if (isActuallyMoving && meetsDistanceThreshold && dd > 0) {
                val ddScaled = scaledGpsDistanceMeters(dd, refinedActivity)
                _sessionDistance.value += ddScaled
                lastGpsDistanceUpdateMs = now
                stepCountAtLastGps = lastStepCount
                updateStats(ddScaled, refinedActivity, speed.toDouble(), elevationGainDelta, elevationLossDelta)
                // Record route point on movement (in addition to time interval)
                recordPathPointIfNeeded(filtered.latitude, filtered.longitude, routeAltitudeMeters, refinedActivity, now, position.accuracy)
            }
        }
        
        lastPosition = position.copy(
            latitude = filtered.latitude,
            longitude = filtered.longitude,
            // Carry the smoothed MSL altitude (not the raw ellipsoidal position.altitude) so the
            // trailing point appended in getRoutePath() is consistent with the rest of the route.
            altitude = smoothedAltitude
        )
        previousAltitude = currentAltitude
        lastUpdateTime = now
        deadReckonStartMs = 0L  // Reset - we got GPS, no longer in gap
        
        // Record path point: first point immediately, then on position change or every PATH_RECORD_INTERVAL_MS
        // This ensures route displays even when autodetect shows IDLE (slow movement) or GPS is delayed
        recordPathPointIfNeeded(filtered.latitude, filtered.longitude, routeAltitudeMeters, refinedActivity, now, position.accuracy)
        
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
            return ActivityType.DRIVING
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
                    activityHistory.add(activity)
                    if (activityHistory.size > ACTIVITY_HISTORY_SIZE) activityHistory.removeAt(0)
                    val motorCount = activityHistory.count { it == activity }
                    if (motorCount >= 3 && _currentActivity.value != activity) {
                        activityStartTime = System.currentTimeMillis()
                        activityDurationSeconds = 0
                        idleStartTimeMs = 0L
                        _currentActivity.value = activity
                        android.util.Log.d("TrackingService", "Activity: → $activity (3-reading motor confirm)")
                        // Show EV-confirm prompt once when the session first locks to DRIVING
                        if (activity == ActivityType.DRIVING && !evConfirmPromptShownThisSession &&
                            _manualActivityMode.value == null) {
                            evConfirmPromptShownThisSession = true
                            _evConfirmPrompt.value = true
                        }
                    }
                    return
                }
                ActivityType.FLYING -> {
                    activityHistory.add(activity)
                    if (activityHistory.size > ACTIVITY_HISTORY_SIZE) activityHistory.removeAt(0)
                    val flyCount = activityHistory.count { it == ActivityType.FLYING }
                    if (flyCount >= 3 && _currentActivity.value != activity) {
                        activityStartTime = System.currentTimeMillis()
                        activityDurationSeconds = 0
                        idleStartTimeMs = 0L
                        _currentActivity.value = activity
                        android.util.Log.d("TrackingService", "Activity: → FLYING (3-reading confirm)")
                    }
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
            // requiredConsistency ≈ seconds of delay before a switch commits (GPS votes ~1/s). Trimmed
            // by one across the common cases for snappier activity changes; the incompatible-transition
            // (5) and pedestrian→motor promotion gates upstream still guard against noisy-GPS false flips.
            var requiredConsistency = when {
                isStartupPhase -> 1          // First 10s: fast convergence
                mostCommon == ActivityType.IDLE -> 1  // Below 0.5 km/h → IDLE immediately
                isAdjacentTransition -> 2    // WALKING↔RUNNING, RUNNING↔DRIVING: ~2s
                activityDurationSeconds > 120 -> {
                    android.util.Log.d("TrackingService",
                        "Activity persistence: $currentActivity for ${activityDurationSeconds}s — requiring 3")
                    3
                }
                else -> if (isSignificantChange) 2 else 3
            }

            // Incompatible transitions require full agreement
            if (!isCompatible && mostCommon != ActivityType.IDLE) {
                requiredConsistency = 5
                android.util.Log.d("TrackingService",
                    "Incompatible transition: $currentActivity -> $mostCommon — requiring 5/5")
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

    private fun withElevationAndMilestones(stats: SessionStats): SessionStats = stats.copy(
        elevationGain = totalElevationGain,
        elevationLoss = totalElevationLoss,
        startingAltitude = startingAltitude,
        stoppingAltitude = stoppingAltitude,
        minAltitude = minAltitude,
        maxAltitude = maxAltitude,
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

                    // Segment boundary: close previous segment when activity changes
                    val tickNow = System.currentTimeMillis()
                    if (activity != segmentActivity && segmentStartTimeMs > 0L) {
                        val segDurationMs = tickNow - segmentStartTimeMs
                        val segDistance = _sessionDistance.value - segmentStartDistanceM
                        if (segDurationMs >= 15_000L || segDistance >= 50.0) {
                            segmentList.add(ActivitySegment(
                                type = segmentActivity,
                                startTime = segmentStartTimeMs,
                                endTime = tickNow,
                                distance = segDistance,
                                avgSpeed = if (segDurationMs > 0) segDistance / (segDurationMs / 1000.0) else 0.0
                            ))
                        }
                        segmentActivity = activity
                        segmentStartTimeMs = tickNow
                        segmentStartDistanceM = _sessionDistance.value
                        // A closed segment is a natural checkpoint boundary — snapshot
                        // here so a kill can't lose a whole leg of the journey.
                        writeCheckpoint()
                    }

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

                    // Periodic snapshot. Placed last so it captures this tick's
                    // stats rather than the previous one's.
                    if (++ticksSinceCheckpoint >= SessionCheckpointStore.CHECKPOINT_INTERVAL_SECONDS) {
                        writeCheckpoint()
                    }
                }
            }
        }
    }

    private fun createNotification(): Notification {
        ensureTrackingChannel()
        return buildTrackingNotification(contentText = "Starting up — GPS locking in...")
    }

    /** Idempotent setup of the foreground tracking channel — safe to call from
     *  both the initial [createNotification] path and the live [updateNotification]
     *  path.
     *
     *  Importance is **always** [NotificationManager.IMPORTANCE_DEFAULT] so the
     *  shade renders the full layout with the Save / Discard action row inline.
     *  Silence is applied at the channel level (and via `setSilent(true)` on the
     *  builder) rather than by dropping the channel to LOW, because LOW collapses
     *  the notification and forces the user to expand it just to see the actions.
     *
     *  This channel is **unconditionally silent**. Starting a session and waiting
     *  for a GPS lock are things the user just did on purpose — they don't need to
     *  be announced, and the notification is ongoing for the whole session. The
     *  "Notification sounds" setting deliberately does not apply here; it still
     *  governs the notifications the user didn't ask for (session summary,
     *  activity-switch hint). */
    private fun ensureTrackingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            "Kinetic Tracking",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows real-time tracking status"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        // Retire the superseded IDs. Without this the user's notification settings
        // list keeps showing a "Kinetic Tracking" row per generation, all but the
        // newest of them dead. Deleting an ID that was never created is a no-op.
        LEGACY_TRACKING_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
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
        // Always silent — see [ensureTrackingChannel]. Belt-and-braces alongside the
        // channel's setSound(null): pre-O devices have no channels at all, and this
        // also covers the per-post alert on updateNotification() refreshes.
        builder.setSilent(true)
        return builder.build()
    }

    private fun updateNotification() {
        if (!_isTracking.value) return

        val metersPerUnit = userPrefsManager.getMetersPerUnit()
        val unitLabel = userPrefsManager.getDistanceUnitLabel()
        val distanceInUnits = _sessionDistance.value / metersPerUnit
        val durationMin = _sessionDuration.value / 60
        val activity = _currentActivity.value

        val activityLabel = when (activity) {
            ActivityType.IDLE -> "Paused"
            ActivityType.WALKING -> "Walking"
            ActivityType.RUNNING -> "Running"
            ActivityType.CYCLING -> "Cycling"
            ActivityType.MOTORCYCLE -> "Riding"
            ActivityType.TRAIN -> "On train"
            ActivityType.DRIVING -> "Driving"
            ActivityType.ELECTRIC_VEHICLE -> "EV ride"
            ActivityType.FLYING -> "Flying"
        }
        val contentText = "$activityLabel — ${String.format("%.2f", distanceInUnits)} $unitLabel, ${durationMin} min"

        val notification = buildTrackingNotification(contentText = contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show an encouraging session-complete notification, then optionally follow up with a
     * weekly CO₂-goal progress notification when [weeklyCo2SavedKg] is supplied by the caller.
     *
     * Message tone is activity-specific:
     *  - Walking/Running → celebrate the eco movement
     *  - Cycling         → highlight savings vs driving
     *  - EV/Train        → clean-commute framing
     *  - Motorcycle/Driving → neutral distance summary (no CO₂ saved claim)
     *  - Flying          → distance/duration only
     */
    fun showSessionCompleteNotification(stats: SessionStats, weeklyCo2SavedKg: Float = 0f) {
        val soundsEnabled = userPrefsManager.getNotificationSoundsEnabled()
        val channelId = "session_celebrate_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Session Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Encouraging summary when a tracking session is saved"
                setShowBadge(true)
                enableVibration(soundsEnabled)
                setSound(if (soundsEnabled) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val metersPerUnit = userPrefsManager.getMetersPerUnit()
        val unitLabel = userPrefsManager.getDistanceUnitLabel()
        val distanceStr = String.format("%.2f %s", stats.totalDistance / metersPerUnit, unitLabel)
        val durationMin = stats.totalDuration / 60
        val co2Saved = stats.co2Conserved  // kg saved vs driving baseline

        val mainActivity = stats.breakdown.entries
            .filter { it.key != ActivityType.IDLE && it.value.time > 0 }
            .maxByOrNull { it.value.time }?.key ?: ActivityType.IDLE

        val (title, body) = when (mainActivity) {
            ActivityType.WALKING -> Pair(
                "Great walk!",
                "$distanceStr in ${durationMin} min — every step counts"
            )
            ActivityType.RUNNING -> Pair(
                "Nice run!",
                "$distanceStr in ${durationMin} min — you earned it"
            )
            ActivityType.CYCLING -> if (co2Saved > 0.0) Pair(
                "Great ride!",
                "$distanceStr in ${durationMin} min — saved ${String.format("%.2f", co2Saved)} kg CO₂ vs driving"
            ) else Pair(
                "Great ride!",
                "$distanceStr in ${durationMin} min"
            )
            ActivityType.TRAIN -> Pair(
                "Clean commute!",
                if (co2Saved > 0.0) "$distanceStr — saved ${String.format("%.2f", co2Saved)} kg CO₂"
                else "$distanceStr in ${durationMin} min"
            )
            ActivityType.ELECTRIC_VEHICLE -> Pair(
                "Zero-emission ride!",
                if (co2Saved > 0.0) "$distanceStr — saved ${String.format("%.2f", co2Saved)} kg CO₂"
                else "$distanceStr in ${durationMin} min"
            )
            ActivityType.FLYING -> Pair(
                "Flight logged",
                "$distanceStr in ${durationMin} min"
            )
            else -> Pair(
                "Session saved",
                "$distanceStr in ${durationMin} min"
            )
        }

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
        if (!soundsEnabled) builder.setSilent(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SESSION_SUMMARY_NOTIFICATION_ID, builder.build())

        if (weeklyCo2SavedKg > 0f) showWeeklyGoalNotificationIfNeeded(weeklyCo2SavedKg)
    }

    /**
     * Posts a weekly CO₂-goal progress notification when the user has saved ≥ 75 % of their
     * weekly target. Fires on a LOW-importance channel (no sound / badge) so it never competes
     * with the session-complete notification for attention.
     */
    private fun showWeeklyGoalNotificationIfNeeded(weeklyKg: Float) {
        val goalKg = userPrefsManager.getWeeklyCo2GoalKg()
        val pct = weeklyKg / goalKg
        if (pct < 0.75f) return

        val channelId = "weekly_goal_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Weekly Goal",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress toward your weekly CO₂ savings goal"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val (title, body) = if (pct >= 1f) Pair(
            "Weekly goal reached!",
            "You saved ${String.format("%.1f", weeklyKg)} kg CO₂ this week — nice work!"
        ) else Pair(
            "Almost there this week!",
            "You're at ${String.format("%.1f", weeklyKg)} / ${String.format("%.1f", goalKg)} kg CO₂ saved"
        )

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(WEEKLY_GOAL_NOTIFICATION_ID, builder.build())
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
        /**
         * Process-wide flag: true while a tracking session is actively running.
         * Read by [AutoStartMonitorService] so its background vehicle-detection poll can
         * skip spending a GPS fix when a session is already in progress (battery saver).
         */
        @Volatile
        var isActivelyTracking: Boolean = false
            private set

        const val NOTIFICATION_ID = 101
        const val ACTIVITY_HINT_NOTIFICATION_ID = 106
        const val SESSION_SUMMARY_NOTIFICATION_ID = 103
        /** ID for the brief "Session discarded" toast-style notification. */
        const val DISCARD_CONFIRMATION_NOTIFICATION_ID = 104
        /** ID for the weekly CO₂-goal progress nudge. */
        const val WEEKLY_GOAL_NOTIFICATION_ID = 105

        /** Intent extra: set to true when auto-start was triggered by an IN_VEHICLE transition. */
        const val EXTRA_COLD_START_VEHICLE = "extra_cold_start_vehicle"

        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        /** Delivery target for the session-scoped Activity Recognition subscription. */
        const val ACTION_PROCESS_ACTIVITY_RESULT = "ACTION_PROCESS_ACTIVITY_RESULT"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"
        const val ACTION_SWITCH_TO_AUTO_DETECT = "ACTION_SWITCH_TO_AUTO_DETECT"
        private const val ACTION_DISMISS_ACTIVITY_HINT = "ACTION_DISMISS_ACTIVITY_HINT"
        /** Foreground-notification action: save the in-flight session and stop. */
        const val ACTION_STOP_AND_SAVE = "ACTION_STOP_AND_SAVE"
        /** Foreground-notification action: stop tracking and throw the session
         *  away (used when the auto-start step counter triggers unintentionally). */
        const val ACTION_DISCARD = "ACTION_DISCARD"

        /** PendingIntent request codes — kept distinct so flag updates on one
         *  action don't accidentally clobber another. */
        private const val REQ_OPEN_APP = 0
        private const val REQ_STOP_SAVE = 401
        private const val REQ_DISCARD = 402
        private const val REQ_SWITCH_TO_AUTO = 403
        private const val REQ_DISMISS_HINT = 404

        private const val ACTIVITY_HINT_CHANNEL_ID = "activity_hint_channel"
        /** Sustained mismatch before the first notification is sent. */
        private const val MANUAL_MISMATCH_NOTIFY_AFTER_MS = 30_000L
        /** Sustained mismatch before auto-switching to auto-detect. */
        private const val MANUAL_MISMATCH_AUTO_SWITCH_MS = 120_000L

        /** Channel ID for the persistent foreground tracking notification.
         *
         *  Android freezes a channel's sound, vibration and importance at
         *  creation — later edits are ignored for anyone who already has the
         *  channel — so each behaviour change needs a fresh ID.
         *
         *  v2 bump: the original `tracking_channel` was created with
         *  IMPORTANCE_LOW whenever the user disabled notification sounds,
         *  which makes Android render the shade entry in a compact form
         *  that hides the action row until the user expands it. Existing
         *  channels can't have their importance raised programmatically,
         *  so the only way to give every user a default-importance shade
         *  entry (Save / Discard visible inline) is to migrate to a fresh
         *  channel ID.
         *
         *  v3 bump: the channel is now unconditionally silent (see
         *  [ensureTrackingChannel]). Users who installed before this change
         *  already have v2 with a sound attached, and lowering it in place is
         *  not permitted, so they need the new ID to actually get silence. */
        private const val TRACKING_CHANNEL_ID = "tracking_channel_v3"

        /** Superseded tracking-channel IDs, deleted on channel setup so they stop
         *  cluttering the user's per-channel notification settings. */
        private val LEGACY_TRACKING_CHANNEL_IDS =
            listOf("tracking_channel", "tracking_channel_v2")

        /** Auto-dismiss the "Session discarded" notification after ~4 s. */
        private const val DISCARD_NOTIFICATION_TIMEOUT_MS = 4_000L

        /** ~15–20% typical overcount from summed GPS chords vs smooth path; tune in code if needed. */
        private const val GPS_PATH_DISTANCE_SCALE = 0.83

        /** Sustained below [SpeedThresholds.DRIVING_MIN] before allowing switch to walk/run/idle (ms). */
        private const val MOTOR_LOW_SPEED_EXIT_MS = 60_000L

        /** After this long without speed in the driving band, [MOTOR_LOW_SPEED_EXIT_MS] countdown applies. */
        private const val STICKY_RECENT_DRIVING_MS = 120_000L

        /** GPS fix is considered stale if it is older than this; frozen speed is zeroed. */
        private const val GPS_FIX_STALE_MS = 8_000L
        /** Zero the speed when raw GPS speed is identical for this many consecutive fixes (frozen chip). */
        private const val GPS_IDENTICAL_SPEED_COUNT = 3
        /** After sensors confirm STILL for this long, bypass sticky-motor hysteresis. */
        private const val STILL_MOTOR_OVERRIDE_MS = 10_000L
        /**
         * Distance from the idle anchor that counts as having travelled, resetting the
         * auto-stop clock. Comfortably above GPS scatter on a stationary device, and trivially
         * cleared by anything actually moving — 40 m is under 6 seconds of walking.
         */
        /**
         * Multiple of the recently sustained speed a single fix may claim before the physics
         * cap trims it. Generous - real acceleration and GPS timing jitter both need room -
         * while still an order of magnitude below a multipath teleport.
         */
        private const val PHYSICS_CAP_HEADROOM = 2.5f
        /**
         * Floor for the physics cap (m/s), so pulling away from a standstill is never clipped:
         * with an all-zero speed ring the ceiling would otherwise be zero. 8 m/s is about
         * 29 km/h, more than any vehicle covers in the first second of moving.
         */
        private const val PHYSICS_CAP_FLOOR_MPS = 8.0f
        /**
         * Physics-cap floor without vehicle context (m/s). Set at the running-speed cap, so any
         * genuine human pace passes untouched while a teleport claiming vehicle speeds on a
         * walk does not. A real drive raises the floor via [PHYSICS_CAP_FLOOR_MPS] as soon as
         * it has evidence, and the sustained-speed term lifts the ceiling well before then.
         */
        private const val PHYSICS_CAP_FLOOR_PEDESTRIAN_MPS = 4.5f
        /**
         * Upper bound (seconds) on the interval the physics cap will credit. A gap longer than
         * this means lost lock, not travel, and letting the ceiling scale with it made the cap
         * most permissive exactly where the data was worst. Distance across a genuine outage is
         * dead reckoning's responsibility, not the raw-geometry path's.
         */
        private const val PHYSICS_CAP_MAX_DT_SEC = 6.0
        private const val IDLE_TRAVEL_RADIUS_M = 75.0
        /** Anchor radius also scales with fix accuracy: an excursion inside the error circle is noise. */
        private const val IDLE_TRAVEL_ACCURACY_FACTOR = 2.0
        /** Consecutive 15 s polls that must all exceed the radius before it counts as travel. */
        private const val IDLE_TRAVEL_CONFIRM_POLLS = 2
        /** Steps needed before a corroboration window is judged. ~20–30 m of real walking. */
        private const val STEP_CORROB_MIN_STEPS = 30
        /**
         * Ground covered per step below which the step events are not gait. A real stride is
         * 0.6–0.8 m; this sits well under that so curves, GPS error and short pauses inside the
         * window can't produce a false verdict, while a shaken phone (≈0 m/step) fails clearly.
         */
        private const val STEP_CORROB_MIN_M_PER_STEP = 0.25
        /** A corroboration window with too few steps re-anchors after this long. */
        private const val STEP_CORROB_WINDOW_MS = 90_000L
        /** Linear acceleration (m/s²) above which live sensor motion contradicts a GPS hover verdict.
         *  Set well above SENSOR_STILL_ACCEL_THRESHOLD so idle engine vibration alone cannot clear it. */
        private const val HOVER_MOTION_CONTRADICTION_ACCEL = 0.8f
        /** A fix timestamp further than this from wall clock is not trusted as a time base. */
        private const val FIX_TIME_TRUST_WINDOW_MS = 30_000L
        /** Minimum Activity Recognition confidence (%) before an IN_VEHICLE reading is trusted. */
        private const val VEHICLE_RECOGNITION_MIN_CONFIDENCE = 75
        /** How long a confident IN_VEHICLE reading keeps vetoing the idle lock. Spans several
         *  10 s update cycles so a transient UNKNOWN at a traffic stop doesn't drop the veto. */
        private const val VEHICLE_RECOGNITION_TTL_MS = 90_000L
        /** Linear acceleration (m/s²) below which the phone is considered stationary (fallback for no-step-counter devices). */
        private const val SENSOR_STILL_ACCEL_THRESHOLD = 0.12f
        /**
         * Step count or accelerometer must confirm no motion for this long before locking
         * activity to IDLE.
         *
         * Used twice — as the "no steps seen for" window and as the lock dwell — so the true
         * cost of a false lock is two of these back to back. At the old 2 s that was 4 s, far
         * too eager for a gate whose stated job is rejecting *sustained* drift: any dip below
         * DRIVING_MIN at a junction or in traffic cleared it.
         */
        private const val SENSOR_IDLE_LOCK_MS = 9_000L

        /**
         * How long high speed must persist before we let a pedestrian activity
         * promote to a motor mode (DRIVING/EV/TRAIN/FLYING). Long enough to
         * filter typical GPS multipath spikes (1–2 ticks ≈ 1–2 s in urban
         * canyons), short enough not to delay legitimate vehicle starts —
         * a real car ride sustains driving-band speed for far longer than 4 s.
         */
        private const val PEDESTRIAN_TO_MOTOR_PROMOTE_MS = 4_000L

        /** Maximum physically plausible ground-vehicle acceleration (m/s²) between GPS fixes.
         *  Sports cars peak ~9 m/s²; indoor GPS can imply 10–15 m/s² in a single 1-second window. */
        private const val MAX_PHYSICAL_ACCEL_MPS2 = 12f

        /** Size of the raw-speed ring buffer fed to the GPS consistency gate. */
        private const val SPEED_BUF_SIZE = 8
        /** Minimum samples in the buffer before the consistency gate activates. */
        private const val SPEED_BUF_MIN_SAMPLES = 6
        /** Fraction of the speed buffer that must clear DRIVING_MIN to confirm settled motor travel.
         *  0.75 of 8 samples = 6, so two dips are tolerated but an oscillation is not. */
        private const val SPEED_BUF_MOTOR_FRACTION = 0.75f
        /** Per-step decrease tolerated while still calling the buffer a rising ramp (m/s). */
        private const val RAMP_TOLERANCE_MPS = 0.6f
        /** Total gain across the buffer required to call it a pull-away ramp rather than noise (m/s). */
        private const val RAMP_MIN_GAIN_MPS = 4.0f

        /** Radius (m) within which sustained GPS fixes are treated as the same parked spot —
         *  wider than typical chip noise (≈10–15 m) so genuine slow movement isn't false-flagged. */
        private const val HOVER_RADIUS_M = 30.0
        /** How long fixes must stay within HOVER_RADIUS_M before we call it "parked, not moving" —
         *  long enough that a red light or brief stop doesn't trigger it. */
        private const val HOVER_CONFIRM_MS = 90_000L

        /** Minimum altitude (m) required alongside high speed for FLYING classification. */
        private const val HIGH_ALTITUDE_M = 1000.0
        /** Minimum speed (m/s = 200 km/h) required alongside HIGH_ALTITUDE_M for FLYING. */
        private const val FLYING_MIN_SPEED_MPS = 200f / 3.6f
        /** Relaxed max-speed-change threshold during cold-start (smoothedSpeed < 1 m/s). */
        private const val COLD_START_MAX_SPEED_CHANGE = 15.0f

        /** Heading delta (degrees) beyond which a fix is counted as a jitter sample. */
        private const val HEADING_JITTER_THRESHOLD_DEG = 45f
        /** Consecutive jitter samples before motor promotion is blocked. */
        private const val HEADING_JITTER_BLOCK_COUNT = 4

        /** Activity-specific GPS path scale factors (overcount correction per activity). */
        private const val GPS_SCALE_WALKING = 0.78
        private const val GPS_SCALE_CYCLING = 0.88
        private const val GPS_SCALE_DRIVING = 0.88

        /** Elevation gain/loss deadband (m): change must exceed this before it counts, so per-fix
         *  altitude noise stops accumulating as phantom climb/descent. */
        private const val ELEVATION_MIN_DELTA_M = 1.0

        /** Rolling window over which instantaneous walking cadence is measured (ms). */
        private const val CADENCE_WINDOW_MS = 6_000L
        /** Minimum steps within [CADENCE_WINDOW_MS] before the cadence-derived speed ceiling is trusted. */
        private const val CADENCE_MIN_STEPS = 5
        /** Headroom on the cadence-implied walking speed before GPS is considered inflated. Stride
         *  length varies with pace, so leave slack — this catches ~2× inflation, not small errors. */
        private const val CADENCE_SPEED_HEADROOM = 1.4f
        /** Never cap walking speed below this (m/s ≈ 5 km/h) even if cadence average is dragged down. */
        private const val CADENCE_CEILING_FLOOR_MPS = 1.4f

        /** Window after an IN_VEHICLE cold-start during which the promotion latency is halved. */
        private const val COLD_START_VEHICLE_WINDOW_MS = 25_000L

        /** GPS warm-up window at the start of every session: chips most often emit a spurious
         *  "jump" fix (huge implied speed, e.g. 100 km/h while standing still indoors) during
         *  this acquisition phase. Block motor-mode promotion entirely until it passes so one
         *  bad fix can't seed DRIVING and then get stuck via sticky-motor hysteresis. */
        private const val GPS_WARMUP_MS = 15_000L

        /**
         * If the step counter ticked within this window, treat the user as
         * actively on foot for the purposes of [applyPedestrianGpsSanityCap],
         * regardless of long-term session cadence. Catches the first urban-
         * canyon GPS spike before the 10-step warm-up of the previous logic
         * would have allowed any cap to take effect.
         */
        private const val PEDESTRIAN_RECENT_STEP_WINDOW_MS = 5_000L
        /** Extended step window applied while an indoor GPS transition is active. */
        private const val INDOOR_STEP_WINDOW_MS = 15_000L

        /**
         * Minimum session-step count before the cadence-based pedestrian cap
         * is allowed to kick in via the session-SPM path. Recent-step
         * detection has its own faster path; this is just the floor for the
         * "long-term cadence" branch.
         */
        private const val PEDESTRIAN_MIN_STEPS_FOR_CAP = 4

        /** GPS accuracy (m) considered "good outdoors" for indoor-transition detection. */
        private const val INDOOR_ACCURACY_GOOD_M = 15f
        /** GPS accuracy (m) considered "degraded / likely indoors". */
        private const val INDOOR_ACCURACY_DEGRADED_M = 40f
        /** Number of recent accuracy readings kept for degradation detection. */
        private const val INDOOR_ACCURACY_HISTORY_SIZE = 5
        /** How long the indoor-transition cautious mode stays active after detection (ms). */
        private const val INDOOR_TRANSITION_TTL_MS = 30_000L
        /** Tightened motor-promotion accuracy gate while indoor transition is active (m). */
        private const val INDOOR_MOTOR_ACCURACY_GATE_M = 15f
        /**
         * Consecutive fixes at [INDOOR_ACCURACY_DEGRADED_M] or worse that arm indoor mode on their
         * own. The transition detector needs a good→bad accuracy edge, which a session that *starts*
         * indoors never produces — there is no clean outdoor fix to degrade from. Sustained poor
         * accuracy is the same evidence arriving without the edge.
         */
        private const val INDOOR_SUSTAINED_POOR_FIXES = 5

        /** Window of recent positions used to cross-check a claimed speed against real displacement. */
        private const val DISPLACEMENT_WINDOW_MS = 12_000L
        /** Minimum span (s) the window must cover before the displacement ratio means anything. */
        private const val DISPLACEMENT_MIN_SPAN_SEC = 6.0
        /**
         * Headroom on the displacement-implied speed. Net displacement legitimately falls short of
         * distance travelled — turns, stop-start traffic — so leave slack. At 1.8 a vehicle only has
         * to make good roughly 55 % of the straight-line distance its speed claims.
         */
        private const val DISPLACEMENT_SPEED_HEADROOM = 1.8f
        /** The displacement cap never clamps below this (m/s ≈ 8 km/h), so it can't touch pedestrians. */
        private const val DISPLACEMENT_CAP_FLOOR_MPS = 2.2f

        /**
         * Clean fixes required before the speed EMA may be *seeded* at or above
         * [SpeedThresholds.DRIVING_MIN]. Matches what the classifier demands before it will call
         * something DRIVING: whatever seeds the EMA sets the outlier window for every tick after
         * it, so a phantom seed holds far longer than the spike that produced it.
         */
        private const val MOTOR_SPEED_SEED_CLEAN_FIXES = 2
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
