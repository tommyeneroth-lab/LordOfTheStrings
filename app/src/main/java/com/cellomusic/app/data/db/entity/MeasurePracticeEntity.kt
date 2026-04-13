package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "measure_practice")
data class MeasurePracticeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long? = null,
    val scoreId: String,
    val measureNumber: Int,
    val durationSec: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)
