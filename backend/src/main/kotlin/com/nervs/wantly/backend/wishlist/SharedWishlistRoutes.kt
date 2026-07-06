package com.nervs.wantly.backend.wishlist

import com.nervs.wantly.backend.db.DatabaseFactory.dbQuery
import com.nervs.wantly.backend.db.Wishes
import com.nervs.wantly.backend.db.Wishlists
import com.nervs.wantly.backend.dto.ErrorResponse
import com.nervs.wantly.backend.dto.WishlistDetailResponse
import com.nervs.wantly.backend.dto.WishlistDto
import com.nervs.wantly.backend.dto.WishDto
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * Публичный (без JWT) доступ к shared wishlist по share_token.
 * Возвращает только публичные поля: title/description/coverColor/wishes.
 * НЕ возвращает ownerId, email владельца, isShared-флаг (получателю не нужен).
 */
fun Route.sharedWishlistRoutes() {
    route("/api/shared/{token}") {
        get {
            val token = call.parameters["token"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный токен"))
            val list = dbQuery {
                Wishlists.selectAll().where {
                    (Wishlists.shareToken eq token) and (Wishlists.isShared eq true)
                }.singleOrNull()
            } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Список не найден"))
            val listId = list[Wishlists.id]
            val wishRows = dbQuery {
                Wishes.selectAll().where { Wishes.wishlistId eq listId }
                    .orderBy(Wishes.sortOrder to SortOrder.ASC, Wishes.createdAt to SortOrder.DESC)
                    .toList()
            }
            call.respond(
                WishlistDetailResponse(
                    wishlist = WishlistDto(
                        id = list[Wishlists.id],
                        title = list[Wishlists.title],
                        description = list[Wishlists.description],
                        isShared = true,
                        coverColor = list[Wishlists.coverColor],
                        wishCount = wishRows.size,
                        // shareToken НЕ возвращаем в public response — получатель
                        // не должен видеть токен, только владелец при toggle.
                        shareToken = null,
                    ),
                    wishes = wishRows.map {
                        WishDto(
                            id = it[Wishes.id],
                            wishlistId = it[Wishes.wishlistId],
                            title = it[Wishes.title],
                            description = it[Wishes.description],
                            url = it[Wishes.url],
                            imageUrl = it[Wishes.imageUrl],
                            price = it[Wishes.price],
                            currency = it[Wishes.currency],
                            storeName = it[Wishes.storeName],
                            status = it[Wishes.status],
                        )
                    },
                ),
            )
        }
    }
}
