package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.KmMilestone
import Kinetic_Eco.Tracker.data.database.*
import java.text.SimpleDateFormat
import java.util.*

class SessionManager(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val firestoreService = FirestoreSessionService.getInstance()
    
    // Scope for fire-and-forget Firestore sync - does not block save completion when offline
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Save session with correct date based on when it started (not when it was saved).
     * @param sessionStartTimeMs When the session started (from TrackingService.getSessionStartTimeMs()). If 0, uses current time.
     * @param accelerometerSamples Optional accelerometer samples (1/sec) for Firestore analytics.
     */
    suspend fun saveSession(
        userId: String,
        stats: SessionStats,
        sessionStartTimeMs: Long = 0,
        accelerometerSamples: List<AccelerometerSample> = emptyList()
    ): String {
        Log.d(TAG, "💾 saveSession called")
        Log.d(TAG, "💾 User ID: $userId")
        Log.d(TAG, "💾 Stats: distance=${stats.totalDistance}m, duration=${stats.totalDuration}s, calories=${stats.caloriesBurned}")
        
        val sessionId = UUID.randomUUID().toString()
        // Use session start time for date so sessions get correct date (e.g. started 11pm, saved 12am next day)
        val date = if (sessionStartTimeMs > 0) {
            dateFormat.format(Date(sessionStartTimeMs))
        } else {
            dateFormat.format(Date())
        }
        
        Log.d(TAG, "💾 Session ID: $sessionId")
        Log.d(TAG, "💾 Date: $date")
        
        val breakdownEntity = stats.breakdown.mapValues { (_, breakdown) ->
            ActivityBreakdownEntity(
                time = breakdown.time,
                distance = breakdown.distance,
                steps = breakdown.steps
            )
        }
        
        Log.d(TAG, "💾 Breakdown: ${stats.breakdown.size} activities, total steps: ${stats.totalSteps}")
        
        val kmMilestonesJson = com.google.gson.Gson().toJson(stats.kmMilestones)
        
        val sessionEntity = SessionEntity(
            id = sessionId,
            userId = userId,
            date = date,
            totalDuration = stats.totalDuration,
            totalDistance = stats.totalDistance,
            caloriesBurned = stats.caloriesBurned,
            co2Emissions = stats.co2Emissions,
            co2Conserved = stats.co2Conserved,
            totalSteps = stats.totalSteps,
            elevationGain = stats.elevationGain,
            elevationLoss = stats.elevationLoss,
            startingAltitude = stats.startingAltitude,
            stoppingAltitude = stats.stoppingAltitude,
            minAltitude = stats.minAltitude,
            maxAltitude = stats.maxAltitude,
            topSpeedMps = stats.topSpeedMps,
            kmMilestonesJson = kmMilestonesJson,
            routePath = stats.routePath,
            createdAt = System.currentTimeMillis(),
            breakdown = breakdownEntity
        )
        
        Log.d(TAG, "💾 Saving to Room database...")
        
        try {
            // Save to Room database (local)
            sessionDao.insertSession(sessionEntity)
            Log.d(TAG, "✅ Session saved to Room database: $sessionId")
            Log.d(TAG, "✅ For user: $userId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save to Room database", e)
            throw e
        }
        
        // Firestore sync uses SAME sessionId as Room to avoid duplicate sessions on restore.
        syncScope.launch {
            firestoreService.saveSessionToFirestore(
                stats,
                sessionStartTimeMs,
                sessionId,
                accelerometerSamples,
                sessionDateKey = date
            )
                .onFailure { error ->
                    Log.e(TAG, "Failed to sync session to Firestore (will retry when online)", error)
                }
                .onSuccess {
                    Log.d(TAG, "✅ Session synced to Firestore")
                }
        }
        
        return sessionId
    }
    
    companion object {
        private const val TAG = "SessionManager"
    }
    
    fun getAllSessions(userId: String): Flow<List<SessionStats>> {
        Log.d(TAG, "📊 Fetching all sessions for user: $userId")
        return sessionDao.getAllSessions(userId).map { entities ->
            Log.d(TAG, "📊 Found ${entities.size} sessions in database")
            entities.map { it.toSessionStats() }
        }
    }
    
    suspend fun getLatestSession(userId: String): SessionStats? {
        return sessionDao.getLatestSession(userId)?.toSessionStats()
    }
    
    /**
     * Restore sessions from Firestore into Room.
     * Called on login and app launch when user is logged in.
     * Keeps newest version when a session exists in both (by createdAt).
     */
    suspend fun restoreSessionsFromFirestore(userId: String): Result<Int> {
        return try {
            Log.d(TAG, "📥 Restoring sessions from Firestore for user: $userId")
            
            val fetchResult = firestoreService.fetchSessionsFromFirestore(userId)
            fetchResult.getOrElse { e ->
                Log.e(TAG, "Failed to fetch from Firestore", e)
                return Result.failure(e)
            }
            
            val docs = fetchResult.getOrNull() ?: emptyList()
            if (docs.isEmpty()) {
                Log.d(TAG, "📥 No sessions to restore")
                return Result.success(0)
            }
            
            var restored = 0
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            
            for (doc in docs) {
                try {
                    val existing = sessionDao.getSessionById(doc.id)
                    if (existing != null && existing.createdAt >= doc.createdAtMs) {
                        Log.d(TAG, "📥 Skipping ${doc.id} - local version newer")
                        continue
                    }
                    
                    val breakdownEntity = doc.breakdown.mapValues { (_, b) ->
                        ActivityBreakdownEntity(
                            time = b.time,
                            distance = b.distance,
                            steps = b.steps
                        )
                    }
                    
                    val date = dateFormat.format(Date(doc.timestamp))
                    val kmMilestonesJson = com.google.gson.Gson().toJson(doc.kmMilestones)
                    
                    // Use routePath from Firestore when available; otherwise preserve local
                    val routePath = doc.routePath.ifEmpty { existing?.routePath ?: emptyList() }
                    val entity = SessionEntity(
                        id = doc.id,
                        userId = userId,
                        date = date,
                        totalDuration = doc.totalDuration,
                        totalDistance = doc.totalDistance,
                        caloriesBurned = doc.caloriesBurned,
                        co2Emissions = doc.co2Emissions,
                        co2Conserved = doc.co2Conserved,
                        totalSteps = doc.totalSteps.takeIf { it > 0 } ?: doc.breakdown.values.sumOf { it.steps },
                        elevationGain = doc.elevationGain,
                        elevationLoss = doc.elevationLoss,
                        startingAltitude = doc.startingAltitude,
                        stoppingAltitude = doc.stoppingAltitude,
                        minAltitude = doc.minAltitude,
                        maxAltitude = doc.maxAltitude,
                        topSpeedMps = doc.topSpeedMps,
                        kmMilestonesJson = kmMilestonesJson,
                        routePath = routePath,
                        createdAt = doc.createdAtMs,
                        breakdown = breakdownEntity
                    )
                    
                    sessionDao.insertSession(entity)
                    restored++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore session ${doc.id}", e)
                }
            }
            
            Log.d(TAG, "✅ Restored $restored sessions from Firestore")
            Result.success(restored)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Restore failed", e)
            Result.failure(e)
        }
    }
    
    suspend fun deleteSession(sessionId: String, userId: String) {
        val session = sessionDao.getLatestSession(userId)
        session?.let {
            if (it.id == sessionId) {
                sessionDao.deleteSession(it)
            }
        }
    }
    
    fun calculateCO2(distance: Double, activity: ActivityType): Pair<Double, Double> {
        val co2Factor = CO2Factors.getFactor(activity)
        val co2Impact = co2Factor * (distance / 1000.0) // Convert meters to km
        
        return if (co2Impact > 0) {
            // Emissions
            Pair(co2Impact, 0.0)
        } else if (co2Impact < 0) {
            // Conservation
            Pair(0.0, Math.abs(co2Impact))
        } else {
            Pair(0.0, 0.0)
        }
    }
    
    /**
     * Calculate calories using the advanced CalorieEngine
     * @param duration Duration in seconds
     * @param activity Activity type
     * @param speedMps Speed in meters per second (optional)
     * @param elevationGain Elevation gained in meters (optional)
     * @param elevationLoss Elevation lost in meters (optional)
     * @param userProfile User's physical profile (optional)
     */
    fun calculateCalories(
        duration: Long,
        activity: ActivityType,
        speedMps: Double? = null,
        elevationGain: Double = 0.0,
        elevationLoss: Double = 0.0,
        userProfile: UserPhysicalProfile? = null
    ): Double {
        return CalorieEngine.calculateCalories(
            activityType = activity,
            durationSeconds = duration,
            speedMps = speedMps,
            elevationGainMeters = elevationGain,
            elevationLossMeters = elevationLoss,
            userProfile = userProfile
        )
    }
    
    /**
     * DEPRECATED: Legacy method for backward compatibility
     * Use calculateCalories() with proper parameters instead
     */
    @Deprecated("Use calculateCalories with speed and elevation parameters")
    fun calculateCaloriesLegacy(duration: Long, activity: ActivityType): Double {
        val caloriesPerHour = CalorieFactorsPerHour.getFactor(activity)
        val caloriesPerSecond = caloriesPerHour / 3600.0
        return caloriesPerSecond * duration
    }
    
    private fun SessionEntity.toSessionStats(): SessionStats {
        val breakdown = this.breakdown.mapValues { (_, entity) ->
            ActivityBreakdown(
                time = entity.time,
                distance = entity.distance,
                steps = entity.steps
            )
        }
        
        val kmMilestones = try {
            com.google.gson.Gson().fromJson<List<KmMilestone>>(
                kmMilestonesJson,
                object : com.google.gson.reflect.TypeToken<List<KmMilestone>>() {}.type
            ) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        
        return SessionStats(
            date = date,
            totalDuration = totalDuration,
            totalDistance = totalDistance,
            caloriesBurned = caloriesBurned,
            co2Emissions = co2Emissions,
            co2Conserved = co2Conserved,
            totalSteps = totalSteps,
            elevationGain = elevationGain,
            elevationLoss = elevationLoss,
            startingAltitude = startingAltitude,
            stoppingAltitude = stoppingAltitude,
            minAltitude = minAltitude,
            maxAltitude = maxAltitude,
            topSpeedMps = topSpeedMps,
            kmMilestones = kmMilestones,
            segments = emptyList(), // Can be expanded later
            routePath = routePath,
            breakdown = breakdown
        )
    }
}



