package com.nervs.wantly.test

import com.nervs.wantly.data.remote.ApiException
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.dto.CreateWishRequest
import com.nervs.wantly.data.remote.dto.CreateWishlistRequest
import com.nervs.wantly.data.remote.dto.UpdateWishRequest
import com.nervs.wantly.data.remote.dto.UpdateWishlistRequest
import com.nervs.wantly.data.remote.dto.WishDto
import com.nervs.wantly.data.remote.dto.WishlistDetailResponse
import com.nervs.wantly.data.remote.dto.WishlistDto
import io.mockk.coEvery
import io.mockk.mockk

/**
 * Stateful fake сервера. Хранит wishlists и wishes в памяти, ведёт себя
 * как реальный backend: POST создаёт с новым id, DELETE несуществующего → 404.
 *
 * Используется в SyncManagerTest вместо моков отдельных вызовов —
 * так тесты проверяют полный сценарий «клиент ↔ сервер».
 */
class FakeApi {

    private val wishlists = mutableMapOf<Long, WishlistDto>()
    private val wishes = mutableMapOf<Long, WishDto>()
    private var nextId = 1L

    /** Возвращает все серверные wishlist IDs. */
    fun wishlistIds(): Set<Long> = wishlists.keys.toSet()

    /** Возвращает все серверные wish IDs. */
    fun wishIds(): Set<Long> = wishes.keys.toSet()

    /** Серверный wishlist по id (для проверок в тестах). */
    fun wishlist(id: Long): WishlistDto? = wishlists[id]

    /** Серверный wish по id. */
    fun wish(id: Long): WishDto? = wishes[id]

    /** 直接но запихнуть предзаполнённое состояние сервера (для setup тестов). */
    fun seed(
        wishlist: WishlistDto,
        wishes: List<WishDto> = emptyList(),
    ) {
        this.wishlists[wishlist.id] = wishlist
        wishes.forEach { this.wishes[it.id] = it }
        nextId = maxOf(nextId, wishlist.id + 1, *wishes.map { it.id + 1 }.toLongArray())
    }

    /** Создаёт mockk<WantlyApi> с поведением, описанным выше. */
    fun mock(): WantlyApi = mockk<WantlyApi>(relaxed = true) {
        coEvery { getWishlists() } answers { wishlists.values.toList() }

        coEvery { getWishlistDetail(any()) } answers {
            val id = firstArg<Long>()
            val list = wishlists[id] ?: throw ApiException(404, "not found")
            WishlistDetailResponse(list, wishes.values.filter { it.wishlistId == id }.toList())
        }

        coEvery { createWishlist(any()) } answers {
            val req = firstArg<CreateWishlistRequest>()
            val id = nextId++
            val dto = WishlistDto(
                id = id,
                title = req.title,
                description = req.description,
                isShared = false,
                coverColor = req.coverColor,
            )
            wishlists[id] = dto
            dto
        }

        coEvery { updateWishlist(any(), any()) } answers {
            val id = firstArg<Long>()
            val req = secondArg<UpdateWishlistRequest>()
            val existing = wishlists[id] ?: throw ApiException(404, "not found")
            val dto = existing.copy(title = req.title, description = req.description, coverColor = req.coverColor)
            wishlists[id] = dto
            dto
        }

        coEvery { deleteWishlist(any()) } answers {
            val id = firstArg<Long>()
            if (wishlists.remove(id) == null) throw ApiException(404, "not found")
            // Cascade: сервер FK onDelete CASCADE убирает wishes тоже.
            wishes.entries.removeIf { it.value.wishlistId == id }
        }

        coEvery { createWish(any(), any()) } answers {
            val listId = firstArg<Long>()
            val req = secondArg<CreateWishRequest>()
            if (listId !in wishlists) throw ApiException(404, "wishlist not found")
            val id = nextId++
            val dto = WishDto(
                id = id,
                wishlistId = listId,
                title = req.title,
                description = req.description,
                url = req.url,
                imageUrl = req.imageUrl,
                price = req.price,
                currency = req.currency,
                storeName = req.storeName,
                status = req.status,
            )
            wishes[id] = dto
            dto
        }

        coEvery { updateWishStatus(any(), any()) } answers {
            val id = firstArg<Long>()
            val status = secondArg<String>()
            val existing = wishes[id] ?: throw ApiException(404, "not found")
            wishes[id] = existing.copy(status = status)
        }

        coEvery { updateWish(any(), any()) } answers {
            val id = firstArg<Long>()
            val req = secondArg<UpdateWishRequest>()
            val existing = wishes[id] ?: throw ApiException(404, "not found")
            // status optional — если передан, обновляем (как на реальном сервере).
            val dto = existing.copy(
                title = req.title,
                description = req.description,
                url = req.url,
                imageUrl = req.imageUrl,
                price = req.price,
                currency = req.currency,
                storeName = req.storeName,
                status = req.status ?: existing.status,
            )
            wishes[id] = dto
            dto
        }

        coEvery { deleteWish(any()) } answers {
            val id = firstArg<Long>()
            if (wishes.remove(id) == null) throw ApiException(404, "not found")
        }
    }
}
