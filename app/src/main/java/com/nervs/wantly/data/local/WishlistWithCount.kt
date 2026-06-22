package com.nervs.wantly.data.local

import androidx.room.Embedded
import com.nervs.wantly.data.local.entity.WishlistEntity

/** Список + количество желаний в нём (один SQL-запрос для экрана «Мои списки»). */
data class WishlistWithCount(
    @Embedded val wishlist: WishlistEntity,
    val wishCount: Int,
)
