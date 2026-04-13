package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tempo_logs")
data class TempoLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long? = null,
    val scoreId: String? = null,
    val pieceName: String,
    val bpm: Int,
    val timestampMs: Long = System.currentTimeMillis()
)
