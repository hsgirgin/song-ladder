package com.songladder.android

import android.app.Application
import com.songladder.android.data.AppContainer

class SongLadderApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
