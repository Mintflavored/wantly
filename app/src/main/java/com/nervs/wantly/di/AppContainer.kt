package com.nervs.wantly.di

import android.content.Context
import com.nervs.wantly.data.GuestCounter
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.remote.LinkPreviewService
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.repository.WishlistRepository

/** Ручной DI-контейнер (без Hilt, чтобы держать сборку простой). */
class AppContainer(context: Context) {
    private val database = WantlyDatabase.get(context)

    val sessionManager = SessionManager(context)
    val guestCounter = GuestCounter(context)
    val linkPreviewService = LinkPreviewService()
    val api = WantlyApi(tokenProvider = { sessionManager.tokenBlocking() })
    val syncManager = SyncManager(database, api)
    val repository = WishlistRepository(
        wishlistDao = database.wishlistDao(),
        wishDao = database.wishDao(),
        linkPreviewService = linkPreviewService,
        api = api,
        sessionManager = sessionManager,
    )
}
