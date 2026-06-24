package com.nervs.wantly.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wishlists")
data class WishlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    /** true для общих списков (Фаза 4: приглашения, совместное редактирование). */
    val isShared: Boolean = false,
    /** Индекс акцентного цвета карточки (палитра в ui.theme). */
    val coverColor: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Серверный ID. null = не отправлено на сервер. */
    @ColumnInfo(name = "serverId") val serverId: Long? = null,
    /** false = локальное изменение, не отправленное на сервер. */
    @ColumnInfo(name = "synced", defaultValue = "0") val synced: Boolean = true,
    /** true = удалено локально, нужно отправить DELETE на сервер. */
    @ColumnInfo(name = "pendingDelete", defaultValue = "0") val pendingDelete: Boolean = false,
)
