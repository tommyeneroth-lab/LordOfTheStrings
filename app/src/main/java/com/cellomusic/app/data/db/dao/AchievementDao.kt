package com.cellomusic.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellomusic.app.data.db.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements ORDER BY unlockedAtMs DESC")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Query("SELECT id FROM achievements")
    suspend fun getUnlockedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(achievement: AchievementEntity)

    @Query("SELECT COUNT(*) FROM achievements")
    suspend fun count(): Int
}
