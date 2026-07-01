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

    @Query("UPDATE wishlists SET pendingDelete = 1, synced = 0 WHERE id = :id")
    suspend fun markDeleted(id: Long)

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
     * Привязывает serverId (всегда), но помечает synced — только если нет pendingDelete.
     * serverId сохраняется ВСЕГДА, чтобы следующий push мог DELETE, а не терять ссылку.
     */
    @Query("UPDATE wishlists SET serverId = :serverId, synced = CASE WHEN pendingDelete = 0 THEN 1 ELSE 0 END WHERE id = :localId")
    suspend fun setServerIdPreservingDirty(localId: Long, serverId: Long)

    @Query("UPDATE wishlists SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM wishlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wishlists")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wishlist: WishlistEntity)
}
