package com.nervs.wantly.data

import android.util.Log
import androidx.room.withTransaction
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.dto.CreateWishRequest
import com.nervs.wantly.data.remote.dto.CreateWishlistRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local-first синхронизация.
 *
 * Локальный Room ID (autoGenerate) и серверный ID (serverId) хранятся раздельно.
 * serverId = null → локальная запись, не отправлена на сервер.
 * serverId != null → серверная запись, можно PATCH/DELETE.
 */
class SyncManager(
    private val database: WantlyDatabase,
    private val api: WantlyApi,
) {
    private val mutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var startupSyncDone = false

    /** Sync после авторизации — в application scope. */
    fun syncAfterAuthScoped(isRegistration: Boolean) {
        appScope.launch { syncAfterAuth(isRegistration) }
    }

    suspend fun syncAfterAuth(isRegistration: Boolean): Boolean {
        return mutex.withLock {
            try {
                pushPendingInternal()
                if (!isRegistration) pullInternal()
                Log.d(TAG, "syncAfterAuth завершён (registration=$isRegistration)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncAfterAuth не удалась", e)
                false
            }
        }
    }

    /** Стартовая синхронизация — один раз. */
    suspend fun syncIfLoggedIn(isLoggedIn: Boolean) {
        if (!isLoggedIn || startupSyncDone) return
        startupSyncDone = true
        mutex.withLock {
            runCatching {
                pushPendingInternal()
                pullInternal()
            }.onFailure { Log.e(TAG, "Стартовая синхронизация не удалась", it) }
        }
    }

    /**
     * Фоновая отправка dirty-записей.
     * Если mutex занят — планируем повтор после текущего sync.
     */
    @Volatile
    private var pushPendingScheduled = false

    suspend fun pushPending() {
        if (!mutex.tryLock()) {
            // Sync идёт — планируем повтор
            pushPendingScheduled = true
            return
        }
        try {
            pushPendingInternal()
            // Если за время push были новые локальные изменения — повторяем
            if (pushPendingScheduled) {
                pushPendingScheduled = false
                pushPendingInternal()
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Очистка Room при выходе. */
    suspend fun clearLocal() {
        mutex.withLock {
            database.withTransaction {
                database.wishDao().clearAll()
                database.wishlistDao().clearAll()
            }
            Log.d(TAG, "Локальные данные очищены")
        }
    }

    // ── Внутренние (под mutex) ────────────────────────────

    private suspend fun pushPendingInternal() {
        val wishDao = database.wishDao()
        val listDao = database.wishlistDao()

        // 1. DELETE: отправляем tombstones, удаляем только при успехе
        for (wish in wishDao.getPendingDelete()) {
            val sid = wish.serverId ?: run {
                wishDao.deleteById(wish.id); continue
            }
            if (runCatching { api.deleteWish(sid) }.isSuccess) wishDao.deleteById(wish.id)
        }
        for (list in listDao.getPendingDelete()) {
            val sid = list.serverId ?: run {
                listDao.deleteById(list.id); continue
            }
            if (runCatching { api.deleteWishlist(sid) }.isSuccess) listDao.deleteById(list.id)
        }

        // 2. CREATE/UPDATE wishlists
        for (list in listDao.getUnsynced()) {
            if (list.serverId == null) {
                // Новая — POST
                val remote = runCatching {
                    api.createWishlist(CreateWishlistRequest(list.title, list.description, list.coverColor))
                }.getOrNull() ?: continue
                listDao.setServerId(list.id, remote.id)
            }
            // UPDATE для существующих — в Фазе 4 (редактирование списков)
        }

        // 3. CREATE/UPDATE wishes
        for (wish in wishDao.getUnsynced()) {
            val wishlist = listDao.getById(wish.wishlistId) ?: continue
            val wishlistServerId = wishlist.serverId ?: continue // список ещё не отправлен
            if (wish.serverId == null) {
                // Новая — POST
                val remote = runCatching {
                    api.createWish(
                        wishlistServerId,
                        CreateWishRequest(
                            title = wish.title,
                            description = wish.description,
                            url = wish.url,
                            imageUrl = wish.imageUrl,
                            price = wish.price,
                            currency = wish.currency,
                            storeName = wish.storeName,
                            status = wish.status,
                        ),
                    )
                }.getOrNull() ?: continue
                wishDao.setServerId(wish.id, remote.id)
            } else {
                // Существующая — PATCH статуса
                if (runCatching { api.updateWishStatus(wish.serverId!!, wish.status) }.isSuccess) {
                    wishDao.markSynced(wish.id)
                }
            }
        }
        Log.d(TAG, "pushPending завершён")
    }

    private suspend fun pullInternal() {
        val remoteLists = api.getWishlists()
        val details = remoteLists.map { api.getWishlistDetail(it.id) }

        // Сохраняем dirty-записи которые ещё не отправлены
        val dirtyWishlists = database.wishlistDao().getUnsynced()
        val dirtyWishes = database.wishDao().getUnsynced()

        database.withTransaction {
            database.wishlistDao().clearAll()
            database.wishDao().clearAll()

            val baseTime = System.currentTimeMillis()
            details.forEachIndexed { index, detail ->
                database.wishlistDao().insertWithId(
                    WishlistEntity(
                        id = detail.wishlist.id,
                        title = detail.wishlist.title,
                        description = detail.wishlist.description,
                        coverColor = detail.wishlist.coverColor,
                        createdAt = baseTime - index,
                        serverId = detail.wishlist.id,
                        synced = true,
                    ),
                )
                detail.wishes.forEachIndexed { wishIndex, wish ->
                    database.wishDao().insertWithId(
                        WishEntity(
                            id = wish.id,
                            wishlistId = wish.wishlistId,
                            title = wish.title,
                            description = wish.description,
                            url = wish.url,
                            imageUrl = wish.imageUrl,
                            price = wish.price,
                            currency = wish.currency,
                            storeName = wish.storeName,
                            status = wish.status,
                            createdAt = baseTime - wishIndex,
                            serverId = wish.id,
                            synced = true,
                        ),
                    )
                }
            }

            // Восстанавливаем dirty-записи (они отправятся в следующий push)
            for (list in dirtyWishlists) {
                database.wishlistDao().insertWithId(list.copy(synced = false))
            }
            for (wish in dirtyWishes) {
                database.wishDao().insertWithId(wish.copy(synced = false))
            }
        }
        Log.d(TAG, "pull завершён: ${remoteLists.size} списков, сохранено ${dirtyWishlists.size}/${dirtyWishes.size} dirty")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
