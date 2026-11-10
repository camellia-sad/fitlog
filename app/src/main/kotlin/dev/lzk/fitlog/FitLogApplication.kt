package dev.lzk.fitlog

import android.app.Application

class FitLogApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.initialize()
        container.startAlerts()
    }
}
