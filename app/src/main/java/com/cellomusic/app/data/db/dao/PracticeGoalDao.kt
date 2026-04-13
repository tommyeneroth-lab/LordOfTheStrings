package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.PracticeGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeGoalDao {

    @Query("SELECT * FROM practice_goals WHERE isCompleted = 0 ORDER BY endDateMs ASC")
    fun getActiveGoals(): Flow<List<PracticeGoalEntity>>

    @Query("SELECT * FROM practice_goals WHERE isCompleted = 1 ORDER BY completedAtMs DESC")
    fun getCompletedGoals(): Flow<List<PracticeGoalEntity>>

    @Query("SELECT * FROM practice_goals ORDER BY createdAtMs DESC")
    fun getAllGoals(): Flow<List<PracticeGoalEntity>>

    @Query("SELECT * FROM practice_goals WHERE id = :id")
    suspend fun getGoalById(id: Long): PracticeGoalEntity?

    @Query("""
        UPDATE practice_goals
        SET completedCount = :count, isCompleted = :completed, completedAtMs = :completedAtMs
        WHERE id = :id
    """)
    suspend fun updateProgress(id: Long, count: Int, completed: Boolean, completedAtMs: Long?)

    @Insert
    suspend fun insert(goal: PracticeGoalEntity): Long

    @Update
    suspend fun update(goal: PracticeGoalEntity)

    @Delete
    suspend fun delete(goal: PracticeGoalEntity)
}
