package com.cellomusic.app

import android.app.Application
import com.cellomusic.app.data.db.AppDatabase

class CelloMusicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Warm up the database on app start
        AppDatabase.getInstance(this)
    }
}
