package com.nervs.wantly.data.repository

import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.local.WishlistDao
import com.nervs.wantly.data.local.WishlistWithCount
import com.nervs.wantly.data.local.WishDao
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.model.WishDraft
import com.nervs.wantly.data.model.WishStatus
import com.nervs.wantly.data.remote.LinkPreview
import com.nervs.wantly.data.remote.LinkPreviewService
import com.nervs.wantly.data.remote.WantlyApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Local-first репозиторий.
 *
 * Все операции пишут в Room мгновенно с synced=false.
 * SyncManager.pushPending() отправляет dirty-записи в фоне.
 * Нет блокировки UI на сеть.
 */
class WishlistRepository(
    private val wishlistDao: WishlistDao,
    private val wishDao: WishDao,
    private val linkPreviewService: LinkPreviewService,
    val api: WantlyApi,
    private val sessionManager: SessionManager,
) {
    fun observeWishlists(): Flow<List<WishlistWithCount>> = wishlistDao.observeAllWithCount()
    fun observeWishlist(id: Long): Flow<WishlistEntity?> = wishlistDao.observeById(id)
    fun observeWishes(wishlistId: Long): Flow<List<WishEntity>> = wishDao.observeByWishlist(wishlistId)

    suspend fun createWishlist(title: String, description: String?, coverColor: Int): Long {
        val owner = sessionManager.email.first()
        return wishlistDao.insert(
            WishlistEntity(
                title = title,
                description = description,
                coverColor = coverColor,
                synced = false,
                ownerEmail = owner,
            ),
        )
    }

    suspend fun deleteWishlist(wishlist: WishlistEntity) {
        wishlistDao.markDeleted(wishlist.id)
    }

    suspend fun addWish(wishlistId: Long, draft: WishDraft): Long {
        val owner = sessionManager.email.first()
        return wishDao.insert(
            WishEntity(
                wishlistId = wishlistId,
                title = draft.title,
                description = draft.description,
                url = draft.url,
                imageUrl = draft.imageUrl,
                price = draft.price,
                currency = draft.currency,
                storeName = draft.storeName,
                status = draft.status.name,
                synced = false,
                ownerEmail = owner,
            ),
        )
    }

    suspend fun updateWishStatus(wish: WishEntity, status: WishStatus) {
        wishDao.updateStatus(wish.id, status.name)
    }

    suspend fun deleteWish(wish: WishEntity) {
        wishDao.markDeleted(wish.id)
    }

    suspend fun previewLink(url: String, isLoggedIn: Boolean): LinkPreview =
        if (isLoggedIn) {
            runCatching {
                val resp = api.preview(url)
                if (resp.success) {
                    LinkPreview(
                        url = resp.url,
                        title = resp.title,
                        description = resp.description,
                        imageUrl = resp.imageUrl,
                        price = resp.price,
                        currency = resp.currency,
                        storeName = resp.storeName,
                        success = true,
                    )
                } else {
                    linkPreviewService.fetch(url)
                }
            }.getOrElse { linkPreviewService.fetch(url) }
        } else {
            linkPreviewService.fetch(url)
        }

    /** Больше не нужна — миграция через pushPending при syncAfterAuth. */
    suspend fun migrateLocalToServer() {
        // Серверные данные придут через pull после pushPending.
    }
}
