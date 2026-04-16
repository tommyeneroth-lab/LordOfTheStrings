package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.GamificationDao
import com.cellomusic.app.data.db.entity.GamificationEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/**
 * Result of [GamificationRepository.updateStreak], telling the caller not
 * just what the new value is but *how* it changed — so the UI can celebrate
 * milestones and surface the "streak saver" grace when it kicks in.
 */
sealed class StreakResult {
    /** First session ever (or after a full reset). */
    data class Started(val newStreak: Int) : StreakResult()
    /** Already practiced today — nothing changed. */
    data class SameDay(val streak: Int) : StreakResult()
    /** Practiced yesterday; clean continuation. */
    data class Continued(val newStreak: Int, val isMilestone: Boolean) : StreakResult()
    /** Two-day gap — the grace day forgave a missed day. */
    data class Saved(val newStreak: Int, val isMilestone: Boolean) : StreakResult()
    /** More than a grace day's worth missed — streak restarted at 1. */
    data class Reset(val prevStreak: Int) : StreakResult()
}

/** Milestone streak lengths that trigger extra celebration + bonus points. */
private val STREAK_MILESTONES = setOf(3, 7, 14, 30, 60, 100, 180, 365)

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

    /**
     * Update the practice streak. Call after saving a session.
     *
     * Streak Saver: missing a single day no longer breaks the streak.
     * The user is allowed one "rest day" gap — if they practiced yesterday
     * or the day before yesterday, the streak continues. This matches the
     * common pattern of taking one day off per week.
     *
     * Returns a [StreakResult] describing what happened so the caller can
     * decide whether to toast "streak saved!" or fire milestone celebrations.
     */
    suspend fun updateStreak(): StreakResult {
        val profile = dao.getProfileSync() ?: return StreakResult.Started(1)
        val today = dayStart(System.currentTimeMillis())
        val lastDay = dayStart(profile.lastPracticeDateMs)
        val gapDays = (today - lastDay) / 86_400_000L
        val hasNeverPracticed = profile.lastPracticeDateMs == 0L

        val result: StreakResult = when {
            hasNeverPracticed                -> StreakResult.Started(1)
            today == lastDay                 -> StreakResult.SameDay(profile.currentStreakDays)
            gapDays == 1L                    -> {
                val newStreak = profile.currentStreakDays + 1
                StreakResult.Continued(newStreak, newStreak in STREAK_MILESTONES)
            }
            gapDays == 2L                    -> {
                val newStreak = profile.currentStreakDays + 1
                StreakResult.Saved(newStreak, newStreak in STREAK_MILESTONES)
            }
            else                             -> StreakResult.Reset(profile.currentStreakDays)
        }

        val newStreakValue = when (result) {
            is StreakResult.Started    -> result.newStreak
            is StreakResult.SameDay    -> result.streak
            is StreakResult.Continued  -> result.newStreak
            is StreakResult.Saved      -> result.newStreak
            is StreakResult.Reset      -> 1
        }
        dao.updateStreak(newStreakValue, System.currentTimeMillis())
        return result
    }

    /** Award bonus points for hitting a streak milestone. Returns the bonus. */
    suspend fun addStreakMilestoneBonus(streakDays: Int): Int {
        // Bonus scales with the milestone size, capped so day-365 doesn't
        // overwhelm normal progression.
        val bonus = (streakDays * 100).coerceAtMost(10_000)
        dao.addPointsAndMinutes(bonus, 0)
        return bonus
    }

    /** Add an arbitrary bonus (comeback, variety, PB, etc.). Returns the bonus. */
    suspend fun addBonusPoints(bonus: Int): Int {
        if (bonus <= 0) return 0
        dao.addPointsAndMinutes(bonus, 0)
        return bonus
    }

    /**
     * Whole-day gap since the user last logged a session. Read before
     * updateStreak() mutates lastPracticeDateMs, used to trigger the
     * comeback bonus when someone returns after a break of 3+ days.
     *
     * Returns -1 if the user has never practiced before, 0 if they
     * practiced today (including just now).
     */
    suspend fun getDaysSinceLastPractice(): Long {
        val profile = dao.getProfileSync() ?: return -1L
        if (profile.lastPracticeDateMs == 0L) return -1L
        val today = dayStart(System.currentTimeMillis())
        val lastDay = dayStart(profile.lastPracticeDateMs)
        return (today - lastDay) / 86_400_000L
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
