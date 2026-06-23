package com.nervs.wantly

import android.app.Application
import com.nervs.wantly.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WantlyApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Стартовая синхронизация: если есть сохранённый токен — тянем данные
        appScope.launch {
            val loggedIn = container.sessionManager.isLoggedIn.first()
            container.syncManager.syncIfLoggedIn(loggedIn)
        }
    }
}
