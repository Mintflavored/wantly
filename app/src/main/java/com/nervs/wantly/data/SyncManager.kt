package com.nervs.wantly.data

import android.util.Log
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Синхронизация: сервер ↔ локальная БД.
 *
 * Архитектура: синхронизация запускается только из двух мест —
 * 1. [syncAfterAuth] — после login/register (из AuthViewModel)
 * 2. [syncIfLoggedIn] — при запуске приложения (из WantlyApp)
 *
 * Никаких LaunchedEffect(isLoggedIn), skipAutoSync, fullSyncForce —
 * единая точка входа, Mutex против гонок.
 */
class SyncManager(
    private val database: WantlyDatabase,
    private val repository: WishlistRepository,
) {
    private val mutex = Mutex()

    /**
     * Синхронизация после авторизации.
     *
     * @param isRegistration true при регистрации — мигрирует локальные данные гостя.
     * @return true при успехе, false при ошибке миграции или синхронизации.
     */
    suspend fun syncAfterAuth(isRegistration: Boolean): Boolean {
        return mutex.withLock {
            try {
                if (isRegistration) {
                    // Мигрируем локальные данные гостя на сервер.
                    // При ошибке — НЕ продолжаем, возвращаем false.
                    val migrated = runCatching { repository.migrateLocalToServer() }
                        .onFailure { Log.e(TAG, "Миграция не удалась", it) }
                        .isSuccess
                    if (!migrated) return@withLock false
                }
                fullSync()
                Log.d(TAG, "syncAfterAuth завершён успешно")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncAfterAuth не удалась", e)
                false
            }
        }
    }

    /**
     * Синхронизация при запуске — только если есть сохранённый токен.
     * Ничего не делает если пользователь — гость.
     */
    suspend fun syncIfLoggedIn(isLoggedIn: Boolean) {
        if (!isLoggedIn) return
        mutex.withLock {
            runCatching { fullSync() }
                .onFailure { Log.e(TAG, "Стартовая синхронизация не удалась", it) }
        }
    }

    /**
     * Полная синхронизация: получить все списки с сервера → заменить Room.
     *
     * Сначала загружает все данные в память, потом атомарно заменяет Room.
     * При ошибке — Room остаётся нетронутым (offline-first).
     */
    private suspend fun fullSync() {
        Log.d(TAG, "Начинаю fullSync...")
        val remoteLists = repository.api.getWishlists()

        // Сначала загружаем все детали в память
        val details = remoteLists.map { repository.api.getWishlistDetail(it.id) }

        // Атомарная замена — только после успешного pull
        database.wishlistDao().clearAll()
        database.wishDao().clearAll()

        // Сохраняем порядок сервера: первый список = наибольший timestamp
        val baseTime = System.currentTimeMillis()
        details.forEachIndexed { index, detail ->
            database.wishlistDao().insertWithId(
                WishlistEntity(
                    id = detail.wishlist.id,
                    title = detail.wishlist.title,
                    description = detail.wishlist.description,
                    coverColor = detail.wishlist.coverColor,
                    createdAt = baseTime - index,
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
                    ),
                )
            }
        }
        Log.d(TAG, "fullSync завершён: ${remoteLists.size} списков")
    }

    /**
     * Очистка локальных данных (при выходе из аккаунта).
     * Берёт mutex чтобы не конфликтовать с идущей синхронизацией.
     */
    suspend fun clearLocal() {
        mutex.withLock {
            try {
                database.wishDao().clearAll()
                database.wishlistDao().clearAll()
                Log.d(TAG, "Локальные данные очищены")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось очистить локальные данные", e)
            }
        }
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
