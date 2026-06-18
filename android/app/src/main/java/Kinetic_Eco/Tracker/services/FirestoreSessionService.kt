package Kinetic_Eco.Tracker.services

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import Kinetic_Eco.Tracker.data.ActivityBreakdown
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.KmMilestone
import Kinetic_Eco.Tracker.data.SessionStats
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Firestore session document for restore (matches saved structure).
 * Older docs may have null for optional fields.
 */
data class FirestoreSessionDoc(
    val id: String,
    val timestamp: Long,
    val totalDistance: Double,
    val totalDuration: Long,
    val totalSteps: Int = 0,
    val caloriesBurned: Double,
    val co2Emissions: Double,
    val co2Conserved: Double,
    val breakdown: Map<ActivityType, ActivityBreakdown>,
    val createdAtMs: Long,
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0,
    val startingAltitude: Double? = null,
    val stoppingAltitude: Double? = null,
    val minAltitude: Double? = null,
    val maxAltitude: Double? = null,
    val topSpeedMps: Double = 0.0,
    val routePath: List<Kinetic_Eco.Tracker.data.RoutePoint> = emptyList(),
    val kmMilestones: List<KmMilestone> = emptyList()
)

/**
 * Service to sync sessions to Firestore for cloud AI analysis
 * Structure: /users/{userId}/sessions/{sessionId}
 */
