package com.cellomusic.app

import android.app.Application
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.notification.PracticeReminderScheduler

class CelloMusicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Warm up the database on app start
        AppDatabase.getInstance(this)
        // Re-enqueue reminder if it was enabled before the app was killed/updated
        if (PracticeReminderScheduler.isEnabled(this)) {
            PracticeReminderScheduler.schedule(
                this,
                PracticeReminderScheduler.getHour(this),
                PracticeReminderScheduler.getMinute(this)
            )
        }
    }
}
