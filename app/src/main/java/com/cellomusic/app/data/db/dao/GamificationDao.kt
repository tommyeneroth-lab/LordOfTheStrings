package com.cellomusic.app.data.db.dao

import androidx.room.*
import com.cellomusic.app.data.db.entity.GamificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GamificationDao {

    @Query("SELECT * FROM gamification WHERE id = 1")
    fun getProfile(): Flow<GamificationEntity?>

    @Query("SELECT * FROM gamification WHERE id = 1")
    suspend fun getProfileSync(): GamificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: GamificationEntity)

    @Query("""
        UPDATE gamification
        SET totalPoints = totalPoints + :points,
            currentLevel = (totalPoints + :points) / 10000,
            lifetimeMinutes = lifetimeMinutes + :minutes
        WHERE id = 1
    """)
    suspend fun addPointsAndMinutes(points: Int, minutes: Int)

    @Query("""
        UPDATE gamification
        SET currentStreakDays = :streak,
            longestStreakDays = MAX(longestStreakDays, :streak),
            lastPracticeDateMs = :dateMs
        WHERE id = 1
    """)
    suspend fun updateStreak(streak: Int, dateMs: Long)
}
