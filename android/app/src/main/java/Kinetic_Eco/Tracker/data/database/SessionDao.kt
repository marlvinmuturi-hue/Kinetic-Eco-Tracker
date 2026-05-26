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

    /** CO2 saved/emitted summed per ISO week ("yyyy-WW"). */
    @Query("""
        SELECT strftime('%Y-%W', date) AS period,
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
}
