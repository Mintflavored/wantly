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

    @Query("UPDATE wishes SET status = :status, synced = 0 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE wishes SET pendingDelete = 1, synced = 0 WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Delete
    suspend fun delete(wish: WishEntity)

    // ── Sync helpers ──────────────────────────────────────

    @Query("SELECT * FROM wishes WHERE synced = 0 AND pendingDelete = 0")
    suspend fun getUnsynced(): List<WishEntity>

    @Query("SELECT * FROM wishes WHERE synced = 0 AND pendingDelete = 1")
    suspend fun getPendingDelete(): List<WishEntity>

    @Query("UPDATE wishes SET serverId = :serverId, synced = 1 WHERE id = :localId")
    suspend fun setServerId(localId: Long, serverId: Long)

    /**
     * Привязывает serverId (всегда), но помечает synced — только если статус
     * не изменился и нет pendingDelete. Защита от race: пока POST в полёте,
     * пользователь мог изменить статус или удалить wish.
     * В отличие от setServerIdIfUnchanged — serverId сохраняется ВСЕГДА,
     * чтобы следующий push мог PATCH/DELETE, а не POSTить дубль.
     */
    @Query("UPDATE wishes SET serverId = :serverId, synced = CASE WHEN status = :expectedStatus AND pendingDelete = 0 THEN 1 ELSE 0 END WHERE id = :localId")
    suspend fun setServerIdPreservingDirty(localId: Long, serverId: Long, expectedStatus: String)

    @Query("UPDATE wishes SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /**
     * Помечает wish synced только если статус не изменился и нет pendingDelete.
     * Защита от race: пока PATCH в полёте, пользователь мог изменить
     * статус или удалить wish — безусловный markSynced затёр бы dirty flag.
     */
    @Query("UPDATE wishes SET synced = 1 WHERE id = :id AND status = :expectedStatus AND pendingDelete = 0")
    suspend fun markSyncedIfUnchanged(id: Long, expectedStatus: String)

    @Query("DELETE FROM wishes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wishes")
    suspend fun clearAll()

    @Query("UPDATE wishes SET wishlistId = :newId WHERE wishlistId = :oldId")
    suspend fun updateWishlistId(oldId: Long, newId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wish: WishEntity)
}
