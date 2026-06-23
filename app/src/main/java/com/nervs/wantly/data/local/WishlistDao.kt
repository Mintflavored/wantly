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
        ORDER BY w.createdAt DESC
        """,
    )
    fun observeAllWithCount(): Flow<List<WishlistWithCount>>

    @Query("SELECT * FROM wishlists WHERE id = :id AND pendingDelete = 0")
    fun observeById(id: Long): Flow<WishlistEntity?>

    @Query("SELECT * FROM wishlists WHERE id = :id AND pendingDelete = 0")
    suspend fun getById(id: Long): WishlistEntity?

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

    @Query("UPDATE wishlists SET id = :newId, synced = 1 WHERE id = :oldId")
    suspend fun updateServerId(oldId: Long, newId: Long)

    @Query("UPDATE wishlists SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM wishlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wishlists")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wishlist: WishlistEntity)
}
