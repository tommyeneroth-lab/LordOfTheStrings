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

    // ── Achievement-support queries ──────────────────────────────────────

    /** Longest single session ever, in minutes (0 if no sessions). */
    @Query("SELECT IFNULL(MAX(durationMin), 0) FROM practice_sessions")
    suspend fun getMaxSingleSessionMinutes(): Int

    /** Lifetime total minutes across all sessions. */
    @Query("SELECT IFNULL(SUM(durationMin), 0) FROM practice_sessions")
    suspend fun getLifetimeMinutes(): Int

    /** How many sessions have a non-zero BPM recorded (metronome used). */
    @Query("SELECT COUNT(*) FROM practice_sessions WHERE bpm > 0")
    suspend fun countMetronomeSessions(): Int

    /** How many sessions the user rated 5/5. */
    @Query("SELECT COUNT(*) FROM practice_sessions WHERE selfEval >= 5")
    suspend fun countFiveStarSessions(): Int

    /** Number of distinct non-empty categories practiced. */
    @Query("SELECT COUNT(DISTINCT category) FROM practice_sessions WHERE category <> ''")
    suspend fun countDistinctCategories(): Int

    /** Total lifetime minutes for one category. */
    @Query("SELECT IFNULL(SUM(durationMin), 0) FROM practice_sessions WHERE category = :category")
    suspend fun getLifetimeMinutesByCategory(category: String): Int

    /** Total minutes grouped by category in a date range. */
    @Query("""
        SELECT category, SUM(durationMin) AS totalMin
        FROM practice_sessions
        WHERE timestampMs BETWEEN :startMs AND :endMs
        GROUP BY category ORDER BY totalMin DESC
    """)
    suspend fun getCategoryTotals(startMs: Long, endMs: Long): List<CategoryTotal>

    /** Session count in a date range. */
    @Query("SELECT AVG(selfEval) FROM practice_sessions WHERE timestampMs BETWEEN :startMs AND :endMs")
    suspend fun getAvgSelfEvalInRange(startMs: Long, endMs: Long): Float?

    data class DailyTotal(val dayKey: Long, val totalMin: Int)
    data class CategoryTotal(val category: String, val totalMin: Int)
}
