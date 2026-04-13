package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.GamificationDao
import com.cellomusic.app.data.db.entity.GamificationEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Manages points, levels, and streak tracking. */
class GamificationRepository(private val dao: GamificationDao) {

    fun getProfile(): Flow<GamificationEntity?> = dao.getProfile()

    /** Ensure the profile row exists (call once at startup). */
    suspend fun ensureProfile() {
        if (dao.getProfileSync() == null) {
            dao.upsert(GamificationEntity())
        }
    }

    /**
     * Award points for a completed session.
     * Returns true if the user leveled up.
     */
    suspend fun addSessionPoints(durationMin: Int): Boolean {
        val points = durationMin * 10
        val before = dao.getProfileSync() ?: GamificationEntity()
        val oldLevel = before.totalPoints / 10_000
        dao.addPointsAndMinutes(points, durationMin)
        val after = dao.getProfileSync() ?: return false
        val newLevel = after.totalPoints / 10_000
        return newLevel > oldLevel
    }

    /** Award bonus points for goal completion. Returns true if leveled up. */
    suspend fun addGoalBonus(): Boolean {
        val before = dao.getProfileSync() ?: GamificationEntity()
        val oldLevel = before.totalPoints / 10_000
        dao.addPointsAndMinutes(1000, 0)
        val after = dao.getProfileSync() ?: return false
        return (after.totalPoints / 10_000) > oldLevel
    }

    /** Update the practice streak. Call after saving a session. */
    suspend fun updateStreak() {
        val profile = dao.getProfileSync() ?: return
        val today = dayStart(System.currentTimeMillis())
        val lastDay = dayStart(profile.lastPracticeDateMs)

        val newStreak = when {
            today == lastDay -> profile.currentStreakDays  // already counted today
            today - lastDay == 86_400_000L -> profile.currentStreakDays + 1  // consecutive day
            else -> 1  // streak broken, restart
        }
        dao.updateStreak(newStreak, System.currentTimeMillis())
    }

    private fun dayStart(ms: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
