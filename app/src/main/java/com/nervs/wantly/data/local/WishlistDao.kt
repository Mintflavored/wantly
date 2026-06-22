package com.nervs.wantly.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nervs.wantly.data.local.entity.WishlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {
    @Query(
        """
        SELECT w.*, (SELECT COUNT(*) FROM wishes wi WHERE wi.wishlistId = w.id) AS wishCount
        FROM wishlists w
        ORDER BY w.createdAt DESC
        """,
    )
    fun observeAllWithCount(): Flow<List<WishlistWithCount>>

    @Query("SELECT * FROM wishlists WHERE id = :id")
    fun observeById(id: Long): Flow<WishlistEntity?>

    @Insert
    suspend fun insert(wishlist: WishlistEntity): Long

    @Update
    suspend fun update(wishlist: WishlistEntity)

    @Delete
    suspend fun delete(wishlist: WishlistEntity)
}
