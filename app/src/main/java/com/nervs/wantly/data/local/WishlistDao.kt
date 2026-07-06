package com.nervs.wantly.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nervs.wantly.data.local.entity.WishlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {
    @Query(
        """
        SELECT w.*, (SELECT COUNT(*) FROM wishes wi WHERE wi.wishlistId = w.id AND wi.pendingDelete = 0) AS wishCount
        FROM wishlists w
        WHERE w.pendingDelete = 0
          AND (w.ownerEmail IS NULL OR w.ownerEmail = :ownerEmail)
        ORDER BY w.createdAt DESC
        """,
    )
    fun observeAllWithCount(ownerEmail: String?): Flow<List<WishlistWithCount>>

    @Query("SELECT * FROM wishlists WHERE id = :id AND pendingDelete = 0")
    fun observeById(id: Long): Flow<WishlistEntity?>

    @Query("SELECT * FROM wishlists")
    suspend fun getAll(): List<WishlistEntity>

    @Query("SELECT * FROM wishlists WHERE id = :id AND pendingDelete = 0")
    suspend fun getById(id: Long): WishlistEntity?

    @Query("SELECT * FROM wishlists WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Long): WishlistEntity?

    @Insert
    suspend fun insert(wishlist: WishlistEntity): Long

    @Update
    suspend fun update(wishlist: WishlistEntity)

    @Query("UPDATE wishlists SET pendingDelete = 1, synced = 0, syncError = 0 WHERE id = :id")
    suspend fun markDeleted(id: Long)

    /**
     * Partial update: только редактируемые поля + synced=0 + syncError=0
     * (любое редактирование сбрасывает terminal-error и снова делает row dirty
     * для повторного sync). Не трогает serverId, createdAt, ownerEmail, isShared —
     * защита от race, когда сущность устарела относительно Room.
     */
    @Query(
        """
        UPDATE wishlists SET
            title = :title,
            description = :description,
            coverColor = :coverColor,
            synced = 0,
            syncError = 0
        WHERE id = :id
        """,
    )
    suspend fun updateEditableFields(
        id: Long,
        title: String,
        description: String?,
        coverColor: Int,
    )

    // ── Sync helpers ──────────────────────────────────────

    @Query("SELECT * FROM wishlists WHERE synced = 0 AND pendingDelete = 0")
    suspend fun getUnsynced(): List<WishlistEntity>

    @Query("SELECT * FROM wishlists WHERE synced = 0 AND pendingDelete = 1")
    suspend fun getPendingDelete(): List<WishlistEntity>

    /** Все rows с ownerEmail != null (привязаны к аккаунту). */
    @Query("SELECT * FROM wishlists WHERE ownerEmail IS NOT NULL")
    suspend fun getOwned(): List<WishlistEntity>

    /** Привязать все guest-rows (ownerEmail = NULL) к этому email. */
    @Query("UPDATE wishlists SET ownerEmail = :email WHERE ownerEmail IS NULL")
    suspend fun claimGuestRows(email: String)

    @Query("UPDATE wishlists SET serverId = :serverId, synced = 1 WHERE id = :localId")
    suspend fun setServerId(localId: Long, serverId: Long)

    /**
     * Привязывает serverId (всегда), но помечает synced — только если нет pendingDelete
     * И ни одно PATCH-поле не изменилось с момента POST. serverId сохраняется ВСЕГДА,
     * чтобы следующий push мог PATCH/DELETE, а не терять ссылку.
     *
     * Snapshot-проверка защищает от race: пока POST в полёте, пользователь мог
     * отредактировать title/description/color — безусловный synced=1 затёр бы
     * dirty flag, и edit потерялся бы (pull перетёр бы локал серверным POST-состоянием).
     */
    @Query(
        """
        UPDATE wishlists SET
            serverId = :serverId,
            synced = CASE
                WHEN pendingDelete = 0
                     AND title = :expectedTitle
                     AND (description IS :expectedDescription)
                     AND coverColor = :expectedCoverColor
                THEN 1
                ELSE 0
            END
        WHERE id = :localId
        """,
    )
    suspend fun setServerIdPreservingDirty(
        localId: Long,
        serverId: Long,
        expectedTitle: String,
        expectedCoverColor: Int,
        expectedDescription: String?,
    )

    @Query("UPDATE wishlists SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /**
     * Помечает wishlist synced только если ни одно PATCH-поле не изменилось и
     * нет pendingDelete. Защита от race: пока PATCH в полёте, пользователь мог
     * отредактировать поля или удалить список — безусловный markSynced затёр бы
     * dirty flag, и второй edit потерялся бы навсегда.
     *
     * Проверяем именно те поля, что едут в UpdateWishlistRequest.
     */
    @Query(
        """
        UPDATE wishlists SET synced = 1
        WHERE id = :id
          AND pendingDelete = 0
          AND title = :expectedTitle
          AND (description IS :expectedDescription)
          AND coverColor = :expectedCoverColor
        """,
    )
    suspend fun markSyncedIfUnchanged(
        id: Long,
        expectedTitle: String,
        expectedCoverColor: Int,
        expectedDescription: String?,
    )

    @Query("DELETE FROM wishlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Partial detach: сбрасывает только serverId и помечает dirty. НЕ трогает
     * pendingDelete и редактируемые поля. Защита от race: SyncManager вызовет
     * это с устаревшим snapshot (захваченным до PATCH), а full-row update через
     * copy() затёр бы более новое состояние — например pendingDelete=true от
     * юзера, удалившего список пока PATCH был в полёте (тогда drain POSTнул бы
     * список вместо DELETE). Также не трогает clean-поля, чтобы concurrent edit
     * не откатывался.
     */
    @Query("UPDATE wishlists SET serverId = NULL, synced = 0 WHERE id = :id")
    suspend fun detachServerId(id: Long)

    /**
     * Помечает row как synced + syncError — HTTP 400 от сервера (validation или
     * иной bad-request). Row выпадает из getUnsynced() → перестаёт retry'иться
     * и не блокирует pushPendingVerifiedForLogout. Пользователь редактирует →
     * updateEditableFields/markDeleted сбрасывают syncError.
     */
    @Query("UPDATE wishlists SET synced = 1, syncError = 1 WHERE id = :id")
    suspend fun markSyncError(id: Long)

    @Query("DELETE FROM wishlists")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wishlist: WishlistEntity)
}
