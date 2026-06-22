package com.nervs.wantly

import android.app.Application
import com.nervs.wantly.di.AppContainer

class WantlyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
