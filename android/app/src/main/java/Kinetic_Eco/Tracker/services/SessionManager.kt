package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.ActivitySegment
import Kinetic_Eco.Tracker.data.KmMilestone
import Kinetic_Eco.Tracker.data.database.*
import com.google.gson.reflect.TypeToken
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
        val segmentsJson = com.google.gson.Gson().toJson(stats.segments)

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
            segmentsJson = segmentsJson,
            routePath = stats.routePath,
            // Real session time (matches `date`), not save-time — createdAt is used downstream as the
            // session's effective time for date grouping, and as the incremental-sync watermark.
            createdAt = if (sessionStartTimeMs > 0) sessionStartTimeMs else System.currentTimeMillis(),
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
                    // Confirmed on the server — mark it so the backfill worker skips it.
                    runCatching { sessionDao.markSessionSynced(sessionId) }
                    // Refresh the leaderboard entry so co2Conserved7d/30d/AllTime stay
                    // in sync with reality. updateLeaderboardEntry is a no-op when the
                    // user hasn't opted in, so this is safe to call unconditionally.
                    runCatching {
                        LeaderboardService.getInstance().updateLeaderboardEntry(userId)
                    }.onFailure { e ->
                        Log.w(TAG, "Leaderboard refresh after session save failed: ${e.message}")
                    }
                }
        }
        
        return sessionId
    }

    /**
     * Upload any locally-saved sessions that were never confirmed in Firestore (server-acked).
     *
     * This is the durable counterpart to the fire-and-forget sync in [saveSession]: that sync can be
     * killed with the process when a session ends and the app is backgrounded, leaving the session only
     * in Room. This method — driven by [Kinetic_Eco.Tracker.services.SessionSyncWorker] on launch /
     * network-regained — re-uploads them, **reusing each session's id as the Firestore doc id** so
     * re-uploads overwrite rather than duplicate. Marks each row synced on success.
     *
     * @return number of sessions successfully uploaded, or a failure if the batch hit an error
     *   (WorkManager then retries with backoff, so a partial upload resumes where it left off).
     */
    suspend fun backfillUnsyncedSessions(userId: String): Result<Int> {
        return try {
            val unsynced = sessionDao.getUnsyncedSessions(userId)
            if (unsynced.isEmpty()) {
                return Result.success(0)
            }
            Log.d(TAG, "☁️ Backfill: ${unsynced.size} un-synced session(s) for $userId")
            var uploaded = 0
            var lastError: Throwable? = null
            for (entity in unsynced) {
                val stats = entity.toSessionStats()
                val result = firestoreService.saveSessionToFirestore(
                    stats = stats,
                    sessionStartTimeMs = entity.createdAt,
                    sessionId = entity.id,
                    sessionDateKey = entity.date
                )
                result
                    .onSuccess {
                        sessionDao.markSessionSynced(entity.id)
                        uploaded++
                    }
                    .onFailure { e ->
                        // Stop on the first failure (usually offline / auth) — remaining rows stay
                        // un-synced and are retried on the next run, preserving order.
                        lastError = e
                    }
                if (lastError != null) break
            }
            if (uploaded > 0) {
                Log.d(TAG, "✅ Backfill uploaded $uploaded session(s)")
                // Keep the leaderboard aggregate honest now that history reached the server.
                runCatching { LeaderboardService.getInstance().updateLeaderboardEntry(userId) }
            }
            lastError?.let { return Result.failure(it as? Exception ?: Exception(it.message ?: "backfill failed")) }
            Result.success(uploaded)
        } catch (e: Exception) {
            Log.e(TAG, "Backfill failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SessionManager"
    }
    
    // ── Time-series aggregation ───────────────────────────────────────────────

    /** CO2 totals grouped by calendar day for the last [days] days. */
    suspend fun getCo2PerDay(userId: String, days: Int = 30): List<Co2ByPeriod> {
        val from = offsetDate(-days)
        return sessionDao.getCo2PerDay(userId, from)
    }

    /** CO2 totals grouped by week for the last [weeks] weeks. */
    suspend fun getCo2PerWeek(userId: String, weeks: Int = 12): List<Co2ByPeriod> {
        val from = offsetDate(-(weeks * 7))
        return sessionDao.getCo2PerWeek(userId, from)
    }

    /** CO2 totals grouped by calendar month for the last [months] months. */
    suspend fun getCo2PerMonth(userId: String, months: Int = 12): List<Co2ByPeriod> {
        val from = offsetDate(-(months * 30))
        return sessionDao.getCo2PerMonth(userId, from)
    }

    /**
     * Current green-day streak: consecutive calendar days (ending today or
     * yesterday) on which at least one session conserved CO₂.
     *
     * If the user hasn't had an active day today or yesterday the streak resets
     * to 0 — matching the "use it or lose it" convention most fitness apps use.
     */
    suspend fun getCurrentStreak(userId: String): Int {
        val activeDays = sessionDao.getActiveDays(userId)
        if (activeDays.isEmpty()) return 0

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(Date())
        val yesterday = sdf.format(Date(System.currentTimeMillis() - 86_400_000L))

        // Streak must start from today or yesterday
        val mostRecent = activeDays.first()
        if (mostRecent != today && mostRecent != yesterday) return 0

        var expected = mostRecent
        var streak = 0
        val cal = Calendar.getInstance()

        for (date in activeDays) {
            if (date == expected) {
                streak++
                cal.time = sdf.parse(expected)!!
                cal.add(Calendar.DAY_OF_YEAR, -1)
                expected = sdf.format(cal.time)
            } else {
                break
            }
        }
        return streak
    }

    /**
     * CO₂ savings attributed per activity type over a date range.
     *
     * Uses per-activity distance from the [breakdown] JSON together with the
     * default [CO2Factors] to apportion the session's savings — the same math
     * the tracker uses when it accumulates CO₂ in real time.
     *
     * Only activities with a negative factor (actual savers) appear in the map.
     * Activities that emit (DRIVING, FLYING, …) are excluded so the caller
     * can display a pure "what saved the most CO₂" breakdown.
     */
    suspend fun getActivityCo2Split(
        userId: String,
        fromDate: String = offsetDate(-30),
        toDate: String = dateFormat.format(Date())
    ): Map<ActivityType, Double> {
        val sessions = sessionDao.getSessionsInRange(userId, fromDate, toDate)
        val totals = mutableMapOf<ActivityType, Double>()
        for (entity in sessions) {
            for ((type, breakdown) in entity.breakdown) {
                val factor = CO2Factors.getFactor(type)
                val savingsKg = -factor * (breakdown.distance / 1000.0)
                if (savingsKg > 0.0) {
                    totals[type] = (totals[type] ?: 0.0) + savingsKg
                }
            }
        }
        return totals
    }

    /**
     * Sessions in a closed date range that have at least one recorded GPS point.
     * Used by [RouteIntelligenceService] for O-D clustering — sessions without a
     * route path are filtered out here rather than in the caller.
     */
    suspend fun getSessionsWithRoutes(
        userId: String,
        fromDate: String,
        toDate: String
    ): List<SessionStats> =
        sessionDao.getSessionsInRange(userId, fromDate, toDate)
            .filter { it.routePath.isNotEmpty() }
            .map { it.toSessionStats() }

    private fun offsetDate(days: Int): String =
        dateFormat.format(Date(System.currentTimeMillis() + days * 86_400_000L))

    // ─────────────────────────────────────────────────────────────────────────

    fun getAllSessions(userId: String): Flow<List<SessionStats>> {
        Log.d(TAG, "📊 Fetching all sessions for user: $userId")
        return sessionDao.getAllSessions(userId)
            .map { entities ->
                Log.d(TAG, "📊 Found ${entities.size} sessions in database")
                entities.map { it.toSessionStats() }
            }
            .flowOn(Dispatchers.IO)
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

            // Incremental sync: only pull documents newer than the most recent one we
            // already have locally, instead of re-downloading the entire history (and
            // re-checking every doc against Room) on every single app launch.
            val sinceMs = sessionDao.getLatestSession(userId)?.createdAt
            val fetchResult = firestoreService.fetchSessionsFromFirestore(userId, sinceMs)
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
                    if (existing != null && existing.createdAt >= doc.timestamp) {
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
                    // Segments drive the post-session Mode-timeline (reclassify a segment). Use Firestore's
                    // when present; otherwise keep any local segments (e.g. user edits) rather than wiping them.
                    val segmentsJson = if (doc.segments.isNotEmpty()) {
                        com.google.gson.Gson().toJson(doc.segments)
                    } else {
                        existing?.segmentsJson ?: "[]"
                    }

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
                        segmentsJson = segmentsJson,
                        routePath = routePath,
                        // Real session time (doc.timestamp), NOT the Firestore write-time (createdAtMs).
                        // This self-heals histories whose createdAt was corrupted by a past re-upload.
                        createdAt = doc.timestamp,
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
    
    fun calculateCO2(
        distance: Double,
        activity: ActivityType,
        vehicleProfile: VehicleProfile = VehicleProfile.DEFAULT
    ): Pair<Double, Double> {
        val co2Factor = CO2Factors.getFactor(activity, vehicleProfile)
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
        
        val segments = try {
            com.google.gson.Gson().fromJson<List<ActivitySegment>>(
                segmentsJson,
                object : TypeToken<List<ActivitySegment>>() {}.type
            ) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        return SessionStats(
            id = id,
            date = date,
            sessionEndTimeMs = createdAt,
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
            segments = segments,
            routePath = routePath,
            breakdown = breakdown
        )
    }

    suspend fun getSessionById(sessionId: String): SessionStats? =
        sessionDao.getSessionById(sessionId)?.toSessionStats()

    /**
     * Apply user-edited [updatedSegments] to an existing session. Uses [effectiveType] for all
     * CO₂/calorie/breakdown recalculations and rebuilds route-path activity colours proportionally.
     * Saves to Room immediately; syncs to Firestore in the background.
     */
    suspend fun updateSessionSegments(
        sessionId: String,
        userId: String,
        updatedSegments: List<ActivitySegment>
    ): Result<Unit> = try {
        val entity = sessionDao.getSessionById(sessionId)
            ?: return Result.failure(Exception("Session $sessionId not found"))

        val storedSegments = try {
            com.google.gson.Gson().fromJson<List<ActivitySegment>>(
                entity.segmentsJson,
                object : TypeToken<List<ActivitySegment>>() {}.type
            ) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        // Pre-fix sessions have no stored segments. The caller (SessionDetailScreen) reconstructs
        // them from routePath for display and passes the full reconstructed list back on edit, so
        // adopt it as the base here — this backfills the session on first edit instead of merging
        // into an empty list and zeroing every recomputed total.
        val allSegments = storedSegments.ifEmpty { updatedSegments }

        // Merge: only the segments the caller edited (matched by startTime); others unchanged.
        val editedByStart = updatedSegments.associateBy { it.startTime }
        val mergedSegments = allSegments.map { seg -> editedByStart[seg.startTime] ?: seg }

        val newEmissions = mergedSegments.sumOf { seg ->
            val f = CO2Factors.getFactor(seg.effectiveType)
            if (f > 0) f * (seg.distance / 1000.0) else 0.0
        }
        val newConserved = mergedSegments.sumOf { seg ->
            val f = CO2Factors.getFactor(seg.effectiveType)
            if (f < 0) -f * (seg.distance / 1000.0) else 0.0
        }
        val newCalories = mergedSegments.sumOf { seg ->
            val hours = (seg.endTime - seg.startTime) / 3_600_000.0
            CalorieFactorsPerHour.getFactor(seg.effectiveType) * hours
        }
        val newBreakdown = ActivityType.values().associateWith { type ->
            val segs = mergedSegments.filter { it.effectiveType == type }
            ActivityBreakdownEntity(
                time = segs.sumOf { (it.endTime - it.startTime) / 1000L },
                distance = segs.sumOf { it.distance },
                steps = entity.breakdown[type]?.steps ?: 0
            )
        }
        val newRoutePath = rebuildRoutePathActivities(entity.routePath, mergedSegments)
        val newSegmentsJson = com.google.gson.Gson().toJson(mergedSegments)

        sessionDao.updateSession(
            entity.copy(
                co2Emissions = newEmissions,
                co2Conserved = newConserved,
                caloriesBurned = newCalories,
                breakdown = newBreakdown,
                segmentsJson = newSegmentsJson,
                routePath = newRoutePath
            )
        )

        syncScope.launch {
            firestoreService.patchSessionSegments(
                sessionId = sessionId,
                userId = userId,
                mergedSegments = mergedSegments,
                co2Emissions = newEmissions,
                co2Conserved = newConserved,
                caloriesBurned = newCalories,
                routePath = newRoutePath
            )
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "updateSessionSegments failed", e)
        Result.failure(e)
    }

    private fun rebuildRoutePathActivities(
        routePath: List<RoutePoint>,
        segments: List<ActivitySegment>
    ): List<RoutePoint> {
        if (segments.isEmpty() || routePath.isEmpty()) return routePath
        val totalMs = segments.sumOf { it.endTime - it.startTime }.toFloat()
        if (totalMs <= 0f) return routePath
        var elapsed = 0L
        val cumFractions = segments.map { seg ->
            elapsed += (seg.endTime - seg.startTime)
            elapsed.toFloat() / totalMs
        }
        return routePath.mapIndexed { idx, pt ->
            val fraction = if (routePath.size <= 1) 0f else idx.toFloat() / (routePath.size - 1)
            val sIdx = cumFractions.indexOfFirst { fraction <= it }.takeIf { it >= 0 } ?: segments.lastIndex
            pt.copy(activity = segments[sIdx].effectiveType)
        }
    }
}



