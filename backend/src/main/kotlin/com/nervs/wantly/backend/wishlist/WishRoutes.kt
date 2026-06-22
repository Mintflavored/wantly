package com.nervs.wantly.backend.wishlist

import com.nervs.wantly.backend.auth.userId
import com.nervs.wantly.backend.db.DatabaseFactory.dbQuery
import com.nervs.wantly.backend.db.Wishes
import com.nervs.wantly.backend.db.Wishlists
import com.nervs.wantly.backend.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.wishRoutes() {
    authenticate("auth-jwt") {
        route("/api/wishlists/{listId}/wishes") {

            post {
                val uid = call.userId()!!
                val listId = call.parameters["listId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID списка"))
                val owns = dbQuery {
                    Wishlists.selectAll().where {
                        (Wishlists.id eq listId) and (Wishlists.ownerId eq uid)
                    }.count() > 0
                }
                if (!owns) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Список не найден"))

                val req = call.receive<CreateWishRequest>()
                val id = dbQuery {
                    Wishes.insert {
                        it[wishlistId] = listId
                        it[title] = req.title.trim()
                        it[description] = req.description?.trim()
                        it[url] = req.url?.trim()
                        it[imageUrl] = req.imageUrl?.trim()
                        it[price] = req.price
                        it[currency] = req.currency
                        it[storeName] = req.storeName?.trim()
                        it[status] = req.status
                    }[Wishes.id]
                }
                call.respond(
                    HttpStatusCode.Created,
                    WishDto(
                        id, listId, req.title.trim(), req.description?.trim(),
                        req.url?.trim(), req.imageUrl?.trim(), req.price,
                        req.currency, req.storeName?.trim(), req.status,
                    ),
                )
            }
        }

        route("/api/wishes/{id}") {

            patch("/status") {
                val uid = call.userId()!!
                val wishId = call.parameters["id"]?.toLongOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID"))
                val req = call.receive<UpdateWishStatusRequest>()
                val owns = dbQuery {
                    (Wishes innerJoin Wishlists)
                        .selectAll().where { (Wishes.id eq wishId) and (Wishlists.ownerId eq uid) }
                        .count() > 0
                }
                if (!owns) return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Желание не найдено"))
                dbQuery { Wishes.update({ Wishes.id eq wishId }) { it[Wishes.status] = req.status } }
                call.respond(HttpStatusCode.OK, mapOf("status" to req.status))
            }

            delete {
                val uid = call.userId()!!
                val wishId = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный ID"))
                val owns = dbQuery {
                    (Wishes innerJoin Wishlists)
                        .selectAll().where { (Wishes.id eq wishId) and (Wishlists.ownerId eq uid) }
                        .count() > 0
                }
                if (!owns) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Желание не найдено"))
                dbQuery { Wishes.deleteWhere { Wishes.id eq wishId } }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
