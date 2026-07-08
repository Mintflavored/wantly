package com.nervs.wantly.backend.wishlist

import com.nervs.wantly.backend.auth.userId
import com.nervs.wantly.backend.db.DatabaseFactory.dbQuery
import com.nervs.wantly.backend.db.Wishes
import com.nervs.wantly.backend.db.Wishlists
import com.nervs.wantly.backend.dto.*
import com.nervs.wantly.backend.util.generateShareToken
import com.nervs.wantly.backend.validation.validate
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.wishlistRoutes() {
    authenticate("auth-jwt") {
        route("/api/wishlists") {

            get {
                val uid = call.userId()!!
                val lists = dbQuery {
                    Wishlists.selectAll().where { Wishlists.ownerId eq uid }
                        .orderBy(Wishlists.createdAt to SortOrder.DESC)
                        .map {
                            WishlistDto(
                                id = it[Wishlists.id],
                                title = it[Wishlists.title],
                                description = it[Wishlists.description],
                                isShared = it[Wishlists.isShared],
                                coverColor = it[Wishlists.coverColor],
                                shareToken = it[Wishlists.shareToken],
                            )
                        }
                }
                val counts = dbQuery {
                    Wishes.selectAll().where { Wishes.wishlistId inList lists.map { it.id } }
                        .map { it[Wishes.wishlistId] }
                        .groupingBy { it }.eachCount()
                }
                call.respond(lists.map { it.copy(wishCount = counts[it.id]?.toInt() ?: 0) })
            }

            post {
                val uid = call.userId()!!
                val req = call.receive<CreateWishlistRequest>()
                req.validate()
                val id = dbQuery {
                    Wishlists.insert {
                        it[ownerId] = uid
                        it[title] = req.title.trim()
                        it[description] = req.description?.trim()
                        it[coverColor] = req.coverColor
                    }[Wishlists.id]
                }
                call.respond(
                    HttpStatusCode.Created,
                    WishlistDto(id, req.title.trim(), req.description?.trim(), false, req.coverColor),
                )
            }

            get("/{id}") {
                val uid = call.userId()!!
                val listId = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID"))
                val list = dbQuery {
                    Wishlists.selectAll().where {
                        (Wishlists.id eq listId) and (Wishlists.ownerId eq uid)
                    }.singleOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Список не найден"))
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
                            isShared = list[Wishlists.isShared],
                            coverColor = list[Wishlists.coverColor],
                            wishCount = wishRows.size,
                            shareToken = list[Wishlists.shareToken],
                        ),
                        wishes = wishRows.map { it.toWishDto() },
                    ),
                )
            }

            patch("/{id}") {
                val uid = call.userId()!!
                val listId = call.parameters["id"]?.toLongOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID"))
                val req = call.receive<UpdateWishlistRequest>()
                req.validate()
                // PUT-стиль: перезаписываем все редактируемые поля. isShared
                // остаётся серверным (как в CreateWishlistRequest). ownership через
                // WHERE (id, ownerId) → 404 если чужой (don't leak existence).
                val updated = dbQuery {
                    Wishlists.update({ (Wishlists.id eq listId) and (Wishlists.ownerId eq uid) }) {
                        it[title] = req.title.trim()
                        it[description] = req.description?.trim()
                        it[coverColor] = req.coverColor
                    }
                }
                if (updated == 0) {
                    return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Список не найден"))
                }
                // Перечитываем строку, чтобы вернуть актуальный isShared и поля.
                val row = dbQuery {
                    Wishlists.selectAll().where { Wishlists.id eq listId }.single()
                }
                call.respond(
                    HttpStatusCode.OK,
                    WishlistDto(
                        id = row[Wishlists.id],
                        title = row[Wishlists.title],
                        description = row[Wishlists.description],
                        isShared = row[Wishlists.isShared],
                        coverColor = row[Wishlists.coverColor],
                        shareToken = row[Wishlists.shareToken],
                    ),
                )
            }

            patch("/{id}/share") {
                // Set isShared: владелец включает/выключает публичный доступ.
                // Принимает desired state (enabled), не blind toggle — иначе stale
                // dialog state мог ревокнуть активный share или включить выключенный.
                // При включении — генерируем новый share_token (старый отзывается).
                // При выключении — очищаем token, старые ссылки перестают работать.
                val uid = call.userId()!!
                val listId = call.parameters["id"]?.toLongOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID"))
                val req = call.receive<SetShareRequest>()
                val row = dbQuery {
                    val existing = Wishlists.selectAll().where {
                        (Wishlists.id eq listId) and (Wishlists.ownerId eq uid)
                    }.singleOrNull() ?: return@dbQuery null
                    // Idempotent: если уже в desired state — ничего не делаем с token.
                    val newToken = if (req.enabled && !existing[Wishlists.isShared]) {
                        generateShareToken()
                    } else if (req.enabled) {
                        existing[Wishlists.shareToken] // уже shared — сохраняем текущий token
                    } else {
                        null // выключаем — очищаем
                    }
                    Wishlists.update({ (Wishlists.id eq listId) and (Wishlists.ownerId eq uid) }) {
                        it[Wishlists.isShared] = req.enabled
                        it[Wishlists.shareToken] = newToken
                    }
                    Wishlists.selectAll().where { Wishlists.id eq listId }.single()
                } ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Список не найден"))
                call.respond(
                    HttpStatusCode.OK,
                    WishlistDto(
                        id = row[Wishlists.id],
                        title = row[Wishlists.title],
                        description = row[Wishlists.description],
                        isShared = row[Wishlists.isShared],
                        coverColor = row[Wishlists.coverColor],
                        shareToken = row[Wishlists.shareToken],
                    ),
                )
            }

            delete("/{id}") {
                val uid = call.userId()!!
                val listId = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID"))
                val deleted = dbQuery {
                    Wishlists.deleteWhere { (Wishlists.id eq listId) and (Wishlists.ownerId eq uid) }
                }
                if (deleted == 0) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Список не найден"))
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun ResultRow.toWishDto() = WishDto(
    id = this[Wishes.id],
    wishlistId = this[Wishes.wishlistId],
    title = this[Wishes.title],
    description = this[Wishes.description],
    url = this[Wishes.url],
    imageUrl = this[Wishes.imageUrl],
    price = this[Wishes.price],
    currency = this[Wishes.currency],
    storeName = this[Wishes.storeName],
    status = this[Wishes.status],
)
