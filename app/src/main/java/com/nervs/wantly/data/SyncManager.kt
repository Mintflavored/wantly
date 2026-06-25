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
            // Pull ПЕРЕД push: v1-migrated rows (serverId=null, synced=0)
            // могут уже существовать на сервере (отправлены через старый
            // migrateLocalToServer). Сначала pull дедуплицирует их по title,
            // затем push отправляет только реально новые/изменённые.
            runCatching { pullInternal() }
                .onFailure { Log.e(TAG, "Стартовый pull не удался", it) }
            runCatching { pushPendingInternal() }
                .onFailure { Log.e(TAG, "Стартовый push не удался", it) }
            runCatching { drainScheduledPush() }
                .onFailure { Log.e(TAG, "Стартовый drain не удался", it) }
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

        database.withTransaction {
            val wishlistDao = database.wishlistDao()
            val wishDao = database.wishDao()

            // === 1. UPSERT wishlists ===
            // Серверные данные обновляют существующие локальные записи по serverId,
            // сохраняя стабильный локальный PK (навигация и ViewModel держат его).
            // Tombstone (pendingDelete) и dirty (!synced) не трогаем — их
            // обработает push, перетирать нельзя.
            val remoteListIds = mutableSetOf<Long>()
            for (detail in details) {
                val remote = detail.wishlist
                remoteListIds.add(remote.id)
                val existing = wishlistDao.getByServerId(remote.id)
                if (existing != null) {
                    if (existing.pendingDelete || !existing.synced) continue
                    wishlistDao.update(existing.copy(
                        title = remote.title,
                        description = remote.description,
                        coverColor = remote.coverColor,
                        synced = true,
                    ))
                } else {
                    wishlistDao.insert(WishlistEntity(
                        title = remote.title,
                        description = remote.description,
                        coverColor = remote.coverColor,
                        serverId = remote.id,
                        synced = true,
                    ))
                }
            }

            // === 2. Удалить локальные серверные wishlists, отсутствующие на сервере.
            //     Tombstones не трогаем — push должен отправить DELETE.
            for (list in wishlistDao.getAll()) {
                val sid = list.serverId ?: continue
                if (sid !in remoteListIds && !list.pendingDelete) {
                    wishlistDao.deleteById(list.id)  // CASCADE удаляет wishes
                }
            }

            // === 3. UPSERT wishes (аналогично wishlists) ===
            val remoteWishIds = mutableSetOf<Long>()
            for (detail in details) {
                val wishlistLocalId = wishlistDao.getByServerId(detail.wishlist.id)?.id ?: continue
                for (remote in detail.wishes) {
                    remoteWishIds.add(remote.id)
                    val existing = wishDao.getByServerId(remote.id)
                    if (existing != null) {
                        if (existing.pendingDelete || !existing.synced) continue
                        wishDao.update(existing.copy(
                            wishlistId = wishlistLocalId,
                            title = remote.title,
                            description = remote.description,
                            url = remote.url,
                            imageUrl = remote.imageUrl,
                            price = remote.price,
                            currency = remote.currency,
                            storeName = remote.storeName,
                            status = remote.status,
                            synced = true,
                        ))
                    } else {
                        wishDao.insert(WishEntity(
                            wishlistId = wishlistLocalId,
                            title = remote.title,
                            description = remote.description,
                            url = remote.url,
                            imageUrl = remote.imageUrl,
                            price = remote.price,
                            currency = remote.currency,
                            storeName = remote.storeName,
                            status = remote.status,
                            serverId = remote.id,
                            synced = true,
                        ))
                    }
                }
            }

            // === 4. Удалить локальные серверные wishes, отсутствующие на сервере.
            for (wish in wishDao.getAll()) {
                val sid = wish.serverId ?: continue
                if (sid !in remoteWishIds && !wish.pendingDelete) {
                    wishDao.deleteById(wish.id)
                }
            }

            // === 5. Дедупликация v1-migrated: локальные wishlists без serverId,
            //    чьи title совпадают с серверными — уже были отправлены через
            //    старый migrateLocalToServer в v1. Удаляем (cascade), чтобы push
            //    не плодил дубли после pull.
            val serverListTitles = details
                .map { it.wishlist.title.trim().lowercase() }
                .toHashSet()
            for (list in wishlistDao.getAll()) {
                if (list.serverId == null && list.title.trim().lowercase() in serverListTitles) {
                    wishlistDao.deleteById(list.id)  // CASCADE удаляет wishes
                }
            }
        }
        Log.d(TAG, "pull завершён: ${details.size} списков")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
