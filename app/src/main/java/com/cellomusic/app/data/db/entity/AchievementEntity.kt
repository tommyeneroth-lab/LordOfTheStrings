package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per unlocked achievement. Definitions (title, description, icon)
 * live in code in [com.cellomusic.app.domain.achievement.AchievementCatalog]
 * so they can evolve with app releases without DB migrations. This table
 * only records which IDs the user has unlocked and when.
 */
@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey
    val id: String,
    val unlockedAtMs: Long
)
