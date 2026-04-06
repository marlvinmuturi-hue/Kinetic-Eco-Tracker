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
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.filters.KalmanFilter
import Kinetic_Eco.Tracker.filters.FlightTracker
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.util.adjustStatsForSimplifiedPath

class TrackingService : LifecycleService() {
    private lateinit var locationService: LocationService
    private lateinit var sensorService: SensorService
    private lateinit var sessionManager: SessionManager
    private lateinit var userPrefsManager: UserPreferencesManager
    private var userPhysicalProfile: UserPhysicalProfile? = null
    
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

    /** Seconds after tracking start where we use relaxed debounce (requiredConsistency=1) for faster convergence */
    private val STARTUP_DEBOUNCE_SECONDS = 10

    // Auto-stop on idle: when we transitioned to IDLE
    private var idleStartTimeMs: Long = 0
    
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
        ActivityType.TRAIN to 97.0f,            // 350 km/h (high-speed rail)
        ActivityType.DRIVING to 50.0f,          // 180 km/h (reasonable max)
        ActivityType.ELECTRIC_VEHICLE to 50.0f, // 180 km/h
        ActivityType.FLYING to 333.0f           // 1200 km/h (allow for high cruise speeds)
    )
    
    private val MAX_SPEED_CHANGE = 10.0f // Max 10 m/s (36 km/h) change per update (ground)
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
    private val STEP_BASED_GAP_THRESHOLD_MS = 3000L    // Use steps after 3s without GPS distance
    private var lastGpsDistanceUpdateMs = 0L
    private val STEP_LENGTH_WALKING = 0.75  // meters per step (GPS fresh)
    private val STEP_LENGTH_RUNNING = 1.0   // meters per step
    /** Slightly conservative when GPS distance is stale (reduces GPS+step inflation). */
    private val STEP_LENGTH_WALKING_STALE = 0.68

    /** Steps counted at last GPS distance increment — caps GPS wiggles vs step-based distance. */
    private var stepCountAtLastGps = 0
    private val MAX_ACCURACY_METERS_FOR_DISTANCE = 25f
    private val WALKING_GPS_MIN_SEGMENT_M = 1.5
    private val CAP_WALKING_SPEED_MPS = 2.2
    private val CAP_RUNNING_SPEED_MPS = 4.5
    private val STEP_GPS_BLEND_SLACK_M = 10.0

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
        
        startTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> startTracking()
            ACTION_STOP_TRACKING -> stopTracking()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startTracking() {
        if (_isTracking.value) return

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

        _isTracking.value = true
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
        startingAltitude = null
        stoppingAltitude = null
        minAltitude = null
        maxAltitude = null
        deadReckonStartMs = 0
        lastGpsDistanceUpdateMs = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = createNotification()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }

        locationCollectJob = lifecycleScope.launch {
            // Use 100m accuracy threshold so route populates in marginal GPS (urban, indoor start, trees)
            // Stricter 30–50m can reject all fixes and leave map empty (world view)
            locationService.getLocationUpdates(maxAccuracy = 100f).collect { position ->
                updateLocation(position)
            }
        }

        sensorCollectJob = lifecycleScope.launch {
            sensorService.getSensorUpdates().collect { sensorData ->
                updateSensors(sensorData)
            }
        }

        // Auto-stop on idle: check every 30 seconds
        idleCheckJob = lifecycleScope.launch {
            while (isActive && _isTracking.value) {
                delay(30_000)
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

        // Dead reckoning: extrapolate distance when GPS drops (flying, tunnels)
        deadReckoningJob = lifecycleScope.launch {
            while (isActive && _isTracking.value) {
                delay(2_000)
                if (!_isTracking.value) break
                val now = System.currentTimeMillis()
                val gapMs = now - lastUpdateTime
                if (gapMs < DEAD_RECKON_GAP_THRESHOLD_MS || lastUpdateTime == 0L) continue
                val activity = _currentActivity.value
                val movingActivity = activity == ActivityType.FLYING ||
                    activity == ActivityType.DRIVING ||
                    activity == ActivityType.ELECTRIC_VEHICLE ||
                    activity == ActivityType.TRAIN ||
                    activity == ActivityType.CYCLING
                if (!movingActivity || smoothedSpeed < 0.5f) continue
                if (deadReckonStartMs == 0L) deadReckonStartMs = lastUpdateTime
                val elapsedSinceStart = now - deadReckonStartMs
                if (elapsedSinceStart > DEAD_RECKON_MAX_DURATION_MS) continue
                val deltaSec = 2.0  // 2s since last tick
                val distanceDelta = smoothedSpeed * deltaSec
                _sessionDistance.value += distanceDelta
                updateStats(distanceDelta, activity, smoothedSpeed.toDouble(), 0.0, 0.0)
            }
        }
    }

    fun stopTracking() {
        idleCheckJob?.cancel()
        idleCheckJob = null
        deadReckoningJob?.cancel()
        deadReckoningJob = null
        _isTracking.value = false
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

    /** Auto-stop and save session when idle timeout is reached. */
    private fun performAutoStopAndSave() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        val stats = _sessionStats.value
        val startMs = sessionStartTimeMs
        val routePath = getRoutePath()
        val accelSamples = getAccelerometerSamples()
        val adjustedStats = adjustStatsForSimplifiedPath(stats, routePath)

        stopTracking()

        if (userId != null && userId.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val statsWithRoute = adjustedStats.copy(routePath = routePath)
                    sessionManager.saveSession(userId, statsWithRoute, startMs, accelSamples)
                    android.util.Log.d("TrackingService", "Auto-stop: session saved successfully")
                } catch (e: Exception) {
                    android.util.Log.e("TrackingService", "Auto-stop: failed to save session", e)
                }
            }
        }
    }

    fun setManualActivityMode(activity: ActivityType?) {
        _manualActivityMode.value = activity
        if (activity != null) {
            _currentActivity.value = activity
            // Reset persistence tracking when manually selecting an activity
            activityStartTime = System.currentTimeMillis()
            activityDurationSeconds = 0
            activityHistory.clear()
        }
    }
    
    /**
     * Reload user's physical profile from preferences
     * Call this after the user updates their profile in settings
     */
    fun reloadPhysicalProfile() {
        userPhysicalProfile = userPrefsManager.loadPhysicalProfile()
        android.util.Log.d("TrackingService", "Physical profile reloaded: $userPhysicalProfile")
    }

    fun resetSession() {
        _sessionDuration.value = 0L
        _sessionDistance.value = 0.0
        _sessionSteps.value = 0
        _sessionStats.value = SessionStats()
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
        accelerometerSamples.clear()
        lastAccelSampleTimeMs = 0
        kalmanFilter.reset()
        flightTracker.reset()
        
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
                list.add(RoutePoint(pos.latitude, pos.longitude, _currentActivity.value))
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
        refinedActivity: ActivityType,
        now: Long
    ) {
        val isFirstPoint = pathPoints.isEmpty()
        val positionChanged = isFirstPoint ||
            pathPoints.last().latitude != lat ||
            pathPoints.last().longitude != lon
        val intervalElapsed = isFirstPoint || (now - lastPathRecordTime) >= PATH_RECORD_INTERVAL_MS
        if (positionChanged && intervalElapsed) {
            pathPoints.add(RoutePoint(lat, lon, refinedActivity))
            lastPathRecordTime = now
            // Update sessionStats with route so live view shows during tracking
            _sessionStats.value = _sessionStats.value.copy(routePath = getRoutePath())
        }
    }

    private fun updateSensors(data: SensorData) {
        currentAcceleration = data.acceleration
        currentRotationRate = data.rotationRate
        
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
            
            val currentActivity = _currentActivity.value
            if (currentActivity == ActivityType.WALKING || currentActivity == ActivityType.RUNNING) {
                _sessionSteps.value += stepDelta
                updateStepsInStats(stepDelta, currentActivity)
                // Step-based distance fallback when GPS hasn't updated (urban/indoor)
                val gpsStale = lastUpdateTime > 0 && (now - lastGpsDistanceUpdateMs) > STEP_BASED_GAP_THRESHOLD_MS
                if (gpsStale) {
                    val stepLength = if (currentActivity == ActivityType.RUNNING) {
                        STEP_LENGTH_RUNNING
                    } else {
                        STEP_LENGTH_WALKING_STALE
                    }
                    val distanceDelta = stepDelta * stepLength
                    _sessionDistance.value += distanceDelta
                    stepCountAtLastGps = lastStepCount
                    val estSpeed = getActivitySpeed(currentActivity).toDouble()
                    updateStats(distanceDelta, currentActivity, estSpeed, 0.0, 0.0)
                }
                android.util.Log.d("TrackingService", 
                    "Steps: +$stepDelta (Total: ${_sessionSteps.value}) - Activity: $currentActivity")
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
        
        // Flying: allow larger speed changes for acceleration during takeoff/landing
        val maxChange = if (activity == ActivityType.FLYING) {
            MAX_SPEED_CHANGE_FLYING
        } else {
            MAX_SPEED_CHANGE
        }
        
        // Reject outliers (sudden massive speed changes)
        val speedChange = Math.abs(newSpeed - smoothedSpeed)
        if (speedChange > maxChange) {
            android.util.Log.d("TrackingService", 
                "Rejecting speed outlier: ${newSpeed * 3.6f} km/h (change: ${speedChange * 3.6f} km/h, max: ${maxChange * 3.6f} km/h)")
            return smoothedSpeed // Keep previous speed
        }
        
        // Dynamic alpha: near-instant for ground (user prefers responsiveness over smoothness)
        // Flying keeps 0.4 for stability at high speed
        val alpha = if (activity == ActivityType.FLYING) 0.4f else 0.95f
        smoothedSpeed = (alpha * newSpeed) + ((1 - alpha) * smoothedSpeed)
        return smoothedSpeed
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
        sessionTopSpeedMps = maxOf(sessionTopSpeedMps, speed.toDouble())
        
        // Get motion pattern for smart classification
        val motionPattern = sensorService.getMotionPattern()
        val hasAccelerometer = sensorService.hasAccelerometer()
        
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
                ActivityType.CYCLING, ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE, ActivityType.TRAIN -> {
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
        }
        
        updateActivityWithDebounce(refinedActivity)
        
        // Log flight phase for debugging
        if (refinedActivity == ActivityType.FLYING) {
            android.util.Log.d("TrackingService", 
                "Flying: Phase=${flightState.phase}, Alt=${currentAltitude.toInt()}m, Speed=${(speed * 3.6f).toInt()} km/h")
        }
        
        if (lastPosition != null) {
            // distanceDelta already computed above for speed fallback
            
            // Calculate elevation changes
            var elevationGainDelta = 0.0
            var elevationLossDelta = 0.0
            val elevationDelta = currentAltitude - previousAltitude
            if (elevationDelta > 0) {
                elevationGainDelta = elevationDelta
                totalElevationGain += elevationDelta
            } else if (elevationDelta < 0) {
                elevationLossDelta = Math.abs(elevationDelta)
                totalElevationLoss += Math.abs(elevationDelta)
            }
            
            val isActuallyMoving = speed > 0.15f || currentAcceleration > 0.2f
            val baseMeet = distanceDelta >= 1.0 || speed >= SpeedThresholds.WALKING_MIN ||
                (distanceDelta >= 0.5 && speed > 0.15f)
            val meetsDistanceThreshold = if (refinedActivity == ActivityType.WALKING) {
                baseMeet && (
                    distanceDelta >= WALKING_GPS_MIN_SEGMENT_M ||
                        speed >= SpeedThresholds.WALKING_MIN ||
                        (distanceDelta >= 0.85 && speed > 0.18f)
                    )
            } else {
                baseMeet
            }

            var dd = distanceDelta
            if (refinedActivity != ActivityType.FLYING && position.accuracy > MAX_ACCURACY_METERS_FOR_DISTANCE) {
                dd = 0.0
            } else if (refinedActivity == ActivityType.WALKING && dd > 0) {
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                val stepCap = stepsThisInterval * STEP_LENGTH_WALKING + STEP_GPS_BLEND_SLACK_M
                val speedCap = CAP_WALKING_SPEED_MPS * timeDelta
                dd = minOf(dd, stepCap, speedCap)
            } else if (refinedActivity == ActivityType.RUNNING && dd > 0) {
                val stepsThisInterval = (lastStepCount - stepCountAtLastGps).coerceAtLeast(0)
                val stepCap = stepsThisInterval * STEP_LENGTH_RUNNING + STEP_GPS_BLEND_SLACK_M
                val speedCap = CAP_RUNNING_SPEED_MPS * timeDelta
                dd = minOf(dd, stepCap, speedCap)
            }

            if (isActuallyMoving && meetsDistanceThreshold && dd > 0) {
                _sessionDistance.value += dd
                lastGpsDistanceUpdateMs = now
                stepCountAtLastGps = lastStepCount
                updateStats(dd, refinedActivity, speed.toDouble(), elevationGainDelta, elevationLossDelta)
                checkAndShowKmMilestoneNotification()
                // Record route point on movement (in addition to time interval)
                recordPathPointIfNeeded(filtered.latitude, filtered.longitude, refinedActivity, now)
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
        recordPathPointIfNeeded(filtered.latitude, filtered.longitude, refinedActivity, now)
        
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
    
    /** Upper bound (excl) and lower bound (incl) in m/s for each activity. Android merges cycling into running. TRAIN is manual-only. */
    private fun getActivitySpeedBounds(activity: ActivityType): Pair<Float, Float>? = when (activity) {
        ActivityType.IDLE -> Pair(0f, SpeedThresholds.WALKING_MIN.toFloat())           // [0, 0.5)
        ActivityType.WALKING -> Pair(SpeedThresholds.WALKING_MIN.toFloat(), SpeedThresholds.RUNNING_MIN.toFloat())  // [0.5, 2)
        ActivityType.RUNNING -> Pair(SpeedThresholds.RUNNING_MIN.toFloat(), SpeedThresholds.DRIVING_MIN.toFloat()) // [2, 5) = 7–18 km/h
        ActivityType.CYCLING -> Pair(SpeedThresholds.CYCLING_MIN.toFloat(), SpeedThresholds.DRIVING_MIN.toFloat()) // [4, 5) m/s = 14–18 km/h
        ActivityType.TRAIN -> Pair(5f, 97f)           // [18, 350) km/h - regional to high-speed rail (manual only)
        ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE -> Pair(SpeedThresholds.DRIVING_MIN.toFloat(), SpeedThresholds.FLYING_MIN.toFloat())  // [5, 50) = 18–180 km/h
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
            Pair(ActivityType.FLYING, ActivityType.WALKING),
            Pair(ActivityType.FLYING, ActivityType.RUNNING),
            Pair(ActivityType.FLYING, ActivityType.CYCLING),
            
            // Can't jump from cycling to driving without a transition
            Pair(ActivityType.CYCLING, ActivityType.DRIVING),
            Pair(ActivityType.CYCLING, ActivityType.ELECTRIC_VEHICLE),
            Pair(ActivityType.CYCLING, ActivityType.TRAIN),
            
            // Can't go from running to driving/cycling without stopping
            Pair(ActivityType.RUNNING, ActivityType.DRIVING),
            Pair(ActivityType.RUNNING, ActivityType.ELECTRIC_VEHICLE),
            Pair(ActivityType.RUNNING, ActivityType.CYCLING),
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
            ActivityType.TRAIN -> 27.8f       // ~100 km/h (typical rail)
            ActivityType.DRIVING -> 15f       // ~54 km/h
            ActivityType.ELECTRIC_VEHICLE -> 15f
            ActivityType.FLYING -> 150f       // ~540 km/h
        }
    }

    private fun updateActivityWithDebounce(activity: ActivityType) {
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
                (currentActivity == ActivityType.DRIVING && mostCommon == ActivityType.RUNNING)
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
        
        val (emissions, conserved) = sessionManager.calculateCO2(distanceDelta, activity)
        
        // Use advanced calorie calculation with speed and elevation
        val caloriesDelta = sessionManager.calculateCalories(
            duration = 1L,
            activity = activity,
            speedMps = if (speedMps > 0) speedMps else null,
            elevationGain = elevationGainDelta,
            elevationLoss = elevationLossDelta,
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
        val channelId = "tracking_channel"
        val soundsEnabled = userPrefsManager.getNotificationSoundsEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (soundsEnabled) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(
                channelId,
                "Kinetic Tracking",
                importance
            ).apply {
                description = "Shows real-time tracking status"
                setShowBadge(false)
                enableVibration(soundsEnabled)
                setSound(if (soundsEnabled) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon = getAppIconBitmap()
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kinetic Tracking")
            .setContentText("Recording your activity...")
            .setSmallIcon(R.drawable.ic_notification)
            .apply { largeIcon?.let { setLargeIcon(it) } }
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
        if (!soundsEnabled) {
            builder.setSilent(true)
        }
        return builder.build()
    }

    /**
     * Check if we've crossed a distance milestone (1 km in metric, 1 mile in imperial).
     * Always log for session summary; show notification only if user has enabled distance alerts.
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
        
        if (userPrefsManager.getDistanceAlertsEnabled()) {
            showDistanceMilestoneNotification(currentSegment, secondsForThisSegment)
        }
        
        lastNotifiedKm = currentSegment
        durationAtLastKm = currentDurationSec
    }

    /**
     * Show a one-off notification for a completed distance unit (km or mile based on preference).
     */
    private fun showDistanceMilestoneNotification(segment: Int, secondsForSegment: Long) {
        val unitLabel = userPrefsManager.getDistanceUnitLabel()
        val soundsEnabled = userPrefsManager.getNotificationSoundsEnabled()
        val channelId = "distance_milestone_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Distance Milestones",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when you complete each km (metric) or mile (imperial) during tracking"
                setShowBadge(true)
                enableVibration(soundsEnabled)
                setSound(if (soundsEnabled) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val minutes = secondsForSegment / 60
        val secs = secondsForSegment % 60
        val timeStr = String.format("%d:%02d", minutes, secs)
        val title = "$segment $unitLabel completed"
        val body = "This $unitLabel in $timeStr"

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
        val notification = builder.build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(KM_MILESTONE_NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        if (!_isTracking.value) return
        
        val metersPerUnit = userPrefsManager.getMetersPerUnit()
        val unitLabel = userPrefsManager.getDistanceUnitLabel()
        val distanceInUnits = _sessionDistance.value / metersPerUnit
        val durationMin = _sessionDuration.value / 60
        val contentText = String.format("%.2f %s • %d min • %s", distanceInUnits, unitLabel, durationMin, _currentActivity.value.name.lowercase())

        val largeIcon = getAppIconBitmap()
        val builder = NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("Kinetic Tracking")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .apply { largeIcon?.let { setLargeIcon(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
        if (!userPrefsManager.getNotificationSoundsEnabled()) {
            builder.setSilent(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
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
        const val KM_MILESTONE_NOTIFICATION_ID = 102
        const val SESSION_SUMMARY_NOTIFICATION_ID = 103
        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"
    }
}
