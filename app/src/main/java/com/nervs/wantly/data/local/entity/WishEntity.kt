package com.nervs.wantly.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nervs.wantly.data.model.WishStatus

@Entity(
    tableName = "wishes",
    foreignKeys = [
        ForeignKey(
            entity = WishlistEntity::class,
            parentColumns = ["id"],
            childColumns = ["wishlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("wishlistId")],
)
data class WishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wishlistId: Long,
    val title: String,
    val description: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val currency: String = "RUB",
    val storeName: String? = null,
    /** [WishStatus.name] — храним строкой, без конвертера. */
    val status: String = WishStatus.WANTED.name,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** false = локальное изменение, не отправленное на сервер. */
    val synced: Boolean = true,
    /** true = удалено локально, нужно отправить DELETE на сервер. */
    val pendingDelete: Boolean = false,
)
