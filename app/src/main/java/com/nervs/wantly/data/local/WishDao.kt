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

    @Query("UPDATE wishes SET id = :newId, synced = 1 WHERE id = :oldId")
    suspend fun updateServerId(oldId: Long, newId: Long)

    @Query("UPDATE wishes SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM wishes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wishes")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wish: WishEntity)
}
