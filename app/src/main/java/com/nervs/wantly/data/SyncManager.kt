package com.nervs.wantly.data

import android.util.Log
import androidx.room.withTransaction
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.dto.CreateWishRequest
import com.nervs.wantly.data.remote.dto.CreateWishlistRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local-first синхронизация.
 *
 * Принципы:
 * 1. Room — единственный источник правды для UI. UI всегда читает из Room.
 * 2. Все записи идут в Room мгновенно с synced=false (dirty).
 * 3. [pushPending] — отправляет dirty-записи на сервер в фоне.
 * 4. [pullFromServer] — заменяет Room данными сервера (при запуске/login).
 *
 * Sync запускается из двух мест:
 * - [syncAfterAuth] — после login/register (из AuthViewModel)
 * - [syncIfLoggedIn] — при запуске приложения (из WantlyApp)
 * - [pushPending] — после каждой локальной операции (из Repository)
 *
 * Нет гонок: Mutex сериализует все sync-операции.
 * Нет блокировки UI: pushPending работает в фоне, UI не ждёт.
 */
class SyncManager(
    private val database: WantlyDatabase,
    private val api: WantlyApi,
) {
    private val mutex = Mutex()

    /**
     * После авторизации: миграция (при регистрации) → pull.
     * Гостевые данные уже в Room с synced=false → pushPending их отправит.
     */
    suspend fun syncAfterAuth(isRegistration: Boolean): Boolean {
        return mutex.withLock {
            try {
                if (isRegistration) {
                    // Гостевые данные в Room уже помечены synced=false.
                    // Отправляем на сервер, получаем серверные ID.
                    pushPendingInternal()
                }
                pullInternal()
                Log.d(TAG, "syncAfterAuth завершён (registration=$isRegistration)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncAfterAuth не удалась", e)
                false
            }
        }
    }

    /**
     * Стартовая синхронизация — один раз за сессию.
     */
    @Volatile
    private var startupSyncDone = false

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
     * Вызывается после каждой локальной операции если залогинен.
     */
    suspend fun pushPending() {
        if (!mutex.tryLock()) return // уже идёт sync — не блокируем
        try {
            pushPendingInternal()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Очистка локальных данных (при выходе).
     */
    suspend fun clearLocal() {
        mutex.withLock {
            database.withTransaction {
                database.wishDao().clearAll()
                database.wishlistDao().clearAll()
            }
            Log.d(TAG, "Локальные данные очищены")
        }
    }

    // ── Внутренние методы (вызываются под mutex) ──────────

    private suspend fun pushPendingInternal() {
        // 1. Отправляем удаления
        for (wish in database.wishDao().getPendingDelete()) {
            runCatching { api.deleteWish(wish.id) }
            database.wishDao().deleteById(wish.id)
        }
        for (list in database.wishlistDao().getPendingDelete()) {
            runCatching { api.deleteWishlist(list.id) }
            database.wishlistDao().deleteById(list.id)
        }

        // 2. Отправляем новые/изменённые списки
        for (list in database.wishlistDao().getUnsynced()) {
            val remote = runCatching {
                api.createWishlist(CreateWishlistRequest(list.title, list.description, list.coverColor))
            }.getOrNull() ?: continue
            // Обновляем локальный ID на серверный
            database.wishlistDao().updateServerId(list.id, remote.id)
        }

        // 3. Отправляем новые/изменённые желания
        for (wish in database.wishDao().getUnsynced()) {
            val wishlist = database.wishlistDao().getById(wish.wishlistId) ?: continue
            val remote = runCatching {
                api.createWish(
                    wishlist.id,
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
            database.wishDao().updateServerId(wish.id, remote.id)
        }
        Log.d(TAG, "pushPending завершён")
    }

    private suspend fun pullInternal() {
        val remoteLists = api.getWishlists()
        val details = remoteLists.map { api.getWishlistDetail(it.id) }

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
                            synced = true,
                        ),
                    )
                }
            }
        }
        Log.d(TAG, "pull завершён: ${remoteLists.size} списков")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