class FirestoreSessionService {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    
    /**
     * Save session to Firestore for cloud analysis
     * @param sessionStartTimeMs When session started (0 = use current time)
     * @param sessionId Session ID to use (must match Room to avoid duplicates on restore). If null, generates one.
     * @param accelerometerSamples Optional accelerometer samples (1/sec) for analytics subcollection.
     */
    suspend fun saveSessionToFirestore(
        stats: SessionStats,
        sessionStartTimeMs: Long = 0,
        sessionId: String? = null,
        accelerometerSamples: List<AccelerometerSample> = emptyList(),
        sessionDateKey: String? = null
    ): Result<String> {
        return try {
            val user = auth.currentUser
            if (user == null) {
                Log.w(TAG, "⚠️ No authenticated user, skipping Firestore session save")
                return Result.failure(Exception("User not authenticated"))
            }
            
            val timestampMs = if (sessionStartTimeMs > 0) sessionStartTimeMs else System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateKey = sessionDateKey?.trim()?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                ?: dateFormat.format(Date(timestampMs))
            
            Log.d(TAG, "📤 Preparing to save session to Firestore...")
            Log.d(TAG, "User: ${user.uid} (${user.email})")
            Log.d(TAG, "Stats: distance=${stats.totalDistance}m, duration=${stats.totalDuration}s, timestamp=$timestampMs")
            
            val docId = sessionId ?: "session-$timestampMs-${(0..999999).random()}"
            val sessionRef = firestore
                .collection("users")
                .document(user.uid)
                .collection("sessions")
                .document(docId)
            
            Log.d(TAG, "Firestore path: users/${user.uid}/sessions/$docId")
            
            // Convert session data to Firestore format (timestamp = when session started)
            val firestoreSession = hashMapOf<String, Any>(
                "timestamp" to timestampMs,
                "sessionDateKey" to dateKey,
                "totalDistance" to (stats.totalDistance),
                "totalDuration" to (stats.totalDuration),
                "totalSteps" to stats.totalSteps,
                "caloriesBurned" to (stats.caloriesBurned),
                "co2Emissions" to (stats.co2Emissions),
                "co2Conserved" to (stats.co2Conserved),
                "breakdown" to convertBreakdown(stats),
                "createdAt" to com.google.firebase.Timestamp.now(),
                "userId" to user.uid,
                "userEmail" to (user.email ?: "unknown"),
                "elevationGain" to stats.elevationGain,
                "elevationLoss" to stats.elevationLoss,
                "startingAltitude" to (stats.startingAltitude ?: 0.0),
                "stoppingAltitude" to (stats.stoppingAltitude ?: 0.0),
                "minAltitude" to (stats.minAltitude ?: 0.0),
                "maxAltitude" to (stats.maxAltitude ?: 0.0),
                "topSpeedMps" to stats.topSpeedMps,
                "routePath" to stats.routePath.map { pt ->
                    mutableMapOf<String, Any>(
                        "latitude" to pt.latitude,
                        "longitude" to pt.longitude
                    ).apply {
                        pt.activity?.let { put("activity", it.name) }
                        pt.altitudeMeters?.takeIf { it.isFinite() }?.let { put("altitude", it) }
                    }
                },
                "kmMilestones" to stats.kmMilestones.map { mapOf("km" to it.km, "secondsForKm" to it.secondsForKm) },
                "segments" to stats.segments.map { seg ->
                    mapOf(
                        "type" to seg.type.name,
                        "startTime" to seg.startTime,
                        "endTime" to seg.endTime,
                        "distance" to seg.distance,
                        "avgSpeed" to seg.avgSpeed,
                        "comment" to seg.comment,
                        "userCorrectedType" to (seg.userCorrectedType?.name ?: "")
                    )
                }
            )
            
            Log.d(TAG, "Calling Firestore set()...")
            sessionRef.set(firestoreSession).await()
            Log.d(TAG, "✅ Session saved to Firestore: $docId")

            // Write accelerometer samples to subcollection (batched: 60 samples per doc = 1 min)
            if (accelerometerSamples.isNotEmpty()) {
                saveAccelerometerSamples(sessionRef, accelerometerSamples)
            }

            Log.d(TAG, "Verify at: https://console.firebase.google.com/project/gen-lang-client-0114974661/firestore")
            Result.success(docId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving session to Firestore: ${e.message}", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
    
    /**
     * Save accelerometer samples to session subcollection (batched: 60 samples per doc).
     * Path: users/{userId}/sessions/{sessionId}/accelerometer_samples/{batchIndex}
     */
    private suspend fun saveAccelerometerSamples(
        sessionRef: DocumentReference,
        samples: List<AccelerometerSample>
    ) {
        val BATCH_SIZE = 60
        samples.chunked(BATCH_SIZE).forEachIndexed { index, chunk ->
            val batchDoc = chunk.map { s ->
                mapOf(
                    "t" to s.timestampMs,
                    "x" to s.x.toDouble(),
                    "y" to s.y.toDouble(),
                    "z" to s.z.toDouble(),
                    "m" to s.magnitude.toDouble()
                )
            }
            sessionRef.collection("accelerometer_samples")
                .document("batch_$index")
                .set(mapOf("samples" to batchDoc))
                .await()
        }
        Log.d(TAG, "✅ Saved ${samples.size} accelerometer samples in ${(samples.size + BATCH_SIZE - 1) / BATCH_SIZE} batches")
    }

    /**
     * Convert session breakdown to Firestore format (includes steps per activity)
     */
    private fun convertBreakdown(stats: SessionStats): Map<String, Map<String, Any>> {
        return stats.breakdown.mapValues { (_, breakdown) ->
            mapOf<String, Any>(
                "time" to breakdown.time.toDouble(),
                "distance" to breakdown.distance.toDouble(),
                "steps" to breakdown.steps
            )
        }.mapKeys { it.key.name } // Convert ActivityType enum to String
    }
    
    /**
     * Fetch all sessions for a user from Firestore (for restore after app update).
     * Structure: users/{userId}/sessions/{docId}
     */
    /**
     * @param sinceMs When non-null, only documents created after this time are fetched
     * (incremental sync — avoids re-downloading the full history on every launch).
     * Pass null to fetch the complete collection (e.g. first sync on a fresh install).
     */
    suspend fun fetchSessionsFromFirestore(userId: String, sinceMs: Long? = null): Result<List<FirestoreSessionDoc>> {
        return try {
            val user = auth.currentUser
            if (user == null || user.uid != userId) {
                Log.w(TAG, "⚠️ No authenticated user or userId mismatch, cannot fetch")
                return Result.failure(Exception("User not authenticated"))
            }

            var query: com.google.firebase.firestore.Query = firestore
                .collection("users")
                .document(userId)
                .collection("sessions")
            if (sinceMs != null) {
                query = query.whereGreaterThan("createdAt", Timestamp(Date(sinceMs)))
            }

            Log.d(TAG, "📥 Fetching sessions from Firestore for user: $userId" +
                if (sinceMs != null) " (since $sinceMs)" else " (full sync)")

            val snapshot = query.get().await()
            
            val docs = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null
                    val timestamp = (data["timestamp"] as? Number)?.toLong() ?: 0L
                    val totalDistance = (data["totalDistance"] as? Number)?.toDouble() ?: 0.0
                    val totalDuration = (data["totalDuration"] as? Number)?.toLong() ?: 0L
                    val totalSteps = (data["totalSteps"] as? Number)?.toInt() ?: 0
                    val caloriesBurned = (data["caloriesBurned"] as? Number)?.toDouble() ?: 0.0
                    val co2Emissions = (data["co2Emissions"] as? Number)?.toDouble() ?: 0.0
                    val co2Conserved = (data["co2Conserved"] as? Number)?.toDouble() ?: 0.0
                    
                    @Suppress("UNCHECKED_CAST")
                    val breakdownRaw = data["breakdown"] as? Map<String, Map<String, Any>> ?: emptyMap()
                    val breakdown = parseBreakdownFromFirestore(breakdownRaw)
                    
                    val createdAtMs = when (val created = data["createdAt"]) {
                        is Timestamp -> created.toDate().time
                        else -> timestamp
                    }
                    
                    val elevationGain = (data["elevationGain"] as? Number)?.toDouble() ?: 0.0
                    val elevationLoss = (data["elevationLoss"] as? Number)?.toDouble() ?: 0.0
                    val startingAltitude = (data["startingAltitude"] as? Number)?.toDouble()?.takeIf { it != 0.0 }
                    val stoppingAltitude = (data["stoppingAltitude"] as? Number)?.toDouble()?.takeIf { it != 0.0 }
                    val minAltitude = (data["minAltitude"] as? Number)?.toDouble()?.takeIf { it != 0.0 }
                    val maxAltitude = (data["maxAltitude"] as? Number)?.toDouble()?.takeIf { it != 0.0 }
                    val topSpeedMps = (data["topSpeedMps"] as? Number)?.toDouble() ?: 0.0
                    
                    @Suppress("UNCHECKED_CAST")
                    val routePathRaw = data["routePath"] as? List<Map<String, Any>> ?: emptyList()
                    val routePath = routePathRaw.mapNotNull fe@{ m ->
                        val lat = (m["latitude"] as? Number)?.toDouble() ?: return@fe null
                        val lon = (m["longitude"] as? Number)?.toDouble() ?: return@fe null
                        val activityStr = m["activity"] as? String
                        val activity = activityStr?.let { str ->
                            try { Kinetic_Eco.Tracker.data.ActivityType.valueOf(str) } catch (_: Exception) { null }
                        }
                        val alt = (m["altitude"] as? Number)?.toDouble()
                            ?: (m["altitudeMeters"] as? Number)?.toDouble()
                        val altitudeMeters = alt?.takeIf { it.isFinite() }
                        Kinetic_Eco.Tracker.data.RoutePoint(
                            latitude = lat,
                            longitude = lon,
                            activity = activity,
                            altitudeMeters = altitudeMeters
                        )
                    }
                    
                    @Suppress("UNCHECKED_CAST")
                    val kmMilestonesRaw = data["kmMilestones"] as? List<Map<String, Any>> ?: emptyList()
                    val kmMilestones = kmMilestonesRaw.mapNotNull km@{ m ->
                        val kmVal = (m["km"] as? Number)?.toInt() ?: return@km null
                        val secondsVal = (m["secondsForKm"] as? Number)?.toLong() ?: return@km null
                        KmMilestone(km = kmVal, secondsForKm = secondsVal)
                    }
                    
                    FirestoreSessionDoc(
                        id = doc.id,
                        timestamp = timestamp,
                        totalDistance = totalDistance,
                        totalDuration = totalDuration,
                        totalSteps = totalSteps,
                        caloriesBurned = caloriesBurned,
                        co2Emissions = co2Emissions,
                        co2Conserved = co2Conserved,
                        breakdown = breakdown,
                        createdAtMs = createdAtMs,
                        elevationGain = elevationGain,
                        elevationLoss = elevationLoss,
                        startingAltitude = startingAltitude,
                        stoppingAltitude = stoppingAltitude,
                        minAltitude = minAltitude,
                        maxAltitude = maxAltitude,
                        topSpeedMps = topSpeedMps,
                        routePath = routePath,
                        kmMilestones = kmMilestones
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse Firestore doc ${doc.id}", e)
                    null
                }
            }
            
            Log.d(TAG, "✅ Fetched ${docs.size} sessions from Firestore")
            Result.success(docs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching sessions from Firestore: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    private fun parseBreakdownFromFirestore(raw: Map<String, Map<String, Any>>): Map<ActivityType, ActivityBreakdown> {
        val result = mutableMapOf<ActivityType, ActivityBreakdown>()
        ActivityType.entries.forEach { type ->
            val map = raw[type.name] ?: return@forEach
            val time = (map["time"] as? Number)?.toLong() ?: 0L
            val distance = (map["distance"] as? Number)?.toDouble() ?: 0.0
            val steps = (map["steps"] as? Number)?.toInt() ?: 0
            result[type] = ActivityBreakdown(time = time, distance = distance, steps = steps)
        }
        return result
    }
    
    /**
     * Batch sync multiple sessions to Firestore
     * Useful for syncing Room database sessions
     */
    suspend fun batchSyncSessions(sessions: List<SessionStats>): Result<Int> {
        return try {
            val user = auth.currentUser
            if (user == null) {
                return Result.failure(Exception("User not authenticated"))
            }
            
            Log.d(TAG, "📤 Syncing ${sessions.size} sessions to Firestore...")
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            var successCount = 0
            for (session in sessions) {
                // Use session date for correct timestamp when syncing from Room
                val timestampMs = if (session.date.isNotEmpty()) {
                    try { dateFormat.parse(session.date)?.time ?: 0L } catch (_: Exception) { 0L }
                } else 0L
                val dateKey = session.date.trim().takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                val result = saveSessionToFirestore(
                    session,
                    timestampMs,
                    sessionDateKey = dateKey
                )
                if (result.isSuccess) {
                    successCount++
                }
            }
            
            Log.d(TAG, "✅ Successfully synced $successCount/${sessions.size} sessions")
            Result.success(successCount)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error batch syncing sessions", e)
            Result.failure(e)
        }
    }
    
    suspend fun patchSessionSegments(
        sessionId: String,
        userId: String,
        mergedSegments: List<Kinetic_Eco.Tracker.data.ActivitySegment>,
        co2Emissions: Double,
        co2Conserved: Double,
        caloriesBurned: Double,
        routePath: List<Kinetic_Eco.Tracker.data.RoutePoint>
    ) {
        try {
            val ref = firestore.collection("users").document(userId)
                .collection("sessions").document(sessionId)
            val updates = hashMapOf<String, Any>(
                "co2Emissions" to co2Emissions,
                "co2Conserved" to co2Conserved,
                "caloriesBurned" to caloriesBurned,
                "segments" to mergedSegments.map { seg ->
                    mapOf(
                        "type" to seg.type.name,
                        "startTime" to seg.startTime,
                        "endTime" to seg.endTime,
                        "distance" to seg.distance,
                        "avgSpeed" to seg.avgSpeed,
                        "comment" to seg.comment,
                        "userCorrectedType" to (seg.userCorrectedType?.name ?: "")
                    )
                },
                "routePath" to routePath.map { pt ->
                    mutableMapOf<String, Any>(
                        "latitude" to pt.latitude,
                        "longitude" to pt.longitude
                    ).apply {
                        pt.activity?.let { put("activity", it.name) }
                        pt.altitudeMeters?.takeIf { it.isFinite() }?.let { put("altitude", it) }
                    }
                }
            )
            ref.update(updates).await()
            Log.d(TAG, "✅ Patched segments for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "patchSessionSegments failed for $sessionId", e)
        }
    }

    companion object {
        private const val TAG = "FirestoreSessionSvc"
        
        @Volatile
        private var instance: FirestoreSessionService? = null
        
        fun getInstance(): FirestoreSessionService {
            return instance ?: synchronized(this) {
                instance ?: FirestoreSessionService().also { instance = it }
            }
        }
    }
}
