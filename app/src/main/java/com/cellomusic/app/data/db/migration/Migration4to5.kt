package com.cellomusic.app.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from DB version 4 to 5.
 * Adds the achievements table used by the badge/achievement system
 * (see [com.cellomusic.app.domain.achievement.AchievementCatalog]).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS achievements (
                id TEXT PRIMARY KEY NOT NULL,
                unlockedAtMs INTEGER NOT NULL DEFAULT 0
            )
        """)
    }
}
