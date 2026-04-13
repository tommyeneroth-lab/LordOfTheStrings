package com.cellomusic.app.data.db.migration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Migration from DB version 3 to 4.
 * Creates the gamified practice journal tables and migrates existing
 * JSON-based practice entries into Room.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create practice_sessions table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS practice_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestampMs INTEGER NOT NULL,
                pieceName TEXT NOT NULL,
                notes TEXT NOT NULL,
                durationMin INTEGER NOT NULL,
                selfEval INTEGER NOT NULL,
                challenge TEXT NOT NULL DEFAULT '',
                nextTimeNote TEXT NOT NULL DEFAULT '',
                recordingPath TEXT,
                scoreId TEXT,
                category TEXT NOT NULL DEFAULT '',
                totalPoints INTEGER NOT NULL DEFAULT 0,
                bpm INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 2. Create practice_goals table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS practice_goals (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                periodType TEXT NOT NULL,
                targetCount INTEGER NOT NULL,
                targetUnit TEXT NOT NULL,
                startDateMs INTEGER NOT NULL,
                endDateMs INTEGER NOT NULL,
                completedCount INTEGER NOT NULL DEFAULT 0,
                isCompleted INTEGER NOT NULL DEFAULT 0,
                completedAtMs INTEGER,
                createdAtMs INTEGER NOT NULL
            )
        """)

        // 3. Create gamification table (single-row profile)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS gamification (
                id INTEGER PRIMARY KEY NOT NULL,
                totalPoints INTEGER NOT NULL DEFAULT 0,
                currentLevel INTEGER NOT NULL DEFAULT 0,
                lifetimeMinutes INTEGER NOT NULL DEFAULT 0,
                longestStreakDays INTEGER NOT NULL DEFAULT 0,
                currentStreakDays INTEGER NOT NULL DEFAULT 0,
                lastPracticeDateMs INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 4. Create tempo_logs table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tempo_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER,
                scoreId TEXT,
                pieceName TEXT NOT NULL,
                bpm INTEGER NOT NULL,
                timestampMs INTEGER NOT NULL
            )
        """)

        // 5. Create measure_practice table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS measure_practice (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER,
                scoreId TEXT NOT NULL,
                measureNumber INTEGER NOT NULL,
                durationSec INTEGER NOT NULL DEFAULT 0,
                timestampMs INTEGER NOT NULL
            )
        """)

        // 6. Create health_logs table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS health_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER,
                timestampMs INTEGER NOT NULL,
                strainLevel INTEGER NOT NULL DEFAULT 0,
                strainArea TEXT NOT NULL DEFAULT '',
                breakTakenMin INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT ''
            )
        """)

        // 7. Seed gamification profile row
        db.execSQL("INSERT OR IGNORE INTO gamification (id, totalPoints, currentLevel, lifetimeMinutes, longestStreakDays, currentStreakDays, lastPracticeDateMs) VALUES (1, 0, 0, 0, 0, 0, 0)")
    }
}

/**
 * Call this AFTER Room opens to migrate the legacy JSON practice journal.
 * Must be called on a background thread.
 */
fun migrateJsonJournal(db: SupportSQLiteDatabase, filesDir: File) {
    val journalFile = File(filesDir, "practice_journal.json")
    if (!journalFile.exists()) return

    @Serializable
    data class LegacyEntry(
        val timestampMs: Long,
        val pieceName: String,
        val notes: String,
        val durationMin: Int,
        val selfEval: Int
    )

    try {
        val json = Json { ignoreUnknownKeys = true }
        val entries: List<LegacyEntry> = json.decodeFromString(journalFile.readText())
        var totalPoints = 0
        var totalMinutes = 0

        db.beginTransaction()
        try {
            for (entry in entries) {
                val points = entry.durationMin * 10
                val cv = ContentValues().apply {
                    put("timestampMs", entry.timestampMs)
                    put("pieceName", entry.pieceName)
                    put("notes", entry.notes)
                    put("durationMin", entry.durationMin)
                    put("selfEval", entry.selfEval)
                    put("challenge", "")
                    put("nextTimeNote", "")
                    put("category", "")
                    put("totalPoints", points)
                    put("bpm", 0)
                }
                db.insert("practice_sessions", SQLiteDatabase.CONFLICT_REPLACE, cv)
                totalPoints += points
                totalMinutes += entry.durationMin
            }

            // Update gamification profile with retroactive points
            val level = totalPoints / 10_000
            db.execSQL(
                "UPDATE gamification SET totalPoints = ?, currentLevel = ?, lifetimeMinutes = ? WHERE id = 1",
                arrayOf(totalPoints, level, totalMinutes)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Rename the old file as backup
        journalFile.renameTo(File(filesDir, "practice_journal_migrated.json"))
    } catch (_: Exception) {
        // Don't crash if migration fails; user just starts fresh
    }
}
