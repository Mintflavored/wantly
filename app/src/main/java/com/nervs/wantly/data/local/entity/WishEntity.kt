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
    /**
     * Снимок [synced] на момент markDeleted (undo удаления). См. [WishlistEntity.preDeleteSynced].
     */
    @ColumnInfo(name = "preDeleteSynced", defaultValue = "0") val preDeleteSynced: Boolean = false,
    /** Email аккаунта-владельца (см. [WishlistEntity.ownerEmail]). */
    @ColumnInfo(name = "ownerEmail") val ownerEmail: String? = null,
    /**
     * true = локально изменены текстовые/полевые правки (title/url/price/etc.),
     * отличные от status. Push шлёт полный PATCH только когда textDirty=true;
     * иначе — узкий PATCH /status (не перезаписывает field-правки других клиентов).
     * Сбрасывается после успешного full PATCH. updateStatus НЕ трогает этот флаг.
     */
    @ColumnInfo(name = "textDirty", defaultValue = "0") val textDirty: Boolean = false,
    /**
     * true = сервер reject'нул row с HTTP 400 (validation или иной bad-request).
     * См. [WishlistEntity.syncError] — семантика и lifecycle идентичны.
     */
    @ColumnInfo(name = "syncError", defaultValue = "0") val syncError: Boolean = false,
)
