package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.PracticeGoalDao
import com.cellomusic.app.data.db.dao.PracticeSessionDao
import com.cellomusic.app.data.db.entity.PracticeGoalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GoalRepository(
    private val goalDao: PracticeGoalDao,
    private val sessionDao: PracticeSessionDao
) {

    fun getActiveGoals(): Flow<List<PracticeGoalEntity>> = goalDao.getActiveGoals()
    fun getCompletedGoals(): Flow<List<PracticeGoalEntity>> = goalDao.getCompletedGoals()
    fun getAllGoals(): Flow<List<PracticeGoalEntity>> = goalDao.getAllGoals()

    suspend fun createGoal(goal: PracticeGoalEntity): Long = goalDao.insert(goal)
    suspend fun deleteGoal(goal: PracticeGoalEntity) = goalDao.delete(goal)

    /**
     * Recalculate progress for all active goals after a session is saved.
     * Returns list of goal IDs that were just completed (for fireworks trigger).
     */
    suspend fun refreshGoalProgress(): List<Long> {
        val now = System.currentTimeMillis()
        val completedIds = mutableListOf<Long>()
        val activeGoals = goalDao.getActiveGoals().first()

        for (goal in activeGoals) {
            if (goal.endDateMs < now) continue // expired

            val progress = when (goal.targetUnit) {
                "SESSIONS" -> {
                    if (goal.category.isNotEmpty()) {
                        sessionDao.countSessionsInRangeByCategory(goal.startDateMs, goal.endDateMs, goal.category)
                    } else {
                        sessionDao.countSessionsInRange(goal.startDateMs, goal.endDateMs)
                    }
                }
                "MINUTES" -> {
                    if (goal.category.isNotEmpty()) {
                        sessionDao.getTotalMinutesInRangeByCategory(goal.startDateMs, goal.endDateMs, goal.category) ?: 0
                    } else {
                        sessionDao.getTotalMinutesInRange(goal.startDateMs, goal.endDateMs) ?: 0
                    }
                }
                else -> 0
            }

            val completed = progress >= goal.targetCount
            goalDao.updateProgress(
                id = goal.id,
                count = progress,
                completed = completed,
                completedAtMs = if (completed && !goal.isCompleted) now else goal.completedAtMs
            )
            if (completed && !goal.isCompleted) {
                completedIds.add(goal.id)
            }
        }

        return completedIds
    }
}
