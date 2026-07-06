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
    /**
     * Email аккаунта, которому принадлежит запись. null = guest / не привязан.
     * При login/register если в Room есть rows с ownerEmail != null и != нового
     * email → Room вытирается, иначе данные чужого аккаунта уйдут под новым токеном.
     */
    @ColumnInfo(name = "ownerEmail") val ownerEmail: String? = null,
    /**
     * true = сервер reject'нул row с HTTP 400 (validation или иной bad-request).
     * SyncManager тогда ставит synced=1 + syncError=1 → row перестаёт retry'иться
     * и не блокирует logout. Пользователь видит иконку ошибки, редактирует →
     * Repository.updateEditableFields сбрасывает syncError=0 и снова synced=0.
     */
    @ColumnInfo(name = "syncError", defaultValue = "0") val syncError: Boolean = false,
)
