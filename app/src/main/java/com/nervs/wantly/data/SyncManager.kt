package com.nervs.wantly.data

import android.util.Log
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi

/**
 * Синхронизация: сервер → локальная БД.
 *
 * Стратегия: server-as-truth. После логина сервер — источник правды.
 * Полная замена локальных данных (wipe + re-insert) при каждом pull.
 *
 * Это просто и надёжно; conflict resolution не нужен т.к. dual-write
 * в Repository гарантирует что сервер всегда актуален.
 */
class SyncManager(
    private val database: WantlyDatabase,
    private val api: WantlyApi,
) {
    /**
     * Полная синхронизация: получить все списки с сервера → заменить Room.
     * Вызывается при запуске (если залогинен) и после login/register.
     */
    suspend fun fullSync() {
        try {
            Log.d(TAG, "Начинаю fullSync...")
            val remoteLists = api.getWishlists()

            // Wipe + re-insert — сервер = source of truth
            database.wishlistDao().clearAll()
            database.wishDao().clearAll()

            for (list in remoteLists) {
                val detail = api.getWishlistDetail(list.id)

                // Вставляем список с серверным ID
                database.wishlistDao().insertWithId(
                    WishlistEntity(
                        id = detail.wishlist.id,
                        title = detail.wishlist.title,
                        description = detail.wishlist.description,
                        coverColor = detail.wishlist.coverColor,
                        createdAt = System.currentTimeMillis(),
                    ),
                )

                // Вставляем желания с серверными ID
                for (wish in detail.wishes) {
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
                        ),
                    )
                }
            }
            Log.d(TAG, "fullSync завершён: ${remoteLists.size} списков")
        } catch (e: Exception) {
            Log.e(TAG, "fullSync не удался", e)
            // Не падаем — работаем offline с последними данными из Room
        }
    }

    /**
     * Очистка локальных данных (при выходе из аккаунта).
     * Гость не должен видеть данные предыдущего залогиненного пользователя.
     */
    suspend fun clearLocal() {
        try {
            database.wishDao().clearAll()
            database.wishlistDao().clearAll()
            Log.d(TAG, "Локальные данные очищены")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось очистить локальные данные", e)
        }
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
