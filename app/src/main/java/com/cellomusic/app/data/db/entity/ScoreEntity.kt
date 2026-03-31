package com.cellomusic.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scoreId: String,          // UUID from Score domain model
    val title: String,
    val composer: String?,
    val arranger: String?,
    val workNumber: String?,
    val filePathJson: String,      // path to serialized Score JSON
    val filePathOriginal: String,  // path to original file (MusicXML/PDF/JPEG)
    val thumbnailPath: String?,    // path to first-page thumbnail
    val sourceType: String,        // "MUSICXML", "JPEG_OMR", "PDF_OMR", "IMPORTED"
    val dateAdded: Long = System.currentTimeMillis(),
    val lastOpened: Long = 0L,
    val durationSeconds: Int = 0,
    val measureCount: Int = 0,
    val keySignature: String = "C major",
    val timeSignatureTop: Int = 4,
    val timeSignatureBottom: Int = 4,
    val lastMeasure: Int = 1,      // last viewed/played measure for resume
    val isFavorite: Boolean = false,
    val tags: String = "",         // comma-separated tags
    /** "NONE" = not OMR | "PROCESSING" = OMR running | "DONE" = OMR ok | "FAILED" = OMR error */
    val omrStatus: String = "NONE",
    val noteCount: Int = 0
)
