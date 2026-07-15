package com.nervs.wantly.data

import android.util.Log
import androidx.room.withTransaction
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.ApiException
import com.nervs.wantly.data.remote.dto.CreateWishRequest
import com.nervs.wantly.data.remote.dto.CreateWishlistRequest
import com.nervs.wantly.data.remote.dto.UpdateWishRequest
import com.nervs.wantly.data.remote.dto.UpdateWishlistRequest
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

/** Результат pushPendingVerifiedForLogout — определяет поведение logout. */
enum class LogoutSyncOutcome {
    /** Все dirty rows отправлены, можно вытирать Room. */
    SUCCESS,

    /** 401 от сервера (JWT истёк). Сессию чистить, Room НЕ трогать —
     *  данные уйдут после повторного входа. */
    AUTH_EXPIRED,

    /** Сетевая/серверная ошибка. Блокировать logout. */
    TRANSIENT_FAILURE,
}

class SyncManager(
    private val database: WantlyDatabase,
    private val api: WantlyApi,
    private val emailProvider: suspend () -> String? = { null },
) {
    private val mutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var startupSyncDone = false

    /** Test-only: состояние флага «startup sync завершён успешно». */
    internal fun isStartupSyncDoneForTest(): Boolean = startupSyncDone

    /** Sync после авторизации — в application scope. */
    fun syncAfterAuthScoped(isRegistration: Boolean) {
        appScope.launch { syncAfterAuth(isRegistration) }
    }

    suspend fun syncAfterAuth(isRegistration: Boolean): Boolean {
        val result = mutex.withLock {
            try {
                // НЕ коммитим undoProtected tombstones здесь: пользователь мог
                // удалить item и пойти логиниться, пока undo-Snackbar ещё виден.
                // commitAllUndoProtected сделал бы tombstone видимым для push →
                // row физически удалился бы → undo callback вызвал restore на
                // несуществующей записи. Snackbar onDismiss / startup / logout
                // обрабатывают commit в безопасных точках.
                pushPendingInternal()
                if (!isRegistration) pullInternal()
                // Drain cycle: ловит dirty changes сделанные во время pull
                pushAndDrain()
                Log.d(TAG, "syncAfterAuth завершён (registration=$isRegistration)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncAfterAuth не удалась", e)
                false
            }
        }
        // Lost-wakeup guard: запрос между CAS-check внутри withLock и unlock
        // видел занятый mutex, выставил флаг и ушёл. Обрабатываем.
        if (pushPendingScheduled.get()) pushPending()
        return result
    }

    /** Стартовая синхронизация — один раз, и только при успехе. */
    suspend fun syncIfLoggedIn(isLoggedIn: Boolean) {
        if (!isLoggedIn || startupSyncDone) return
        val ok = mutex.withLock {
            // Startup recovery: могли остаться undoProtected tombstones после
            // process death (Snackbar onDismiss не вызовется). Коммитим их —
            // undo-окно уже точно закрыто, tombstone должен уйти на сервер.
            database.wishlistDao().commitAllUndoProtected()
            database.wishDao().commitAllUndoProtected()
            // Owner guard ПЕРЕД pull: если в Room есть rows, привязанные к другому
            // аккаунту (не совпадает ownerEmail), вытираем их сразу, иначе pull
            // вставит свежие серверные данные, а старый guard после pull вытер
            // бы всё вместе с ними.
            val owner = emailProvider()
            val hasForeign = owner != null && (
                database.wishlistDao().getOwned().any { it.ownerEmail != owner } ||
                    database.wishDao().getOwned().any { it.ownerEmail != owner }
            )
            if (hasForeign) {
                database.withTransaction {
                    database.wishDao().clearAll()
                    database.wishlistDao().clearAll()
                }
                Log.w(TAG, "Стартовый sync: обнаружены foreign rows — Room очищена перед pull")
            }

            // Pull: UPSERT обновляет существующие локальные записи по serverId.
            val pullOk = runCatching { pullInternal() }
                .onFailure { Log.e(TAG, "Стартовый pull не удался", it) }
                .isSuccess

            val pushOk = runCatching { pushAndDrain() }
                .onFailure { Log.e(TAG, "Стартовый push не удался", it) }
                .isSuccess
            // pushAndDrain успешно завершается даже если API errors проглочены
            // и dirty rows остались. Проверяем явно, иначе startup sync будет
            // помечен выполненным при недоставленных данных.
            pullOk && pushOk && !hasUnsyncedRows()
        }
        if (ok) {
            startupSyncDone = true
        } else {
            Log.e(TAG, "Стартовый sync не удался — будет ретрай при следующем вызове")
        }
        // Lost-wakeup guard (тот же что в syncAfterAuth).
        if (pushPendingScheduled.get()) pushPending()
    }

    /**
     * Фоновая отправка dirty-записей.
     * Если mutex занят — планируем повтор после текущего sync.
     */
    private val pushPendingScheduled = AtomicBoolean(false)

    /**
     * One-time guard для recovery undo-protected tombstones. True после первого
     * pushPending — recovery нужен только один раз при startup (process death
     * мог оставить undoProtected tombstones). Последующие push'и во время
     * активного undo-окна НЕ должны коммитить tombstones (иначе undo ломается).
     */
    private val recoveryDone = AtomicBoolean(false)

    /** Push через application scope — не отменяется при popBackStack. */
    fun pushPendingScoped() {
        appScope.launch { pushPending() }
    }

    /**
     * Push + verification для logout. Различает:
     * - SUCCESS — всё отправлено, можно вытирать локальные данные
     * - AUTH_EXPIRED — 401 от сервера (JWT истёк). Сессию чистить, Room НЕ трогать
     *   — данные уйдут после повторного входа.
     * - TRANSIENT_FAILURE — network/server error. Блокировать logout,
     *   иначе данные потеряются.
     */
    suspend fun pushPendingVerifiedForLogout(): LogoutSyncOutcome {
        lastSyncSaw401 = false
        // Logout = user confirmed. Закрываем все undo-окна принудительно:
        // tombstones становятся видимыми для getPendingDelete, иначе
        // hasUnsyncedRows() вернул бы false и logout вытер Room при
        // unsynced deletes (сервер хранил бы удалённые элементы).
        database.wishlistDao().commitAllUndoProtected()
        database.wishDao().commitAllUndoProtected()
        // Ждём mutex напрямую, а не через tryLock scheduler. Если в flight
        // есть syncAfterAuth/pushPendingScoped, дождёмся завершения, потом
        // сделаем финальный push. Иначе dirty check мог бы сработать до того,
        // как in-flight sync обработает очередь.
        mutex.withLock {
            pushAndDrain()
        }
        // Lost-wakeup guard (как в syncAfterAuth).
        if (pushPendingScheduled.get()) pushPending()
        return when {
            !hasUnsyncedRows() -> LogoutSyncOutcome.SUCCESS
            lastSyncSaw401 -> LogoutSyncOutcome.AUTH_EXPIRED
            else -> LogoutSyncOutcome.TRANSIENT_FAILURE
        }
    }

    suspend fun pushPending() {
        // One-time startup recovery: если процесс был убит во время undo-окна
        // (Snackbar onDismiss не успел вызваться), undoProtected tombstones
        // остались скрытыми от getPendingDelete. Коммитим их ОДИН РАЗ при
        // первом pushPending — последующие push'и во время активных undo-окон
        // tombstones не трогают (CAS: recoveryDone true → skip).
        if (recoveryDone.compareAndSet(false, true)) {
            mutex.withLock {
                database.wishlistDao().commitAllUndoProtected()
                database.wishDao().commitAllUndoProtected()
            }
        }
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

    /**
     * Push + drain cycle ВНУТРИ уже захваченного mutex. Повторяет push пока
     * флаг scheduled не стабилизируется false — ловит dirty changes, сделанные
     * параллельно во время push. Только для вызова из mutex.withLock блока
     * (syncAfterAuth/syncIfLoggedIn), не reentrant — pushPending использует
     * tryLock и не может вызываться изнутри.
     */
    private suspend fun pushAndDrain() {
        while (true) {
            pushPendingInternal()
            if (!pushPendingScheduled.compareAndSet(true, false)) return
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

    /**
     * Запустить block и очистить локальные данные под одним mutex — атомарно.
     * Гарантирует, что queued sync, дождавшийся mutex, не успеет записать
     * данные обратно между очисткой Room и очисткой session.
     */
    suspend fun clearLocalUnder(block: suspend () -> Unit) {
        mutex.withLock {
            block()
            database.withTransaction {
                database.wishDao().clearAll()
                database.wishlistDao().clearAll()
            }
            Log.d(TAG, "Локальные данные и сессия очищены")
        }
    }

    // ── Внутренние (под mutex) ────────────────────────────

    /** Успех или 404 — сервер уже не содержит запись, желаемое состояние достигнуто. */
    private suspend fun isDeletedOrGone(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (e: ApiException) {
        if (e.code == 401) lastSyncSaw401 = true
        e.code == 404
    } catch (e: Exception) {
        false
    }

    /**
     * Флаг: последний push встретил 401. Используется logout-путём,
     * чтобы отличать «настоящая network/server ошибка» от «истёк JWT».
     * Сбрасывается в начале pushPendingVerifiedForLogout.
     */
    @Volatile
    private var lastSyncSaw401: Boolean = false

    /** Test-only: был ли 401 в последнем push. */
    internal fun lastSyncSawAuthFailureForTest(): Boolean = lastSyncSaw401

    /** API-вызов с отловом 401 → выставляет флаг, возвращает null при любой ошибке. */
    private suspend fun <T> apiCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: ApiException) {
        if (e.code == 401) lastSyncSaw401 = true
        null
    } catch (e: Exception) {
        null
    }

    /** True если в Room остались dirty rows или tombstones. */
    private suspend fun hasUnsyncedRows(): Boolean =
        database.wishlistDao().getUnsynced().isNotEmpty() ||
            database.wishDao().getUnsynced().isNotEmpty() ||
            database.wishlistDao().getPendingDelete().isNotEmpty() ||
            database.wishDao().getPendingDelete().isNotEmpty()

    /**
     * Проверка перед login/register: есть ли в Room rows, привязанные к ДРУГОМУ
     * аккаунту (ownerEmail != null && != [newEmail]), включая legacy rows
     * (ownerEmail = __legacy__) оставшиеся от предыдущего релиза — они не
     * привязаны ни к какому известному аккаунту.
     * Если есть → clearLocal, иначе данные чужого аккаунта уйдут в новый при
     * syncAfterAuth. Возвращает true если Room был вычищен.
     */
    suspend fun clearLocalIfOwnedByOther(newEmail: String): Boolean {
        val hasForeignOrLegacy = mutex.withLock {
            // getOwned возвращает rows с ownerEmail != null. Если среди них есть
            // хоть один не совпадающий с newEmail (включая __legacy__) → wipe.
            database.wishlistDao().getOwned().any { it.ownerEmail != newEmail } ||
                database.wishDao().getOwned().any { it.ownerEmail != newEmail }
        }
        if (hasForeignOrLegacy) {
            clearLocal()
        }
        return hasForeignOrLegacy
    }

    /**
     * Привязать все guest-rows (ownerEmail = NULL) к этому email. Вызывается
     * после успешного login/register перед syncAfterAuth.
     */
    suspend fun claimGuestRows(email: String) {
        mutex.withLock {
            database.wishlistDao().claimGuestRows(email)
            database.wishDao().claimGuestRows(email)
        }
    }

    /**
     * Один раз после миграции 2→3: привязать существующие NULL-owner rows
     * к текущему залогиненному аккаунту (если есть). Если юзер был гостем —
     * rows остаются NULL и нормально привяжутся при первом login.
     *
     * @return true если backfill был выполнен в этом вызове.
     */
    suspend fun backfillOwnerEmailIfFirstRun(isBackfillDone: Boolean): Boolean {
        if (isBackfillDone) return false
        val currentEmail = emailProvider() ?: return false.also {
            // Гость при апгрейде — ничего не делаем, rows останутся NULL
        }
        mutex.withLock {
            database.wishlistDao().claimGuestRows(currentEmail)
            database.wishDao().claimGuestRows(currentEmail)
        }
        return true
    }

    /**
     * Отсоединяет parent wishlist и всех его детей от серверных ID. Используется
     * в обоих conflict-путях: pull-recreate (parent удалён на сервере) и
     * push createWish 404 (обнаружили удаление parent во время push).
     *
     * - parent → serverId=null, synced=false (push пересоздаст)
     * - tombstone children → удаляются локально (сервер их уже не имеет)
     * - остальные children → serverId=null, synced=false (push пересоздаст)
     */
    private suspend fun detachParentAndChildren(
        listDao: com.nervs.wantly.data.local.WishlistDao,
        wishDao: com.nervs.wantly.data.local.WishDao,
        wishlist: WishlistEntity,
    ) {
        // Partial detach: сбрасываем только serverId/synced, не трогая pendingDelete
        // и редактируемые поля. SyncManager держит устаревший snapshot (захвачен до
        // PATCH), а full-row update через copy() затёр бы более новое состояние —
        // например pendingDelete=true от юзера, удалившего список пока PATCH летел:
        // тогда drain POSTнул бы список вместо DELETE. Аналогично для детей.
        listDao.detachServerId(wishlist.id)
        for (wish in wishDao.getAll().filter { it.wishlistId == wishlist.id }) {
            when {
                // Tombstone без serverId отправлять некуда — чистим окончательно.
                wish.pendingDelete -> wishDao.deleteById(wish.id)
                else -> wishDao.detachServerId(wish.id)
            }
        }
    }

    private suspend fun pushPendingInternal() {
        val wishDao = database.wishDao()
        val listDao = database.wishlistDao()

        // 1. DELETE: отправляем tombstones, удаляем только при успехе.
        // 404 трактуем как успех — запись уже удалена на сервере
        // (другой клиент, или прошлый DELETE упал после применения).
        for (wish in wishDao.getPendingDelete()) {
            val sid = wish.serverId ?: run {
                wishDao.deleteById(wish.id); continue
            }
            if (isDeletedOrGone { api.deleteWish(sid) }) wishDao.deleteById(wish.id)
        }
        for (list in listDao.getPendingDelete()) {
            val sid = list.serverId ?: run {
                listDao.deleteById(list.id); continue
            }
            if (isDeletedOrGone { api.deleteWishlist(sid) }) listDao.deleteById(list.id)
        }

        // 2. CREATE/UPDATE wishlists
        for (list in listDao.getUnsynced()) {
            if (list.serverId == null) {
                // Новая — POST. Явный try/catch (вместо apiCall, который глотает
                // ApiException) — иначе 400 нельзя отличить от network-ошибки и
                // пометить row как syncError.
                val remote = try {
                    api.createWishlist(CreateWishlistRequest(list.title, list.description, list.coverColor))
                } catch (e: ApiException) {
                    when (e.code) {
                        401 -> lastSyncSaw401 = true
                        // 400 = validation/bad-request на parent. Retry бесполезен —
                        // mark syncError (synced=1, выпадает из getUnsynced).
                        // Snapshot-guard: если юзер уже фиксит поле, не перезаписываем.
                        //
                        // ВАЖНО: детей НЕ трогаем. Они не виноваты в parent-only 400
                        // (сервер их не видел). Если пометить их syncError, юзер починит
                        // только parent → parent получит serverId, но дети останутся
                        // synced=1/serverId=NULL → logout вытер бы Room без их отправки.
                        // Дети остаются dirty (synced=0) → блокируют logout ровно до тех
                        // пор, пока юзер не починит parent. После починки parent POST'ится,
                        // получает serverId, и дети уходят следом в следующем push.
                        400 -> listDao.markSyncError(
                            list.id,
                            expectedTitle = list.title,
                            expectedCoverColor = list.coverColor,
                            expectedDescription = list.description,
                        )
                    }
                    continue
                } catch (e: Exception) {
                    continue
                }
                // serverId сохраняется ВСЕГДА; synced — только если PATCH-поля не
                // изменились и нет pendingDelete. Даже если список удалён или
                // отредактирован во время POST, мы знаем serverId для PATCH/DELETE.
                // Snapshot из list (захвачен до POST) — если юзер редактировал поля
                // пока POST в полёте, row останется dirty для follow-up PATCH.
                listDao.setServerIdPreservingDirty(
                    localId = list.id,
                    serverId = remote.id,
                    expectedTitle = list.title,
                    expectedCoverColor = list.coverColor,
                    expectedDescription = list.description,
                )
            } else {
                // Существующая — PATCH (PUT-стиль: все редактируемые поля).
                // Захватываем снапшот до PATCH для условного markSynced — иначе
                // второй edit в полёте потерялся бы (status-only проверки мало).
                val patchResult = runCatching {
                    api.updateWishlist(
                        list.serverId,
                        UpdateWishlistRequest(list.title, list.description, list.coverColor),
                    )
                }
                if (patchResult.isSuccess) {
                    // Условный markSynced: только если PATCH-поля не изменились и
                    // нет tombstone. Пока PATCH в полёте, пользователь мог
                    // отредактировать список или удалить его.
                    listDao.markSyncedIfUnchanged(
                        list.id,
                        expectedTitle = list.title,
                        expectedCoverColor = list.coverColor,
                        expectedDescription = list.description,
                    )
                } else {
                    val err = patchResult.exceptionOrNull()
                    if (err is ApiException) when (err.code) {
                        401 -> lastSyncSaw401 = true
                        404 -> {
                            // Список удалён на сервере (другой клиент). Без отсоединения
                            // serverId следующий push снова получит 404 и зависнет;
                            // pullInternal пропускает dirty lists → вечный ретрай, а
                            // pushPendingVerifiedForLogout заблокирует logout.
                            // Отсоединяем parent И детей: clean дети иначе сохранили бы
                            // устаревший serverId и потерялись при следующем pull (orphan).
                            // Логика идентична wish-ветке createWish 404 и pull-recreate.
                            detachParentAndChildren(listDao, wishDao, list)
                            // Планируем drain pass — иначе freshly-detached list/дети
                            // останутся unsynced и pushPendingVerifiedForLogout заблокирует
                            // logout. Outer pushPending/pushAndDrain loop POSTнет в след.витке.
                            pushPendingScheduled.set(true)
                        }
                        // 400 = validation/bad-request. Retry бесполезен.
                        // Snapshot-guard: не перезаписываем, если юзер уже фиксит.
                        400 -> listDao.markSyncError(
                            list.id,
                            expectedTitle = list.title,
                            expectedCoverColor = list.coverColor,
                            expectedDescription = list.description,
                        )
                    }
                    // Иначе (сетевая ошибка и т.п.) — оставляем dirty для retry.
                }
            }
        }

        // 3. CREATE/UPDATE wishes
        for (wish in wishDao.getUnsynced()) {
            val wishlist = listDao.getById(wish.wishlistId) ?: continue
            val wishlistServerId = wishlist.serverId ?: continue // список ещё не отправлен
            val wishServerId = wish.serverId
            if (wishServerId == null) {
                // Новая — POST. Различаем ошибки: 401 (auth), 404 (parent удалён
                // на сервере), прочее. apiCall глотает всё, поэтому 404 обрабатываем
                // явно — отсоединяем parent serverId для recreate-через-pull.
                val remote = try {
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
                } catch (e: ApiException) {
                    when (e.code) {
                        401 -> lastSyncSaw401 = true
                        404 -> {
                            // Parent list удалён на сервере. Отсоединяем serverId
                            // у parent и всех детей — иначе clean дети с устаревшим
                            // serverId выпадут из getUnsynced() и будут потеряны
                            // при следующем pull. Логика идентична pull-recreate path.
                            detachParentAndChildren(listDao, wishDao, wishlist)
                            // Планируем ещё один pass — иначе push завершится с
                            // freshly-detached dirty rows и pushPendingVerifiedForLogout
                            // заблокирует logout. Outer pushPending/pushAndDrain loop
                            // подхватит флаг и POSTнет parent + children в следующем
                            // проходе.
                            pushPendingScheduled.set(true)
                        }
                        // 400 = validation/bad-request. Retry бесполезен — mark syncError.
                        // Snapshot-guard: не перезаписываем, если юзер уже фиксит.
                        400 -> wishDao.markSyncError(
                            wish.id,
                            expectedTitle = wish.title,
                            expectedDescription = wish.description,
                            expectedUrl = wish.url,
                            expectedImageUrl = wish.imageUrl,
                            expectedPrice = wish.price,
                            expectedCurrency = wish.currency,
                            expectedStoreName = wish.storeName,
                            expectedStatus = wish.status,
                        )
                    }
                    continue
                } catch (e: Exception) {
                    continue
                }
                // serverId сохраняется ВСЕГДА; synced — только если ни одно PATCH-поле
                // не изменилось и нет pendingDelete. Snapshot из wish (захвачен до POST):
                // если юзер редактировал поля пока POST в полёте, row останется dirty
                // для follow-up PATCH. Если удалён — следующий push DELETE по serverId.
                wishDao.setServerIdPreservingDirty(
                    localId = wish.id,
                    serverId = remote.id,
                    expectedTitle = wish.title,
                    expectedDescription = wish.description,
                    expectedUrl = wish.url,
                    expectedImageUrl = wish.imageUrl,
                    expectedPrice = wish.price,
                    expectedCurrency = wish.currency,
                    expectedStoreName = wish.storeName,
                    expectedStatus = wish.status,
                )
            } else {
                // Существующая wish. Разделяем по textDirty: полевые правки требуют
                // полного PATCH, а cycle-status должен идти узким PATCH /status, чтобы
                // не перезаписывать field-правки других клиентов на устаревшем устройстве.
                val patchResult = if (wish.textDirty) {
                    runCatching {
                        api.updateWish(
                            wishServerId,
                            UpdateWishRequest(
                                title = wish.title,
                                description = wish.description,
                                url = wish.url,
                                imageUrl = wish.imageUrl,
                                price = wish.price,
                                currency = wish.currency,
                                storeName = wish.storeName,
                                // status едёт в общем PATCH — иначе cycle-status при
                                // одновременной полевой правке не синхронизировался бы.
                                status = wish.status,
                            ),
                        )
                    }
                } else {
                    // textDirty=false → только status изменился (cycle-status chip).
                    // Узкий PATCH /status не трогает чужие field-правки.
                    runCatching { api.updateWishStatus(wishServerId, wish.status) }
                }
                if (patchResult.isSuccess) {
                    if (wish.textDirty) {
                        // Сбрасываем textDirty + условный markSynced по полному snapshot.
                        wishDao.clearTextDirtyIfUnchanged(
                            id = wish.id,
                            expectedTitle = wish.title,
                            expectedDescription = wish.description,
                            expectedUrl = wish.url,
                            expectedImageUrl = wish.imageUrl,
                            expectedPrice = wish.price,
                            expectedCurrency = wish.currency,
                            expectedStoreName = wish.storeName,
                            expectedStatus = wish.status,
                        )
                    } else {
                        // status-only PATCH — обычный markSyncedIfUnchanged по snapshot.
                        wishDao.markSyncedIfUnchanged(
                            id = wish.id,
                            expectedTitle = wish.title,
                            expectedDescription = wish.description,
                            expectedUrl = wish.url,
                            expectedImageUrl = wish.imageUrl,
                            expectedPrice = wish.price,
                            expectedCurrency = wish.currency,
                            expectedStoreName = wish.storeName,
                            expectedStatus = wish.status,
                        )
                    }
                } else {
                    val err = patchResult.exceptionOrNull()
                    if (err is ApiException) when (err.code) {
                        401 -> lastSyncSaw401 = true
                        404 -> {
                            // Wish удалён на сервере (другой клиент). Без отсоединения
                            // serverId следующий push снова получит 404 и зависнет.
                            // Partial detach: сбрасываем только serverId/synced, не
                            // затирая textDirty/pendingDelete/поля (см. аналог для
                            // wishlist выше). → следующий push POSTит под текущим parent.
                            wishDao.detachServerId(wish.id)
                            // Планируем drain pass — иначе freshly-detached wish
                            // останется unsynced и pushPendingVerifiedForLogout
                            // заблокирует logout. Outer loop POSTнет в следующем витке.
                            pushPendingScheduled.set(true)
                        }
                        // 400 = validation/bad-request. Retry бесполезен.
                        // Snapshot-guard: не перезаписываем, если юзер уже фиксит.
                        400 -> wishDao.markSyncError(
                            wish.id,
                            expectedTitle = wish.title,
                            expectedDescription = wish.description,
                            expectedUrl = wish.url,
                            expectedImageUrl = wish.imageUrl,
                            expectedPrice = wish.price,
                            expectedCurrency = wish.currency,
                            expectedStoreName = wish.storeName,
                            expectedStatus = wish.status,
                        )
                    }
                    // Иначе (сетевая ошибка и т.п.) — оставляем dirty для retry.
                }
            }
        }
        Log.d(TAG, "pushPending завершён")
    }

    internal suspend fun pullInternal() {
        val remoteLists = api.getWishlists()
        val details = remoteLists.map { api.getWishlistDetail(it.id) }

        database.withTransaction {
            val wishlistDao = database.wishlistDao()
            val wishDao = database.wishDao()
            val owner = emailProvider()

            // === 1. UPSERT wishlists ===
            // Серверные данные обновляют существующие локальные записи по serverId,
            // сохраняя стабильный локальный PK (навигация и ViewModel держат его).
            // Tombstone (pendingDelete), dirty (!synced) и syncError не трогаем —
            // их обработает push, перетирать нельзя. syncError особенно важен: pull
            // не должен затирать отвергнутые сервером поля старой серверной версией,
            // иначе юзер потеряет значения, которые хотел бы исправить.
            val remoteListIds = mutableSetOf<Long>()
            for (detail in details) {
                val remote = detail.wishlist
                remoteListIds.add(remote.id)
                val existing = wishlistDao.getByServerId(remote.id)
                if (existing != null) {
                    if (existing.pendingDelete || !existing.synced || existing.syncError) continue
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
                        ownerEmail = owner,
                    ))
                }
            }

            // === 2. Удалить локальные серверные wishlists, отсутствующие на сервере.
            //     Пропускаем tombstone (push отправит DELETE), dirty (push отправит
            //     изменения) и списки с dirty/tombstoned/new children (cascade по FK
            //     вытер бы их — local-first data loss). Конфликт разрешится на
            //     следующем pull после успешного push.
            for (list in wishlistDao.getAll()) {
                val sid = list.serverId ?: continue
                if (sid in remoteListIds || list.pendingDelete || !list.synced || list.syncError) continue
                val hasDirtyChildren = wishDao.getAll().any {
                    it.wishlistId == list.id && (!it.synced || it.pendingDelete || it.syncError)
                }
                if (hasDirtyChildren) {
                    // Parent удалён на сервере, но есть локальные изменения.
                    // Отсоединяем serverId у parent и детей — push пересоздаст
                    // родительский список и воссоздаст/удалит детей под новым id,
                    // иначе PATCH/DELETE падал бы с 404 и дети зависали навсегда.
                    detachParentAndChildren(wishlistDao, wishDao, list)
                } else {
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
                        if (existing.pendingDelete || !existing.synced || existing.syncError) continue
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
                            ownerEmail = owner,
                        ))
                    }
                }
            }

            // === 4. Удалить локальные серверные wishes, отсутствующие на сервере.
            //     Пропускаем tombstone и dirty (как и для wishlists выше).
            for (wish in wishDao.getAll()) {
                val sid = wish.serverId ?: continue
                if (sid in remoteWishIds || wish.pendingDelete || !wish.synced || wish.syncError) continue
                wishDao.deleteById(wish.id)
            }

            // === 5. (no v1-migrated dedup) ===
            // Title-match небезопасен как ключ дедупликации: легальный случай
            // — пользователь создал локальный список с тем же title, что у
            // серверного, но с другими wishes. Удаление по title + cascade
            // вело к необратимой потере данных. Дубли v1-migrated rows (если
            // они были отправлены через старый migrateLocalToServer) принят как
            // known limitation — data integrity важнее отсутствия дубликатов.
            // Живых v1-юзеров нет, для свежих пользователей unreachable.
        }
        Log.d(TAG, "pull завершён: ${details.size} списков")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
