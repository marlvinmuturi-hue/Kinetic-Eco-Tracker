package Kinetic_Eco.Tracker.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.services.TrackingService
import Kinetic_Eco.Tracker.services.SessionManager
import Kinetic_Eco.Tracker.services.FirestoreSessionService
import Kinetic_Eco.Tracker.services.LocationService
import Kinetic_Eco.Tracker.util.adjustStatsForSimplifiedPath
import kotlinx.coroutines.Job

class TrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager = SessionManager(application)
    private val locationService = LocationService(application)
    private val firestoreService = FirestoreSessionService.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var trackingService: TrackingService? = null
    private var isBound = false

    /** Cancels when service disconnects or reconnects — avoids duplicate collectors and stale activity after auto-stop. */
    private var serviceCollectJob: Job? = null

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound = _isServiceBound.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TrackingService.LocalBinder
            val svc = binder.getService()
            trackingService = svc
            isBound = true
            _isServiceBound.value = true
            if (gpsWarmUpJob?.isActive == true) svc.startSensorWarmUp()

            serviceCollectJob?.cancel()
            serviceCollectJob = viewModelScope.launch {
                coroutineScope {
                    launch { svc.isTracking.collect { _isTracking.value = it } }
                    launch { svc.currentSpeed.collect { _currentSpeed.value = it } }
                    launch { svc.currentActivity.collect { _currentActivity.value = it } }
                    launch { svc.sessionDuration.collect { _sessionDuration.value = it } }
                    launch { svc.sessionDistance.collect { _sessionDistance.value = it } }
                    launch { svc.sessionSteps.collect { _sessionSteps.value = it } }
                    launch { svc.sessionStats.collect { _sessionStats.value = it } }
                    launch { svc.currentPosition.collect { _currentPosition.value = it } }
                    launch { svc.manualActivityMode.collect { _manualActivityMode.value = it } }
                    launch { svc.leanActivityHint.collect { _leanActivityHint.value = it } }
                    launch { svc.evConfirmPrompt.collect { _evConfirmPrompt.value = it } }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceCollectJob?.cancel()
            serviceCollectJob = null
            trackingService = null
            isBound = false
            _isServiceBound.value = false
            // Auto-stop / stopSelf() does not go through resetSession() — clear stale driving/walking UI state
            _isTracking.value = false
            _currentSpeed.value = 0f
            _currentActivity.value = ActivityType.IDLE
            _manualActivityMode.value = null
            _leanActivityHint.value = null
            _evConfirmPrompt.value = false
        }
    }

    // State
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()
    
    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()
    
    private val _currentActivity = MutableStateFlow<ActivityType>(ActivityType.IDLE)
    val currentActivity: StateFlow<ActivityType> = _currentActivity.asStateFlow()
    
    private val _manualActivityMode = MutableStateFlow<ActivityType?>(null)
    val manualActivityMode: StateFlow<ActivityType?> = _manualActivityMode.asStateFlow()

    private val _leanActivityHint = MutableStateFlow<ActivityType?>(null)
    val leanActivityHint: StateFlow<ActivityType?> = _leanActivityHint.asStateFlow()

    private val _evConfirmPrompt = MutableStateFlow(false)
    val evConfirmPrompt: StateFlow<Boolean> = _evConfirmPrompt.asStateFlow()

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
    
    /** Position from GPS warm-up (shown before tracking starts so user sees lock + altitude) */
    private val _warmUpPosition = MutableStateFlow<GeoPosition?>(null)
    val warmUpPosition: StateFlow<GeoPosition?> = _warmUpPosition.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private var gpsWarmUpJob: Job? = null
    
    /**
     * Start GPS warm-up when Tracker screen is shown. Keeps GPS (incl. altitude) ready
     * so first fix is fast when user hits Start tracking.
     * Also pre-feeds accelerometer to pattern analyzer for faster activity detection.
     */
    fun startGpsWarmUp() {
        if (gpsWarmUpJob?.isActive == true) return
        if (!locationService.hasLocationPermission()) return
        if (_isTracking.value) return
        
        gpsWarmUpJob = viewModelScope.launch {
            locationService.getLocationUpdatesForWarmUp().collect { position ->
                _warmUpPosition.value = position
            }
        }
        trackingService?.startSensorWarmUp()
        android.util.Log.d(TAG, "GPS warm-up started")
    }
    
    /**
     * Stop GPS warm-up (e.g. when user navigates away or starts tracking).
     */
    fun stopGpsWarmUp() {
        gpsWarmUpJob?.cancel()
        gpsWarmUpJob = null
        trackingService?.stopSensorWarmUp()
        android.util.Log.d(TAG, "GPS warm-up stopped")
    }
    
    init {
        val intent = Intent(application, TrackingService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    fun startTracking() {
        stopGpsWarmUp()
        val intent = Intent(getApplication(), TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }
    
    fun stopTracking() {
        trackingService?.stopTracking()
    }
    
    fun pauseTracking() {
        // Pausing is not explicitly handled by TrackingService action yet, 
        // but it effectively stops GPS updates if we want to implement it.
        // For now, stopTracking is used.
        trackingService?.stopTracking()
    }
    
    /**
     * Stop and save session - runs in viewModelScope so it survives composition changes.
     * Use this from UI to avoid coroutine cancellation when dialog/screen recomposes.
     */
    fun stopAndSaveSessionAsync(userId: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                stopAndSaveSession(userId)
                onComplete(Result.success(Unit))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ stopAndSaveSessionAsync failed", e)
                onComplete(Result.failure(e))
            }
        }
    }

    suspend fun stopAndSaveSession(userId: String) {
        android.util.Log.d(TAG, "💾 ===== stopAndSaveSession STARTED =====")
        android.util.Log.d(TAG, "💾 User ID: '$userId'")
        
        if (userId.isEmpty()) {
            android.util.Log.e(TAG, "❌ ERROR: Cannot save session with empty userId!")
            trackingService?.stopTracking()
            trackingService?.resetSession()
            _sessionSteps.value = 0
            _sessionDuration.value = 0L
            _sessionDistance.value = 0.0
            _sessionStats.value = SessionStats()
            _currentActivity.value = ActivityType.IDLE
            _currentSpeed.value = 0f
            _manualActivityMode.value = null
            return
        }
        
        val stats = _sessionStats.value
        // Get session start time BEFORE stopping - used for correct session date
        val sessionStartMs = trackingService?.getSessionStartTimeMs() ?: 0L
        android.util.Log.d(TAG, "💾 Session stats retrieved:")
        android.util.Log.d(TAG, "💾   - Distance: ${stats.totalDistance}m")
        android.util.Log.d(TAG, "💾   - Duration: ${stats.totalDuration}s")
        android.util.Log.d(TAG, "💾   - Session start: $sessionStartMs")
        android.util.Log.d(TAG, "💾   - Calories: ${stats.caloriesBurned}")
        android.util.Log.d(TAG, "💾   - CO2 Emissions: ${stats.co2Emissions}")
        android.util.Log.d(TAG, "💾   - CO2 Conserved: ${stats.co2Conserved}")
        android.util.Log.d(TAG, "💾   - Total Steps: ${stats.totalSteps}")
        android.util.Log.d(TAG, "💾   - Breakdown size: ${stats.breakdown.size}")
        
        try {
            // Merge route path, segments, and accelerometer samples from TrackingService for persistence
            val routePath = trackingService?.getRoutePath() ?: emptyList()
            val accelSamples = trackingService?.getAccelerometerSamples() ?: emptyList()
            val segments = trackingService?.getFinalSegments() ?: emptyList()
            val adjustedStats = adjustStatsForSimplifiedPath(stats, routePath)
            val statsWithRoute = adjustedStats.copy(routePath = routePath, segments = segments)
            android.util.Log.d(TAG, "💾 Calling sessionManager.saveSession...")
            val sessionId = sessionManager.saveSession(userId, statsWithRoute, sessionStartMs, accelSamples)
            android.util.Log.d(TAG, "✅ ===== SESSION SAVED SUCCESSFULLY =====")
            android.util.Log.d(TAG, "✅ Session ID: $sessionId")
            trackingService?.showSessionCompleteNotification(adjustedStats)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ ===== ERROR SAVING SESSION =====")
            android.util.Log.e(TAG, "❌ Exception type: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "❌ Error message: ${e.message}")
            android.util.Log.e(TAG, "❌ Stack trace:", e)
            // Re-throw to let caller handle
            throw e
        } finally {
            android.util.Log.d(TAG, "💾 Stopping tracking service...")
            trackingService?.stopTracking()
            android.util.Log.d(TAG, "💾 Resetting session data...")
            trackingService?.resetSession()
            // Explicitly reset ViewModel state so UI shows 0 immediately
            // (service may be destroyed before reset propagates via flow)
            _sessionSteps.value = 0
            _sessionDuration.value = 0L
            _sessionDistance.value = 0.0
            _sessionStats.value = SessionStats()
            _currentActivity.value = ActivityType.IDLE
            _currentSpeed.value = 0f
            _manualActivityMode.value = null
            _leanActivityHint.value = null
            _evConfirmPrompt.value = false
            android.util.Log.d(TAG, "💾 ===== stopAndSaveSession COMPLETED =====")
        }
    }
    
    fun setManualActivityMode(activity: ActivityType?) {
        trackingService?.setManualActivityMode(activity)
    }
    
    fun resetSession() {
        trackingService?.resetSession()
        // Explicitly reset ViewModel state so UI shows 0 immediately
        _sessionSteps.value = 0
        _sessionDuration.value = 0L
        _sessionDistance.value = 0.0
        _sessionStats.value = SessionStats()
        _currentActivity.value = ActivityType.IDLE
        _currentSpeed.value = 0f
        _manualActivityMode.value = null
        _leanActivityHint.value = null
        _evConfirmPrompt.value = false
    }
    
    fun dismissLeanActivityHint() {
        trackingService?.dismissLeanActivityHint()
    }

    fun dismissEvConfirmPrompt() {
        trackingService?.dismissEvConfirmPrompt()
        _evConfirmPrompt.value = false
    }

    /**
     * Reload physical profile in the tracking service
     * Call this after the user updates their profile in settings
     */
    fun reloadPhysicalProfile() {
        trackingService?.reloadPhysicalProfile()
    }

    fun reloadVehicleProfile() {
        trackingService?.reloadVehicleProfile()
    }

    fun getSessionStats(): SessionStats = _sessionStats.value

    /**
     * Sync all local Room database sessions to Firestore for cloud AI analysis
     */
    suspend fun syncSessionsToFirestore(): Result<Int> {
        return try {
            android.util.Log.d(TAG, "🔄 Starting session sync to Firestore...")
            
            val userId = auth.currentUser?.uid
            if (userId == null) {
                android.util.Log.e(TAG, "❌ No authenticated user")
                return Result.failure(Exception("User not authenticated"))
            }
            
            android.util.Log.d(TAG, "📱 Getting sessions for user: $userId")
            
            // Get all sessions from Room database (use first() to get single emission)
            val sessions = sessionManager.getAllSessions(userId).first()
            
            android.util.Log.d(TAG, "📊 Found ${sessions.size} sessions in Room database")
            
            if (sessions.isEmpty()) {
                android.util.Log.w(TAG, "⚠️ No sessions found to sync")
                return Result.failure(Exception("No sessions found to sync"))
            }
            
            android.util.Log.d(TAG, "☁️ Starting batch sync of ${sessions.size} sessions...")
            
            // Batch sync to Firestore
            val result = firestoreService.batchSyncSessions(sessions)
            
            result.onSuccess { count ->
                android.util.Log.d(TAG, "✅ Sync completed: $count sessions uploaded")
            }.onFailure { error ->
                android.util.Log.e(TAG, "❌ Sync failed: ${error.message}", error)
            }
            
            result
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Exception during sync: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    companion object {
        private const val TAG = "TrackerViewModel"
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
