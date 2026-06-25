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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val pushPendingScheduled = AtomicBoolean(false)

    /** Push через application scope — не отменяется при popBackStack. */
    fun pushPendingScoped() {
        appScope.launch { pushPending() }
    }

    suspend fun pushPending() {
        // Outer loop ловит lost-wakeup: запрос, пришедший во время нашего
        // push, видел занятый mutex, выставил флаг и ушёл. После unlock —
        // CAS: если флаг=true, атомарно сбрасываем и делаем ещё один виток
        // со свежим DAO snapshot (параллельные dirty изменения).
        while (true) {
            if (!mutex.tryLock()) {
                // Sync идёт — планируем повтор
                pushPendingScheduled.set(true)
                return
            }
            try {
                pushPendingInternal()
            } finally {
                mutex.unlock()
            }
            // CAS: выходим только если флаг остался false. Иначе кто-то
            // звал во время push — сбрасываем и прогоняем ещё раз.
            if (!pushPendingScheduled.compareAndSet(true, false)) return
        }
    }

    /** Отправляем накопленные dirty-записи если был запланирован повтор. */
    private suspend fun drainScheduledPush() {
        if (pushPendingScheduled.compareAndSet(true, false)) {
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
                // serverId сохраняется ВСЕГДА; synced — только если нет pendingDelete.
                // Даже если список удалён во время POST, мы знаем serverId для DELETE.
                listDao.setServerIdPreservingDirty(list.id, remote.id)
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
                // serverId сохраняется ВСЕГДА; synced — только если статус не изменился
                // и нет pendingDelete. Если статус изменился — следующий push PATCHит.
                // Если удалён — следующий push DELETE по serverId.
                wishDao.setServerIdPreservingDirty(wish.id, remote.id, wish.status)
            } else {
                // Существующая — PATCH статуса
                if (runCatching { api.updateWishStatus(wish.serverId!!, wish.status) }.isSuccess) {
                    // Условный markSynced: только если статус не изменился и нет tombstone.
                    // Пока PATCH в полёте, пользователь мог изменить статус или удалить wish.
                    wishDao.markSyncedIfUnchanged(wish.id, wish.status)
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

            // v1-migrated detection: серверные titles (lowercase, trimmed).
            // Локальные списки без serverId с совпадающим title — уже были
            // отправлены через старый migrateLocalToServer в v1. При
            // восстановлении пропускаются (как и их wishes), чтобы push
            // не плодил дубли после pull.
            val serverListTitles = remoteLists
                .map { it.title.trim().lowercase() }
                .toHashSet()

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
            // Для записей БЕЗ serverId выделяем свежий локальный ID (autoGenerate),
            // чтобы временный локальный ID не коллизировал с серверным (#50).
            // Отдельные map для wishlist и wish: ID из разных таблиц могут
            // совпадать (обе начинаются с 1) — общий map перезапишет remap (#53).
            val wishlistIdRemap = mutableMapOf<Long, Long>() // старый wishlist ID → новый
            val wishIdRemap = mutableMapOf<Long, Long>() // старый wish ID → новый

            for (list in dirtyWishlists) {
                if (list.serverId != null) {
                    // Серверная запись стала dirty — REPLACE по serverId
                    database.wishlistDao().insertWithId(list.copy(
                        synced = false,
                        id = list.serverId,
                    ))
                } else {
                    // v1-migrated дубль (title уже есть на сервере) — пропускаем,
                    // серверная копия вставлена выше, push не будет POSTить.
                    if (list.title.trim().lowercase() in serverListTitles) continue
                    // Локальная запись (POST не удался) — свежий ID
                    val newId = database.wishlistDao().insert(list.copy(id = 0, synced = false))
                    wishlistIdRemap[list.id] = newId
                }
            }
            for (wish in dirtyWishes) {
                val remappedWishlistId = wishlistIdMap[wish.wishlistId]
                    ?: wishlistIdRemap[wish.wishlistId]
                // Родительский wishlist не восстановлен (v1-migrated дубль
                // или удалён) — wish тоже дубликат серверного, пропускаем.
                if (remappedWishlistId == null) continue
                if (wish.serverId != null) {
                    database.wishDao().insertWithId(wish.copy(
                        synced = false,
                        wishlistId = remappedWishlistId,
                        id = wish.serverId,
                    ))
                } else {
                    val newId = database.wishDao().insert(wish.copy(
                        id = 0,
                        synced = false,
                        wishlistId = remappedWishlistId,
                    ))
                    wishIdRemap[wish.id] = newId
                }
            }
            // Tombstones без serverId не восстанавливаем — сервер о них не знает.
            for (list in tombstoneWishlists) {
                if (list.serverId != null) {
                    database.wishlistDao().insertWithId(list.copy(id = list.serverId))
                }
            }
            for (wish in tombstoneWishes) {
                val remappedWishlistId = wishlistIdMap[wish.wishlistId]
                    ?: wishlistIdRemap[wish.wishlistId]
                    ?: wish.wishlistId
                if (wish.serverId != null) {
                    database.wishDao().insertWithId(wish.copy(
                        wishlistId = remappedWishlistId,
                        id = wish.serverId,
                    ))
                }
            }
        }
        Log.d(TAG, "pull завершён: ${remoteLists.size} списков")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
