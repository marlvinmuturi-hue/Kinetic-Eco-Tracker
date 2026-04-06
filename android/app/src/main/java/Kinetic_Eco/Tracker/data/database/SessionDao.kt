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
}
