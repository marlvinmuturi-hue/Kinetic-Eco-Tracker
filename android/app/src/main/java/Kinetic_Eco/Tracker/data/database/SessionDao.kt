package Kinetic_Eco.Tracker.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY createdAt DESC")
    fun getAllSessions(userId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE userId = :userId AND date = :date")
    suspend fun getSessionByDate(userId: String, date: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSession(userId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity): Int

    @Delete
    suspend fun deleteSession(session: SessionEntity): Int

    @Query("DELETE FROM sessions WHERE userId = :userId")
    suspend fun deleteAllSessions(userId: String)

    // ── Firestore sync backfill ───────────────────────────────────────────────

    /** Sessions not yet confirmed in Firestore, oldest first (so backfill preserves chronology). */
    @Query("SELECT * FROM sessions WHERE userId = :userId AND synced = 0 ORDER BY createdAt ASC")
    suspend fun getUnsyncedSessions(userId: String): List<SessionEntity>

    /** Mark a session as confirmed-synced to Firestore. */
    @Query("UPDATE sessions SET synced = 1 WHERE id = :sessionId")
    suspend fun markSessionSynced(sessionId: String)

    // ── Time-series aggregation ───────────────────────────────────────────────

    /** CO2 saved/emitted summed per calendar day. [fromDate] is "yyyy-MM-dd". */
    @Query("""
        SELECT date AS period,
               SUM(co2Conserved) AS co2Conserved,
               SUM(co2Emissions) AS co2Emissions
        FROM sessions
        WHERE userId = :userId AND date >= :fromDate
        GROUP BY date
        ORDER BY date ASC
    """)
    suspend fun getCo2PerDay(userId: String, fromDate: String): List<Co2ByPeriod>

    /** CO2 saved/emitted summed per week ("yyyy-WW", week starts Monday). */
    @Query("""
        SELECT strftime('%Y-%W', date(date, '-1 day')) AS period,
               SUM(co2Conserved) AS co2Conserved,
               SUM(co2Emissions) AS co2Emissions
        FROM sessions
        WHERE userId = :userId AND date >= :fromDate
        GROUP BY period
        ORDER BY period ASC
    """)
    suspend fun getCo2PerWeek(userId: String, fromDate: String): List<Co2ByPeriod>

    /** CO2 saved/emitted summed per calendar month ("yyyy-MM"). */
    @Query("""
        SELECT strftime('%Y-%m', date) AS period,
               SUM(co2Conserved) AS co2Conserved,
               SUM(co2Emissions) AS co2Emissions
        FROM sessions
        WHERE userId = :userId AND date >= :fromDate
        GROUP BY period
        ORDER BY period ASC
    """)
    suspend fun getCo2PerMonth(userId: String, fromDate: String): List<Co2ByPeriod>

    /**
     * Distinct dates (desc) on which any CO2 was conserved.
     * Used by streak computation — caller walks the list and counts consecutive days.
     */
    @Query("""
        SELECT DISTINCT date
        FROM sessions
        WHERE userId = :userId AND co2Conserved > 0
        ORDER BY date DESC
    """)
    suspend fun getActiveDays(userId: String): List<String>

    /**
     * Full session rows for a closed date range.
     * The [breakdown] JSON is decoded by Room's TypeConverter, letting the caller
     * compute per-activity CO2 split without a second round-trip.
     */
    @Query("""
        SELECT * FROM sessions
        WHERE userId = :userId AND date >= :fromDate AND date <= :toDate
        ORDER BY date ASC
    """)
    suspend fun getSessionsInRange(
        userId: String,
        fromDate: String,
        toDate: String
    ): List<SessionEntity>

    // ── Route endpoints (recurring-trip detection) ───────────────────────────

    /**
     * Start/end coordinates for sessions that have them, newest first.
     *
     * **Deliberately a projection, not `SELECT *`.** Selecting the entity pulls
     * `routePathJson` through `Converters.toRoutePath`, Gson-parsing every GPS point
     * of every session into memory — the documented OOM on this app's Room path. This
     * query reads four doubles and a timestamp per row instead, so it stays flat
     * regardless of how long the routes are.
     *
     * Rows whose endpoints are still NULL (pre-v9, not yet backfilled) are excluded
     * rather than defaulted, because 0,0 is a real coordinate and would cluster
     * unrelated indoor sessions together off the coast of Africa. The latitude range
     * check additionally filters the out-of-range sentinel written by
     * [markEndpointsUnavailable].
     */
    @Query("""
        SELECT id, date, startLat, startLng, endLat, endLng,
               totalDistance, totalDuration, co2Conserved, co2Emissions,
               createdAt, breakdown
        FROM sessions
        WHERE userId = :userId
          AND createdAt >= :sinceMs
          AND startLat IS NOT NULL AND startLng IS NOT NULL
          AND endLat IS NOT NULL AND endLng IS NOT NULL
          AND startLat BETWEEN -90.0 AND 90.0
          AND endLat BETWEEN -90.0 AND 90.0
        ORDER BY createdAt DESC
    """)
    suspend fun getTripEndpoints(userId: String, sinceMs: Long): List<TripEndpointRow>

    /** Ids still awaiting endpoint backfill. Bounded by [limit] to cap peak memory. */
    @Query("""
        SELECT id FROM sessions
        WHERE userId = :userId AND startLat IS NULL
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getSessionIdsMissingEndpoints(userId: String, limit: Int): List<String>

    /**
     * Write endpoints for one session. Nulls are written as-is so a session with no
     * GPS is not re-examined forever — see [markEndpointsUnavailable].
     */
    @Query("""
        UPDATE sessions
        SET startLat = :startLat, startLng = :startLng,
            endLat = :endLat, endLng = :endLng
        WHERE id = :sessionId
    """)
    suspend fun updateRouteEndpoints(
        sessionId: String,
        startLat: Double?,
        startLng: Double?,
        endLat: Double?,
        endLng: Double?
    )

    /**
     * Park a route-less session outside the backfill queue.
     *
     * Writes an out-of-range latitude sentinel rather than leaving NULL, so "no GPS, we
     * checked" is distinguishable from "not looked at yet". Without it the backfill
     * would reload the same geometry-free sessions on every pass forever.
     * [getTripEndpoints] filters the sentinel out via its latitude range check.
     */
    @Query("""
        UPDATE sessions
        SET startLat = 999.0, startLng = 999.0, endLat = 999.0, endLng = 999.0
        WHERE id = :sessionId
    """)
    suspend fun markEndpointsUnavailable(sessionId: String)
}

/**
 * Endpoint projection of a session — everything recurring-trip detection needs and
 * nothing it doesn't. Notably absent: `routePathJson`.
 */
@TypeConverters(Converters::class)
data class TripEndpointRow(
    val id: String,
    val date: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val totalDistance: Double,
    val totalDuration: Long,
    val co2Conserved: Double,
    val co2Emissions: Double,
    val createdAt: Long,
    val breakdown: Map<Kinetic_Eco.Tracker.data.ActivityType, ActivityBreakdownEntity>
)
