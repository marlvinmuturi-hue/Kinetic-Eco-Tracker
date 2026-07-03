package Kinetic_Eco.Tracker.services

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import Kinetic_Eco.Tracker.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.LeaderboardCategory
import Kinetic_Eco.Tracker.data.LeaderboardEntry
import Kinetic_Eco.Tracker.data.SessionStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "LeaderboardService"

/** Rolling window uses Firestore field suffix `Nd` (e.g. score7d). AllTime uses AllTime. */
sealed class LeaderboardPeriod {
    data class Rolling(val days: Int) : LeaderboardPeriod() {
        init {
            require(days in 1..366) { "days must be 1..366" }
        }
    }

    data object AllTime : LeaderboardPeriod()
}

private fun LeaderboardPeriod.fieldSuffix(): String = when (this) {
    is LeaderboardPeriod.Rolling -> "${days}d"
    LeaderboardPeriod.AllTime -> "AllTime"
}

/**
 * Service for leaderboard: opt-in, update entry, fetch ranked list.
 * Firestore: leaderboard/{userId}, users/{userId}.leaderboardOptIn
 */
class LeaderboardService {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val firestoreSessionService = FirestoreSessionService.getInstance()
    private val userProfileService = UserProfileService()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Check if user has opted in (from Firestore users doc). */
    suspend fun isOptedIn(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("users").document(userId).get().await()
            (doc.data?.get("leaderboardOptIn") as? Boolean) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check opt-in", e)
            false
        }
    }

    /**
     * Returns the raw opt-in flag:
     *   true  = user explicitly opted in
     *   false = user explicitly opted out
     *   null  = field has never been written (new user, no preference set)
     */
    suspend fun getOptInStatus(userId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("users").document(userId).get().await()
            doc.data?.get("leaderboardOptIn") as? Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get opt-in status", e)
            null
        }
    }

    /** Opt in: write leaderboard entry first (so user appears), then set flag. */
    suspend fun optIn(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != userId) {
            Log.e(TAG, "Opt-in: user not authenticated or uid mismatch (auth=${currentUser?.uid}, requested=$userId)")
            return@withContext Result.failure(Exception("Please sign in to update leaderboard preference"))
        }
        try {
            // 1. Write leaderboard entry first (always; use zeros if fetch fails so user appears immediately)
            writeLeaderboardEntry(userId)
            // 2. Set opt-in flag
            firestore.collection("users").document(userId)
                .set(mapOf("leaderboardOptIn" to true), SetOptions.merge())
                .await()
            Log.d(TAG, "Leaderboard opt-in complete for $userId")
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "Opt-in failed: ${e.message}", e)
            Result.failure(e as? Exception ?: Exception(e.message ?: "Opt-in failed"))
        }
    }

    /** Opt out: delete leaderboard entry first, then clear flag. */
    suspend fun optOut(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != userId) {
            Log.e(TAG, "Opt-out: user not authenticated or uid mismatch")
            return@withContext Result.failure(Exception("Please sign in to update leaderboard preference"))
        }
        try {
            // Delete first so we don't leave inconsistent state if delete fails
            firestore.collection("leaderboard").document(userId).delete().await()
            firestore.collection("users").document(userId)
                .set(mapOf("leaderboardOptIn" to false), SetOptions.merge())
                .await()
            Log.d(TAG, "Leaderboard opt-out complete for $userId")
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "Opt-out failed", e)
            Result.failure(e as? Exception ?: Exception(e.message ?: "Opt-out failed"))
        }
    }

    /** Write leaderboard entry (fetches sessions; uses zeros if fetch fails). */
    private suspend fun writeLeaderboardEntry(userId: String) {
        val profile = userProfileService.getProfile(userId)
        val currentUser = auth.currentUser
        val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: currentUser?.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: currentUser?.email?.substringBefore('@')
            ?: "User"
        val photoUrl = profile?.photoUrl?.takeIf { it.isNotBlank() }
            ?: currentUser?.photoUrl?.toString()?.takeIf { it.isNotBlank() }
            ?: ""
        val sessions = firestoreSessionService.fetchSessionsFromFirestore(userId).getOrElse {
            Log.w(TAG, "Could not fetch sessions for leaderboard, using zeros: ${it.message}")
            emptyList()
        }
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L

        val entry = hashMapOf<String, Any>(
            "userId" to userId,
            "displayName" to displayName,
            "photoUrl" to photoUrl,
            "lastUpdated" to now
        )

        // Monotonic window: avoid 366× filter allocations (reduces GC pressure / OOM risk on large histories).
        val sortedAsc = sessions.sortedBy { it.timestamp }
        var lo = 0
        for (days in 366 downTo 1) {
            val cutoff = now - days * oneDayMs
            while (lo < sortedAsc.size && sortedAsc[lo].timestamp < cutoff) lo++
            val stats = aggregateSessions(sortedAsc.subList(lo, sortedAsc.size))
            val score = LeaderboardEntry.computeScore(
                stats.totalDistance,
                stats.co2Conserved,
                stats.totalSessions
            )
            val suffix = "${days}d"
            entry["score$suffix"] = score
            entry["totalDistance$suffix"] = stats.totalDistance
            entry["totalSessions$suffix"] = stats.totalSessions
            entry["co2Conserved$suffix"] = stats.co2Conserved
            entry["co2Emissions$suffix"] = stats.co2Emissions
            entry["topSpeedMps$suffix"] = stats.topSpeedMps
            entry["distanceWalking$suffix"] = stats.distanceWalking
            entry["distanceRunning$suffix"] = stats.distanceRunning
            entry["distanceCycling$suffix"] = stats.distanceCycling
        }

        run {
            val stats = aggregateSessions(sessions)
            val score = LeaderboardEntry.computeScore(
                stats.totalDistance,
                stats.co2Conserved,
                stats.totalSessions
            )
            val suffix = "AllTime"
            entry["score$suffix"] = score
            entry["totalDistance$suffix"] = stats.totalDistance
            entry["totalSessions$suffix"] = stats.totalSessions
            entry["co2Conserved$suffix"] = stats.co2Conserved
            entry["co2Emissions$suffix"] = stats.co2Emissions
            entry["topSpeedMps$suffix"] = stats.topSpeedMps
            entry["distanceWalking$suffix"] = stats.distanceWalking
            entry["distanceRunning$suffix"] = stats.distanceRunning
            entry["distanceCycling$suffix"] = stats.distanceCycling
        }

        firestore.collection("leaderboard").document(userId)
            .set(entry, SetOptions.merge())
            .await()
        Log.d(TAG, "Leaderboard entry written for $userId")
    }

    /** Update leaderboard entry for user (fetch sessions, aggregate, write). Called on sync. */
    suspend fun updateLeaderboardEntry(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("users").document(userId).get().await()
            if ((doc.data?.get("leaderboardOptIn") as? Boolean) != true) return@withContext Result.success(Unit)
            writeLeaderboardEntry(userId)
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "Update leaderboard failed", e)
            Result.failure(e as? Exception ?: Exception(e.message ?: "Update leaderboard failed"))
        }
    }

    private fun aggregateSessions(docs: List<FirestoreSessionDoc>): AggregatedStats {
        var totalDistance = 0.0
        var co2Conserved = 0.0
        var co2Emissions = 0.0
        var topSpeedMps = 0.0
        var distanceWalking = 0.0
        var distanceRunning = 0.0
        var distanceCycling = 0.0
        for (d in docs) {
            totalDistance += d.totalDistance
            co2Conserved += d.co2Conserved
            co2Emissions += d.co2Emissions
            if (d.topSpeedMps > topSpeedMps) topSpeedMps = d.topSpeedMps
            distanceWalking += d.breakdown[ActivityType.WALKING]?.distance ?: 0.0
            distanceRunning += d.breakdown[ActivityType.RUNNING]?.distance ?: 0.0
            distanceCycling += d.breakdown[ActivityType.CYCLING]?.distance ?: 0.0
        }
        return AggregatedStats(
            totalDistance = totalDistance,
            totalSessions = docs.size,
            co2Conserved = co2Conserved,
            co2Emissions = co2Emissions,
            topSpeedMps = topSpeedMps,
            distanceWalking = distanceWalking,
            distanceRunning = distanceRunning,
            distanceCycling = distanceCycling
        )
    }

    private data class AggregatedStats(
        val totalDistance: Double,
        val totalSessions: Int,
        val co2Conserved: Double,
        val co2Emissions: Double,
        val topSpeedMps: Double = 0.0,
        val distanceWalking: Double = 0.0,
        val distanceRunning: Double = 0.0,
        val distanceCycling: Double = 0.0
    )

    /** Fetch reactions for a leaderboard entry from reactions subcollection. */
    private suspend fun fetchReactionsForEntry(targetUserId: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val snapshot = firestore.collection("leaderboard").document(targetUserId)
                .collection("reactions").get().await()
            snapshot.documents.associate { doc ->
                val emoji = doc.getString("emoji") ?: return@associate doc.id to "fire"
                doc.id to emoji
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch reactions for $targetUserId", e)
            emptyMap()
        }
    }

    /** Set or remove reaction. Emoji code: fire, sweat, clap, joy, thumbs, cool. Pass null to remove. */
    suspend fun setReaction(targetUserId: String, reactorUserId: String, emojiCode: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != reactorUserId) {
            return@withContext Result.failure(Exception("Please sign in to react"))
        }
        if (targetUserId == reactorUserId) {
            return@withContext Result.failure(Exception("Cannot react to yourself"))
        }
        try {
            val ref = firestore.collection("leaderboard").document(targetUserId)
                .collection("reactions").document(reactorUserId)
            if (emojiCode == null) {
                ref.delete().await()
            } else {
                ref.set(mapOf("emoji" to emojiCode)).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Set reaction failed", e)
            Result.failure(e)
        }
    }

    /** Fetch leaderboard for category and period. Sorts in memory by category field. */
    suspend fun fetchLeaderboard(
        category: LeaderboardCategory,
        period: LeaderboardPeriod
    ): Result<List<LeaderboardEntry>> = withContext(Dispatchers.IO) {
        try {
            val suffix = period.fieldSuffix()

            val snapshot = firestore.collection("leaderboard").get().await()

            // Parallelize reaction subcollection reads in bounded chunks to avoid N sequential network calls (ANR/timeouts).
            val chunks = snapshot.documents.chunked(20)
            val entries = ArrayList<LeaderboardEntry>(snapshot.size())
            for (chunk in chunks) {
                val part = coroutineScope {
                    chunk.map { doc ->
                        async {
                            val d = doc.data ?: return@async LeaderboardEntry(userId = doc.id)
                            val distKey = "totalDistance$suffix"
                            val sessionsKey = "totalSessions$suffix"
                            val co2Key = "co2Conserved$suffix"
                            val emitKey = "co2Emissions$suffix"
                            val scoreKey = "score$suffix"
                            val topSpeedKey = "topSpeedMps$suffix"
                            val walkKey = "distanceWalking$suffix"
                            val runKey = "distanceRunning$suffix"
                            val cycleKey = "distanceCycling$suffix"
                            val reactions = fetchReactionsForEntry(doc.id)
                            LeaderboardEntry(
                                userId = doc.id,
                                displayName = d["displayName"] as? String,
                                photoUrl = d["photoUrl"] as? String,
                                totalDistance = (d[distKey] as? Number)?.toDouble() ?: 0.0,
                                totalSessions = (d[sessionsKey] as? Number)?.toInt() ?: 0,
                                co2Conserved = (d[co2Key] as? Number)?.toDouble() ?: 0.0,
                                co2Emissions = (d[emitKey] as? Number)?.toDouble() ?: 0.0,
                                score = (d[scoreKey] as? Number)?.toDouble() ?: 0.0,
                                rank = 0,
                                topSpeedMps = (d[topSpeedKey] as? Number)?.toDouble() ?: 0.0,
                                distanceWalking = (d[walkKey] as? Number)?.toDouble() ?: 0.0,
                                distanceRunning = (d[runKey] as? Number)?.toDouble() ?: 0.0,
                                distanceCycling = (d[cycleKey] as? Number)?.toDouble() ?: 0.0,
                                reactions = reactions
                            )
                        }
                    }.awaitAll()
                }
                entries.addAll(part)
            }

            // Only one category is exposed today (CO₂ saved). The exhaustive
            // when keeps the compiler honest if a future category is added —
            // it'll force a deliberate decision here rather than silently
            // sorting by the wrong field.
            val sorted = entries.sortedByDescending { e ->
                when (category) {
                    LeaderboardCategory.CO2_SAVED -> e.co2Conserved
                }
            }.mapIndexed { index, e -> e.copy(rank = index + 1) }

            Result.success(sorted)
        } catch (e: Throwable) {
            Log.e(TAG, "Fetch leaderboard failed", e)
            Result.failure(e as? Exception ?: Exception(e.message ?: "Fetch leaderboard failed"))
        }
    }

    /**
     * Global #1 for [dateKey] (yyyy-MM-dd, session start date as stored in Firestore) and [category].
     * Backed by Cloud Function `dailyLeaderboardTop` and `leaderboardDaily/{date}/users`.
     */
    suspend fun fetchDailyLeaderboardTop(
        category: LeaderboardCategory,
        dateKey: String
    ): Result<List<LeaderboardEntry>> = withContext(Dispatchers.IO) {
        try {
            val user = auth.currentUser
                ?: return@withContext Result.failure(Exception("Please sign in"))
            val token = user.getIdToken(false).await().token
                ?: return@withContext Result.failure(Exception("Auth failed"))
            val jsonBody = JSONObject().apply {
                put("dateKey", dateKey)
                put("category", category.name)
            }
            val request = Request.Builder()
                .url(DAILY_LEADERBOARD_TOP_URL)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val err = runCatching { JSONObject(body).optString("error", body) }.getOrDefault(body)
                Log.e(TAG, "dailyLeaderboardTop HTTP ${response.code}: $err")
                return@withContext Result.failure(Exception(err.ifBlank { "Daily leaderboard failed" }))
            }
            val jo = JSONObject(body)
            val entryObj = jo.optJSONObject("entry")
            if (entryObj == null) {
                return@withContext Result.success(emptyList())
            }
            val uid = entryObj.optString("userId", "")
            val reactions = if (uid.isNotEmpty()) fetchReactionsForEntry(uid) else emptyMap()
            val entry = LeaderboardEntry(
                userId = uid,
                displayName = entryObj.optString("displayName").takeIf { it.isNotEmpty() },
                photoUrl = entryObj.optString("photoUrl").takeIf { it.isNotEmpty() },
                totalDistance = entryObj.optDouble("totalDistance", 0.0),
                totalSessions = entryObj.optInt("totalSessions", 0),
                co2Conserved = entryObj.optDouble("co2Conserved", 0.0),
                co2Emissions = entryObj.optDouble("co2Emissions", 0.0),
                score = entryObj.optDouble("score", 0.0),
                rank = entryObj.optInt("rank", 1),
                topSpeedMps = entryObj.optDouble("topSpeedMps", 0.0),
                distanceWalking = entryObj.optDouble("distanceWalking", 0.0),
                distanceRunning = entryObj.optDouble("distanceRunning", 0.0),
                distanceCycling = entryObj.optDouble("distanceCycling", 0.0),
                reactions = reactions
            )
            Result.success(listOf(entry))
        } catch (e: Exception) {
            Log.e(TAG, "fetchDailyLeaderboardTop failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private val DAILY_LEADERBOARD_TOP_URL =
            "${BuildConfig.FUNCTIONS_BASE_URL}/dailyLeaderboardTop"

        @Volatile
        private var instance: LeaderboardService? = null
        fun getInstance(): LeaderboardService = instance ?: synchronized(this) {
            instance ?: LeaderboardService().also { instance = it }
        }
    }
}
