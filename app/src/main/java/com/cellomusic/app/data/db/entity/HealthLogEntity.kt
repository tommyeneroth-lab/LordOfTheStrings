package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_logs")
data class HealthLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val strainLevel: Int = 0,          // 0–5 (0 = not set)
    val strainArea: String = "",       // NECK, LEFT_HAND, RIGHT_HAND, BACK, SHOULDER
    val breakTakenMin: Int = 0,
    val notes: String = ""
)
