package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practice_goals")
data class PracticeGoalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val category: String,       // INTONATION, VIBRATO, BOW_TECHNIQUE, MEMORIZATION, SCALES, SIGHT_READING, GENERAL
    val periodType: String,     // WEEKLY, MONTHLY, HALF_YEARLY
    val targetCount: Int,       // e.g. 7 sessions, or 300 minutes
    val targetUnit: String,     // SESSIONS or MINUTES
    val startDateMs: Long,
    val endDateMs: Long,
    val completedCount: Int = 0,
    val isCompleted: Boolean = false,
    val completedAtMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)
