package com.nervs.wantly.data.model

/** Черновик желания для сохранения (заполняется вручную или из [LinkPreview]). */
data class WishDraft(
    val title: String,
    val description: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val currency: String = "RUB",
    val storeName: String? = null,
    val status: WishStatus = WishStatus.WANTED,
)
