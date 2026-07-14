package com.nervs.wantly.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nervs.wantly.data.local.entity.WishEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WishDao {
    @Query("SELECT * FROM wishes WHERE wishlistId = :wishlistId AND pendingDelete = 0 ORDER BY sortOrder ASC, createdAt DESC")
    fun observeByWishlist(wishlistId: Long): Flow<List<WishEntity>>

    @Query("SELECT * FROM wishes WHERE id = :id AND pendingDelete = 0")
    suspend fun getById(id: Long): WishEntity?

    @Query("SELECT * FROM wishes")
    suspend fun getAll(): List<WishEntity>

    @Query("SELECT * FROM wishes WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Long): WishEntity?

    @Insert
    suspend fun insert(wish: WishEntity): Long

    @Update
    suspend fun update(wish: WishEntity)

    @Query("UPDATE wishes SET status = :status, synced = 0, syncError = 0 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * Partial update: только редактируемые поля + synced=0 + textDirty=1
     * + syncError=0 (любое редактирование сбрасывает terminal-error и снова
     * делает row dirty для повторного sync). (Маркер textDirty — что push
     * должен слать полный PATCH, а не узкий PATCH /status — иначе cycle-status
     * перезаписал бы эти правки). Не трогает serverId, createdAt, ownerEmail,
     * wishlistId, status — защита от race, когда сущность, которую видит
     * UI/Repository, устарела относительно Room.
     */
    @Query(
        """
        UPDATE wishes SET
            title = :title,
            description = :description,
            url = :url,
            imageUrl = :imageUrl,
            price = :price,
            currency = :currency,
            storeName = :storeName,
            synced = 0,
            textDirty = 1,
            syncError = 0
        WHERE id = :id
        """,
    )
    suspend fun updateEditableFields(
        id: Long,
        title: String,
        description: String?,
        url: String?,
        imageUrl: String?,
        price: Double?,
        currency: String,
        storeName: String?,
    )

    /**
     * Сбрасывает textDirty и ставит synced — оба только при snapshot-match.
     * Если пока PATCH был в полёте, юзер отредактировал поля (snapshot разошёлся),
     * textDirty остаётся 1 и synced остаётся 0 → следующий push снова шлёт полный
     * PATCH. Иначе (безусловный textDirty=0) drain счёл бы row status-only и
     * отправил узкий /status, потеряв field-правку.
     *
     * ВАЖНО: textDirty семантически означает «есть неотправленные FIELD-правки».
     * Status в этот предикат НЕ входит — иначе cycle-status в окне full PATCH
     * оставил бы textDirty=1, и drain опять шлёт full PATCH, перезаписывая чужие
     * field-правки. synced же учитывает status (race по status тоже должен держать
     * row dirty для follow-up узкого /status).
     */
    @Query(
        """
        UPDATE wishes SET
            synced = CASE
                WHEN pendingDelete = 0
                     AND title = :expectedTitle
                     AND (description IS :expectedDescription)
                     AND (url IS :expectedUrl)
                     AND (imageUrl IS :expectedImageUrl)
                     AND (price IS :expectedPrice)
                     AND currency = :expectedCurrency
                     AND (storeName IS :expectedStoreName)
                     AND status = :expectedStatus
                THEN 1
                ELSE 0
            END,
            textDirty = CASE
                WHEN pendingDelete = 0
                     AND title = :expectedTitle
                     AND (description IS :expectedDescription)
                     AND (url IS :expectedUrl)
                     AND (imageUrl IS :expectedImageUrl)
                     AND (price IS :expectedPrice)
                     AND currency = :expectedCurrency
                     AND (storeName IS :expectedStoreName)
                THEN 0
                ELSE textDirty
            END
        WHERE id = :id
        """,
    )
    suspend fun clearTextDirtyIfUnchanged(
        id: Long,
        expectedTitle: String,
        expectedDescription: String?,
        expectedUrl: String?,
        expectedImageUrl: String?,
        expectedPrice: Double?,
        expectedCurrency: String,
        expectedStoreName: String?,
        expectedStatus: String,
    )

    @Query("UPDATE wishes SET pendingDelete = 1, synced = 0, syncError = 0 WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Delete
    suspend fun delete(wish: WishEntity)

    // ── Sync helpers ──────────────────────────────────────

    @Query("SELECT * FROM wishes WHERE synced = 0 AND pendingDelete = 0")
    suspend fun getUnsynced(): List<WishEntity>

    @Query("SELECT * FROM wishes WHERE synced = 0 AND pendingDelete = 1")
    suspend fun getPendingDelete(): List<WishEntity>

    /**
     * Reactive unsynced count для sync-индикатора в UI.
     * Включает tombstones (pendingDelete=1) — если delete push ещё не дошёл,
     * сервер всё ещё не знает об удалении → это несинхронизированное состояние.
     * ownerEmail IS NOT NULL — guest-only rows не считаются (они не пойдут на сервер).
     */
    @Query(
        """
        SELECT COUNT(*) FROM wishes
        WHERE synced = 0 AND ownerEmail IS NOT NULL
        """,
    )
    fun observeUnsyncedCount(): Flow<Int>

    /** Снимает pendingDelete (undo удаления). synced=0 → row снова visible в UI. */
    @Query("UPDATE wishes SET pendingDelete = 0, synced = 0 WHERE id = :id")
    suspend fun restoreDeleted(id: Long)

    /** Все rows с ownerEmail != null (привязаны к аккаунту). */
    @Query("SELECT * FROM wishes WHERE ownerEmail IS NOT NULL")
    suspend fun getOwned(): List<WishEntity>

    /** Привязать все guest-rows (ownerEmail = NULL) к этому email. */
    @Query("UPDATE wishes SET ownerEmail = :email WHERE ownerEmail IS NULL")
    suspend fun claimGuestRows(email: String)

    @Query("UPDATE wishes SET serverId = :serverId, synced = 1 WHERE id = :localId")
    suspend fun setServerId(localId: Long, serverId: Long)

    /**
     * Привязывает serverId (всегда), но помечает synced — только если ни одно
     * PATCH-поле не изменилось и нет pendingDelete. Защита от race: пока POST
     * в полёте, пользователь мог отредактировать любые поля или удалить wish.
     * serverId сохраняется ВСЕГДА, чтобы следующий push мог PATCH/DELETE,
     * а не POSTить дубль.
     *
     * Проверяем все поля, что едут в UpdateWishRequest — status-only проверки
     * недостаточно: текст/цена/URL меняются чаще без смены status, и edit
     * молча терялся бы (synced затёрт, pull перетёр локал).
     *
     * textDirty сбрасывается почти тем же CASE, что и synced: POST уже отправил
     * field-правки на сервер, значит флаг не нужен. Но textDirty семантически —
     * «есть неотправленные FIELD-правки», поэтому status в его предикат НЕ входит
     * (cycle-status в окне POST не должен оставлять textDirty=1 — иначе drain
     * снова шлёт full PATCH и перезаписывает чужие field-правки).
     */
    @Query(
        """
        UPDATE wishes SET
            serverId = :serverId,
            synced = CASE
                WHEN pendingDelete = 0
                     AND title = :expectedTitle
                     AND (description IS :expectedDescription)
                     AND (url IS :expectedUrl)
                     AND (imageUrl IS :expectedImageUrl)
                     AND (price IS :expectedPrice)
                     AND currency = :expectedCurrency
                     AND (storeName IS :expectedStoreName)
                     AND status = :expectedStatus
                THEN 1
                ELSE 0
            END,
            textDirty = CASE
                WHEN pendingDelete = 0
                     AND title = :expectedTitle
                     AND (description IS :expectedDescription)
                     AND (url IS :expectedUrl)
                     AND (imageUrl IS :expectedImageUrl)
                     AND (price IS :expectedPrice)
                     AND currency = :expectedCurrency
                     AND (storeName IS :expectedStoreName)
                THEN 0
                ELSE textDirty
            END
        WHERE id = :localId
        """,
    )
    suspend fun setServerIdPreservingDirty(
        localId: Long,
        serverId: Long,
        expectedTitle: String,
        expectedDescription: String?,
        expectedUrl: String?,
        expectedImageUrl: String?,
        expectedPrice: Double?,
        expectedCurrency: String,
        expectedStoreName: String?,
        expectedStatus: String,
    )

    @Query("UPDATE wishes SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /**
     * Помечает wish synced только если ни одно PATCH-поле не изменилось и
     * нет pendingDelete. Защита от race: пока PATCH в полёте, пользователь мог
     * отредактировать любые поля или удалить wish — безусловный markSynced (или
     * проверка только status) затёр бы dirty flag, и второй edit потерялся бы.
     *
     * Проверяем именно те поля, что едут в UpdateWishRequest.
     */
    @Query(
        """
        UPDATE wishes SET synced = 1
        WHERE id = :id
          AND pendingDelete = 0
          AND title = :expectedTitle
          AND (description IS :expectedDescription)
          AND (url IS :expectedUrl)
          AND (imageUrl IS :expectedImageUrl)
          AND (price IS :expectedPrice)
          AND currency = :expectedCurrency
          AND (storeName IS :expectedStoreName)
          AND status = :expectedStatus
        """,
    )
    suspend fun markSyncedIfUnchanged(
        id: Long,
        expectedTitle: String,
        expectedDescription: String?,
        expectedUrl: String?,
        expectedImageUrl: String?,
        expectedPrice: Double?,
        expectedCurrency: String,
        expectedStoreName: String?,
        expectedStatus: String,
    )

    @Query("DELETE FROM wishes WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Partial detach: сбрасывает только serverId и помечает dirty. НЕ трогает
     * pendingDelete и редактируемые поля — см. WishlistDao.detachServerId.
     */
    @Query("UPDATE wishes SET serverId = NULL, synced = 0 WHERE id = :id")
    suspend fun detachServerId(id: Long)

    /**
     * Помечает row как synced + syncError — HTTP 400 от сервера (validation или
     * иной bad-request). См. [WishlistDao.markSyncError] — семантика идентична.
     *
     * Snapshot-guard: пока PATCH/POST в полёте, юзер мог исправить поле →
     * updateEditableFields уже выставил synced=0, syncError=0. Безусловный
     * markSyncError затёр бы этот фикс. Применяем только если поля совпадают.
     */
    @Query(
        """
        UPDATE wishes SET synced = 1, syncError = 1
        WHERE id = :id
          AND pendingDelete = 0
          AND title = :expectedTitle
          AND (description IS :expectedDescription)
          AND (url IS :expectedUrl)
          AND (imageUrl IS :expectedImageUrl)
          AND (price IS :expectedPrice)
          AND currency = :expectedCurrency
          AND (storeName IS :expectedStoreName)
          AND status = :expectedStatus
        """,
    )
    suspend fun markSyncError(
        id: Long,
        expectedTitle: String,
        expectedDescription: String?,
        expectedUrl: String?,
        expectedImageUrl: String?,
        expectedPrice: Double?,
        expectedCurrency: String,
        expectedStoreName: String?,
        expectedStatus: String,
    )

    @Query("DELETE FROM wishes")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wish: WishEntity)
}
