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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeWishlists(): Flow<List<WishlistWithCount>> =
        sessionManager.email.flatMapLatest { owner -> wishlistDao.observeAllWithCount(owner) }

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

    /**
     * Обновляет поля wishlist локально с synced=false → SyncManager отправит PATCH.
     * Сохраняет id/serverId/createdAt/ownerEmail/sync-флаги через copy().
     */
    suspend fun updateWishlist(
        wishlist: WishlistEntity,
        title: String,
        description: String?,
        coverColor: Int,
    ) {
        wishlistDao.update(
            wishlist.copy(
                title = title,
                description = description,
                coverColor = coverColor,
                synced = false,
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

    /** Загружает wish по local id — для prefill в edit-mode. */
    suspend fun getWish(id: Long): WishEntity? = wishDao.getById(id)

    /**
     * Обновляет редактируемые поля wish локально с synced=false → SyncManager
     * отправит PATCH. Сохраняет wishlistId (без перемещения), status, sortOrder,
     * id/serverId/createdAt/ownerEmail через copy().
     */
    suspend fun updateWish(wish: WishEntity, draft: WishDraft) {
        wishDao.update(
            wish.copy(
                title = draft.title,
                description = draft.description,
                url = draft.url,
                imageUrl = draft.imageUrl,
                price = draft.price,
                currency = draft.currency,
                storeName = draft.storeName,
                synced = false,
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
}
