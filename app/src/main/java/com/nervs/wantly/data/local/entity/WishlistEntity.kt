package com.nervs.wantly.data.local.entity

import androidx.room.Entity
import androidx.room.Index
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
    val serverId: Long? = null,
    /** false = локальное изменение, не отправленное на сервер. */
    val synced: Boolean = true,
    /** true = удалено локально, нужно отправить DELETE на сервер. */
    val pendingDelete: Boolean = false,
)
