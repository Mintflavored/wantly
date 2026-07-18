package com.nervs.wantly

import android.app.Application
import com.nervs.wantly.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WantlyApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.networkMonitor.register()

        // Стартовая синхронизация: если есть сохранённый токен — тянем данные
        appScope.launch {
            // Один раз после миграции v3: привязать существующие NULL-owner rows
            // к текущему аккаунту (если юзер был залогинен до апгрейда).
            val backfillDone = container.sessionManager.isOwnerBackfillDone()
            val didBackfill = container.syncManager.backfillOwnerEmailIfFirstRun(backfillDone)
            if (didBackfill) {
                container.sessionManager.markOwnerBackfillDone()
            }

            val loggedIn = container.sessionManager.isLoggedIn.first()
            container.syncManager.syncIfLoggedIn(loggedIn)

            // Auto-sync когда сеть восстановилась: NetworkMonitor обновляет isOnline
            // при переходе оффлайн→онлайн. drop(1) пропускает initial emission
            // StateFlow (syncIfLoggedIn выше уже отработал), filter оставляет
            // только online. syncOnReconnect делает pull+push БЕЗ startup guard —
            // срабатывает при каждом reconnect, даже после успешного startup sync.
            container.networkMonitor.isOnline
                .drop(1) // пропускаем initial emission
                .filter { it } // только переходы в онлайн
                .collect {
                    runCatching {
                        val stillLoggedIn = container.sessionManager.isLoggedIn.first()
                        container.syncManager.syncOnReconnect(stillLoggedIn)
                    }
                }
        }
    }
}
