package com.nervs.wantly.data.repository

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
import com.nervs.wantly.data.remote.dto.CreateWishlistRequest
import com.nervs.wantly.data.remote.dto.CreateWishRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class WishlistRepository(
    private val wishlistDao: WishlistDao,
    private val wishDao: WishDao,
    private val linkPreviewService: LinkPreviewService,
    val api: WantlyApi,
) {
    fun observeWishlists(): Flow<List<WishlistWithCount>> = wishlistDao.observeAllWithCount()
    fun observeWishlist(id: Long): Flow<WishlistEntity?> = wishlistDao.observeById(id)
    fun observeWishes(wishlistId: Long): Flow<List<WishEntity>> = wishDao.observeByWishlist(wishlistId)

    /**
     * Создание списка.
     * - Залогинен: server-first (создаём на сервере → Room с server ID).
     * - Гость: только Room (локальный ID).
     */
    suspend fun createWishlist(title: String, description: String?, coverColor: Int, isLoggedIn: Boolean): Long =
        if (isLoggedIn) {
            val remote = api.createWishlist(CreateWishlistRequest(title, description, coverColor))
            wishlistDao.insertWithId(
                WishlistEntity(
                    id = remote.id,
                    title = remote.title,
                    description = remote.description,
                    coverColor = remote.coverColor,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            remote.id
        } else {
            wishlistDao.insert(WishlistEntity(title = title, description = description, coverColor = coverColor))
        }

    suspend fun deleteWishlist(wishlist: WishlistEntity, isLoggedIn: Boolean) {
        if (isLoggedIn) {
            runCatching { api.deleteWishlist(wishlist.id) }
        }
        wishlistDao.delete(wishlist)
    }

    /**
     * Добавление желания.
     * - Залогинен: server-first.
     * - Гость: только Room.
     */
    suspend fun addWish(wishlistId: Long, draft: WishDraft, isLoggedIn: Boolean): Long =
        if (isLoggedIn) {
            val remote = api.createWish(
                wishlistId,
                CreateWishRequest(
                    title = draft.title,
                    description = draft.description,
                    url = draft.url,
                    imageUrl = draft.imageUrl,
                    price = draft.price,
                    currency = draft.currency,
                    storeName = draft.storeName,
                    status = draft.status.name,
                ),
            )
            wishDao.insertWithId(
                WishEntity(
                    id = remote.id,
                    wishlistId = remote.wishlistId,
                    title = remote.title,
                    description = remote.description,
                    url = remote.url,
                    imageUrl = remote.imageUrl,
                    price = remote.price,
                    currency = remote.currency,
                    storeName = remote.storeName,
                    status = remote.status,
                ),
            )
            remote.id
        } else {
            wishDao.insert(
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
                ),
            )
        }

    suspend fun updateWishStatus(wish: WishEntity, status: WishStatus, isLoggedIn: Boolean) {
        if (isLoggedIn) {
            runCatching { api.updateWishStatus(wish.id, status.name) }
        }
        wishDao.updateStatus(wish.id, status.name)
    }

    suspend fun deleteWish(wish: WishEntity, isLoggedIn: Boolean) {
        if (isLoggedIn) {
            runCatching { api.deleteWish(wish.id) }
        }
        wishDao.delete(wish)
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

    /** Миграция локальных данных гостя на сервер после регистрации. */
    suspend fun migrateLocalToServer() {
        val lists = wishlistDao.observeAllWithCount().first()
        for (item in lists) {
            val remoteList = api.createWishlist(
                CreateWishlistRequest(
                    title = item.wishlist.title,
                    description = item.wishlist.description,
                    coverColor = item.wishlist.coverColor,
                ),
            )
            val wishes = wishDao.observeByWishlist(item.wishlist.id).first()
            for (wish in wishes) {
                api.createWish(
                    remoteList.id,
                    CreateWishRequest(
                        title = wish.title,
                        description = wish.description,
                        url = wish.url,
                        imageUrl = wish.imageUrl,
                        price = wish.price,
                        currency = wish.currency,
                        storeName = wish.storeName,
                        status = wish.status,
                    ),
                )
            }
        }
    }
}
