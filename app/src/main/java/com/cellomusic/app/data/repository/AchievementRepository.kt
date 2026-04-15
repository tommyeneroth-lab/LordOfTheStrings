package com.cellomusic.app.data.repository

import com.cellomusic.app.data.db.dao.AchievementDao
import com.cellomusic.app.data.db.dao.GamificationDao
import com.cellomusic.app.data.db.dao.PracticeSessionDao
import com.cellomusic.app.data.db.entity.AchievementEntity
import com.cellomusic.app.domain.achievement.AchievementCatalog
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/**
 * Evaluates which achievements should be unlocked given the current state
 * of the practice database, and persists unlocks. Call [evaluateAfterSession]
 * once per saved session — it is cheap (a handful of aggregate queries).
 */
class AchievementRepository(
    private val achievementDao: AchievementDao,
    private val sessionDao: PracticeSessionDao,
    private val gamificationDao: GamificationDao
) {

    fun observeUnlocked(): Flow<List<AchievementEntity>> = achievementDao.observeAll()

    suspend fun getUnlockedIds(): Set<String> = achievementDao.getUnlockedIds().toSet()

    /**
     * Recompute achievement stats and unlock any newly-earned badges.
     * Returns the list of achievements that transitioned from locked → unlocked
     * on this call, so the UI can celebrate them.
     *
     * @param lastSessionTimestampMs timestamp of the session that just triggered
     *   this pass, used by time-of-day achievements. Pass 0 to fall back to "now".
     */
    suspend fun evaluateAfterSession(lastSessionTimestampMs: Long = 0L): List<AchievementCatalog.Def> {
        val already = getUnlockedIds()
        val stats = computeStats(lastSessionTimestampMs)
        val now = System.currentTimeMillis()
        val newly = mutableListOf<AchievementCatalog.Def>()
        for (def in AchievementCatalog.ALL) {
            if (def.id in already) continue
            if (def.check(stats)) {
                achievementDao.insert(AchievementEntity(def.id, now))
                newly.add(def)
            }
        }
        return newly
    }

    private suspend fun computeStats(lastSessionTimestampMs: Long): AchievementCatalog.AchievementStats {
        val profile = gamificationDao.getProfileSync()
        val tsForHour = if (lastSessionTimestampMs > 0L) lastSessionTimestampMs else System.currentTimeMillis()
        val hour = Calendar.getInstance().apply { timeInMillis = tsForHour }
            .get(Calendar.HOUR_OF_DAY)
        return AchievementCatalog.AchievementStats(
            totalSessions = sessionDao.getCount(),
            lifetimeMinutes = sessionDao.getLifetimeMinutes(),
            maxSingleSessionMinutes = sessionDao.getMaxSingleSessionMinutes(),
            longestStreak = profile?.longestStreakDays ?: 0,
            metronomeSessions = sessionDao.countMetronomeSessions(),
            fiveStarSessions = sessionDao.countFiveStarSessions(),
            distinctCategories = sessionDao.countDistinctCategories(),
            intonationMinutes = sessionDao.getLifetimeMinutesByCategory("INTONATION"),
            vibratoMinutes = sessionDao.getLifetimeMinutesByCategory("VIBRATO"),
            bowTechniqueMinutes = sessionDao.getLifetimeMinutesByCategory("BOW_TECHNIQUE"),
            scalesMinutes = sessionDao.getLifetimeMinutesByCategory("SCALES"),
            sightReadingMinutes = sessionDao.getLifetimeMinutesByCategory("SIGHT_READING"),
            memorizationMinutes = sessionDao.getLifetimeMinutesByCategory("MEMORIZATION"),
            lastSessionHour = hour
        )
    }
}
