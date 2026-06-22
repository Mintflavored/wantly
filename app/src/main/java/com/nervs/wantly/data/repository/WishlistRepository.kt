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
import com.nervs.wantly.data.remote.dto.PreviewResponse
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

    suspend fun createWishlist(title: String, description: String?, coverColor: Int): Long =
        wishlistDao.insert(WishlistEntity(title = title, description = description, coverColor = coverColor))

    suspend fun deleteWishlist(wishlist: WishlistEntity) = wishlistDao.delete(wishlist)

    suspend fun addWish(wishlistId: Long, draft: WishDraft): Long =
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

    suspend fun updateWishStatus(wish: WishEntity, status: WishStatus) =
        wishDao.updateStatus(wish.id, status.name)

    suspend fun deleteWish(wish: WishEntity) = wishDao.delete(wish)

    /**
     * Парсинг ссылки.
     *
     * - Гость (нет токена): клиентский Jsoup (OpenGraph/microdata/JSON-LD) — быстро, оффлайн.
     * - Залогиненный: серверный Playwright через API — рендерит JS, надёжнее для SPA.
     */
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
                    // Сервер не справился — fallback на клиентский Jsoup
                    linkPreviewService.fetch(url)
                }
            }.getOrElse { linkPreviewService.fetch(url) }
        } else {
            linkPreviewService.fetch(url)
        }

    // ── Серверная синхронизация (Фаза 3) ──────────────────

    /** Миграция всех локальных данных гостя на сервер после регистрации. */
    suspend fun migrateLocalToServer() {
        val lists = wishlistDao.observeAllWithCount()
        // Читаем синхронно один раз — миграция идёт однократно при регистрации
        val currentLists = lists.first()
        for (item in currentLists) {
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
