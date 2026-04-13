package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table holding the player's gamification profile. */
@Entity(tableName = "gamification")
data class GamificationEntity(
    @PrimaryKey
    val id: Int = 1,
    val totalPoints: Int = 0,
    val currentLevel: Int = 0,
    val lifetimeMinutes: Int = 0,
    val longestStreakDays: Int = 0,
    val currentStreakDays: Int = 0,
    val lastPracticeDateMs: Long = 0L
)
