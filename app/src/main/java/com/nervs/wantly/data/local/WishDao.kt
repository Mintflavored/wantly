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
    @Query("SELECT * FROM wishes WHERE wishlistId = :wishlistId ORDER BY sortOrder ASC, createdAt DESC")
    fun observeByWishlist(wishlistId: Long): Flow<List<WishEntity>>

    @Query("SELECT * FROM wishes WHERE id = :id")
    suspend fun getById(id: Long): WishEntity?

    @Insert
    suspend fun insert(wish: WishEntity): Long

    @Update
    suspend fun update(wish: WishEntity)

    @Delete
    suspend fun delete(wish: WishEntity)

    @Query("UPDATE wishes SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM wishes")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(wish: WishEntity)
}
