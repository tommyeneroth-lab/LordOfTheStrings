package com.cellomusic.app.data.db

import android.content.Context
import androidx.room.*
import com.cellomusic.app.data.db.dao.*
import com.cellomusic.app.data.db.entity.*
import com.cellomusic.app.data.db.migration.MIGRATION_3_4
import com.cellomusic.app.data.db.migration.MIGRATION_4_5
import com.cellomusic.app.data.db.migration.migrateJsonJournal

@Database(
    entities = [
        ScoreEntity::class,
        PracticeSessionEntity::class,
        PracticeGoalEntity::class,
        GamificationEntity::class,
        TempoLogEntity::class,
        MeasurePracticeEntity::class,
        HealthLogEntity::class,
        AchievementEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scoreDao(): ScoreDao
    abstract fun practiceSessionDao(): PracticeSessionDao
    abstract fun practiceGoalDao(): PracticeGoalDao
    abstract fun gamificationDao(): GamificationDao
    abstract fun tempoLogDao(): TempoLogDao
    abstract fun measurePracticeDao(): MeasurePracticeDao
    abstract fun healthLogDao(): HealthLogDao
    abstract fun achievementDao(): AchievementDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "cello_music_db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Migrate legacy JSON journal entries on first open after upgrade
                        migrateJsonJournal(db, appContext.filesDir)
                    }
                })
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
