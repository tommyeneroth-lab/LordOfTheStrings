package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practice_sessions")
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    val pieceName: String,
    val notes: String,
    val durationMin: Int,
    val selfEval: Int,              // 1–5
    val challenge: String = "",     // "Today's challenge"
    val nextTimeNote: String = "",  // "Think about next time"
    val recordingPath: String? = null,
    val scoreId: String? = null,    // links to ScoreEntity.scoreId
    val category: String = "",      // INTONATION, VIBRATO, BOW_TECHNIQUE, etc.
    val totalPoints: Int = 0,       // 10 * durationMin, computed at save
    val bpm: Int = 0                // metronome BPM used during session, 0 if unknown
)
