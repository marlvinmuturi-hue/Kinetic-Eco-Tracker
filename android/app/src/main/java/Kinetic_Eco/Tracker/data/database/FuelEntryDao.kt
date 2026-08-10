package Kinetic_Eco.Tracker.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FuelEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FuelEntryEntity)

    @Query("DELETE FROM fuel_entries WHERE id = :id")
    suspend fun delete(id: String)

    /** Oldest first — [FuelEconomyCalculator] walks consecutive pairs in time order. */
    @Query("SELECT * FROM fuel_entries WHERE userId = :userId ORDER BY filledAtMs ASC")
    suspend fun getAll(userId: String): List<FuelEntryEntity>

    /** Newest first, for the log UI. */
    @Query("SELECT * FROM fuel_entries WHERE userId = :userId ORDER BY filledAtMs DESC")
    fun observeRecent(userId: String): Flow<List<FuelEntryEntity>>

    @Query("SELECT * FROM fuel_entries WHERE userId = :userId AND synced = 0")
    suspend fun getUnsynced(userId: String): List<FuelEntryEntity>

    @Query("UPDATE fuel_entries SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT id FROM fuel_entries WHERE userId = :userId")
    suspend fun getAllIds(userId: String): List<String>

    /**
     * Per-session driving distance between two fill-ups.
     *
     * A projection, not `SELECT *`: pulling the entity would drag every GPS point
     * through `Converters.toRoutePath`, which is the documented Room OOM on this app's
     * history. Only the breakdown map is needed, and it holds a handful of numbers.
     *
     * Motorised distance has to come from `breakdown` rather than `totalDistance`
     * because a single session can mix walking and driving — counting the whole
     * session as driving would inflate the distance and flatter the economy figure.
     */
    @Query("""
        SELECT createdAt, totalDistance, breakdown
        FROM sessions
        WHERE userId = :userId AND createdAt > :fromMs AND createdAt <= :toMs
    """)
    suspend fun getBreakdownsInRange(
        userId: String,
        fromMs: Long,
        toMs: Long
    ): List<SessionBreakdownRow>
}

/** Minimal projection for summing motorised distance over an interval. */
@androidx.room.TypeConverters(Converters::class)
data class SessionBreakdownRow(
    val createdAt: Long,
    val totalDistance: Double,
    val breakdown: Map<Kinetic_Eco.Tracker.data.ActivityType, ActivityBreakdownEntity>
)
