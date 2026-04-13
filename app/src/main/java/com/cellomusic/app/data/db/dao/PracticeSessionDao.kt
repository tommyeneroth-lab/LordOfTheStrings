package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeSessionDao {

    @Query("SELECT * FROM practice_sessions ORDER BY timestampMs DESC")
    fun getAll(): Flow<List<PracticeSessionEntity>>

    @Query("SELECT * FROM practice_sessions ORDER BY timestampMs DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<PracticeSessionEntity>>

    @Query("SELECT * FROM practice_sessions WHERE timestampMs BETWEEN :startMs AND :endMs ORDER BY timestampMs DESC")
    fun getSessionsInRange(startMs: Long, endMs: Long): Flow<List<PracticeSessionEntity>>

    @Query("SELECT * FROM practice_sessions WHERE scoreId = :scoreId ORDER BY timestampMs DESC")
    fun getSessionsByScore(scoreId: String): Flow<List<PracticeSessionEntity>>

    /** Distinct dates (as day-start millis) on which the user practiced. */
    @Query("SELECT DISTINCT (timestampMs / 86400000) * 86400000 AS dayMs FROM practice_sessions ORDER BY dayMs DESC")
    suspend fun getDistinctPracticeDays(): List<Long>

    /** Total minutes practiced per day in a date range. */
    @Query("""
        SELECT (timestampMs / 86400000) AS dayKey, SUM(durationMin) AS totalMin
        FROM practice_sessions
        WHERE timestampMs BETWEEN :startMs AND :endMs
        GROUP BY dayKey ORDER BY dayKey ASC
    """)
    suspend fun getDailyTotals(startMs: Long, endMs: Long): List<DailyTotal>

    @Query("SELECT SUM(durationMin) FROM practice_sessions WHERE timestampMs BETWEEN :startMs AND :endMs")
    suspend fun getTotalMinutesInRange(startMs: Long, endMs: Long): Int?

    @Query("SELECT COUNT(*) FROM practice_sessions WHERE timestampMs BETWEEN :startMs AND :endMs AND category = :category")
    suspend fun countSessionsInRangeByCategory(startMs: Long, endMs: Long, category: String): Int

    @Query("SELECT SUM(durationMin) FROM practice_sessions WHERE timestampMs BETWEEN :startMs AND :endMs AND category = :category")
    suspend fun getTotalMinutesInRangeByCategory(startMs: Long, endMs: Long, category: String): Int?

    @Insert
    suspend fun insert(session: PracticeSessionEntity): Long

    @Update
    suspend fun update(session: PracticeSessionEntity)

    @Delete
    suspend fun delete(session: PracticeSessionEntity)

    @Query("SELECT COUNT(*) FROM practice_sessions WHERE timestampMs BETWEEN :startMs AND :endMs")
    suspend fun countSessionsInRange(startMs: Long, endMs: Long): Int

    @Query("SELECT COUNT(*) FROM practice_sessions")
    suspend fun getCount(): Int

    data class DailyTotal(val dayKey: Long, val totalMin: Int)
}
