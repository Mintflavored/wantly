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
                // Drain: если за время sync были локальные изменения — отправляем
                drainScheduledPush()
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
                drainScheduledPush()
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
            drainScheduledPush()
        } finally {
            mutex.unlock()
        }
    }

    /** Отправляем накопленные dirty-записи если был запланирован повтор. */
    private suspend fun drainScheduledPush() {
        if (pushPendingScheduled) {
            pushPendingScheduled = false
            pushPendingInternal()
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

        // Снапшот dirty/tombstone и полный ID map — ВНУТРИ транзакции,
        // чтобы новые записи во время pull тоже сохранились (#43)
        database.withTransaction {
            val dirtyWishlists = database.wishlistDao().getUnsynced()
            val dirtyWishes = database.wishDao().getUnsynced()
            val tombstoneWishlists = database.wishlistDao().getPendingDelete()
            val tombstoneWishes = database.wishDao().getPendingDelete()

            // Полный map: локальный ID → serverId для ВСЕХ списков (#44)
            val wishlistIdMap = mutableMapOf<Long, Long>()
            for (list in database.wishlistDao().getAll()) {
                if (list.serverId != null) wishlistIdMap[list.id] = list.serverId
            }

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

            // Восстанавливаем dirty-записи и tombstones.
            for (list in dirtyWishlists) {
                database.wishlistDao().insertWithId(list.copy(
                    synced = false,
                    id = list.serverId ?: list.id,
                ))
            }
            for (wish in dirtyWishes) {
                val remappedWishlistId = wishlistIdMap[wish.wishlistId] ?: wish.wishlistId
                database.wishDao().insertWithId(wish.copy(
                    synced = false,
                    wishlistId = remappedWishlistId,
                ))
            }
            for (list in tombstoneWishlists) {
                database.wishlistDao().insertWithId(list.copy(
                    id = list.serverId ?: list.id,
                ))
            }
            for (wish in tombstoneWishes) {
                val remappedWishlistId = wishlistIdMap[wish.wishlistId] ?: wish.wishlistId
                database.wishDao().insertWithId(wish.copy(wishlistId = remappedWishlistId))
            }
        }
        Log.d(TAG, "pull завершён: ${remoteLists.size} списков")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
