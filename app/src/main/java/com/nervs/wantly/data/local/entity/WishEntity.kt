package com.nervs.wantly.data.local.entity

import androidx.room.ColumnInfo
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
    /** Серверный ID. null = не отправлено на сервер. */
    @ColumnInfo(name = "serverId") val serverId: Long? = null,
    /** false = локальное изменение, не отправленное на сервер. */
    @ColumnInfo(name = "synced", defaultValue = "0") val synced: Boolean = true,
    /** true = удалено локально, нужно отправить DELETE на сервер. */
    @ColumnInfo(name = "pendingDelete", defaultValue = "0") val pendingDelete: Boolean = false,
)
