package com.nervs.wantly.backend.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.nervs.wantly.backend.db.DatabaseFactory.dbQuery
import com.nervs.wantly.backend.db.Users
import com.nervs.wantly.backend.dto.*
import com.nervs.wantly.backend.validation.validate
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import com.nervs.wantly.backend.AUTHORIZATION_RATE_LIMIT
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

fun Route.authRoutes() {
    route("/api/auth") {
        // Жёсткий rate-limit на auth: 5 req/min/IP. BCrypt cost 12 = ~200-400ms CPU,
        // credential stuffing забивает Netty event-loop. withRateLimit поверх глобального.
        rateLimit(AUTHORIZATION_RATE_LIMIT) {
            // RateLimitName inline-class — Ktor передаёт по value.
            post("/register") {
            val req = call.receive<RegisterRequest>()
            req.validate()
            val existing = dbQuery {
                Users.selectAll().where { Users.email eq req.email.trim().lowercase() }.count() > 0
            }
            if (existing) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Пользователь с таким email уже зарегистрирован"))
                return@post
            }
            val hash = BCrypt.withDefaults().hashToString(12, req.password.toCharArray())
            val userId = dbQuery {
                Users.insert {
                    it[email] = req.email.trim().lowercase()
                    it[passwordHash] = hash
                    it[displayName] = req.displayName?.trim()
                }[Users.id]
            }
            val token = JwtConfig.makeToken(userId, req.email.trim().lowercase())
            val user = dbQuery {
                Users.selectAll().where { Users.id eq userId }.single()
            }
            call.respond(AuthResponse(token, userId, user[Users.email], user[Users.displayName]))
        }

        post("/login") {
            val req = call.receive<LoginRequest>()
            req.validate()
            val row = dbQuery {
                Users.selectAll().where { Users.email eq req.email.trim().lowercase() }.singleOrNull()
            }
            if (row == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Неверный email или пароль"))
                return@post
            }
            val ok = BCrypt.verifyer().verify(req.password.toCharArray(), row[Users.passwordHash]).verified
            if (!ok) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Неверный email или пароль"))
                return@post
            }
            val userId = row[Users.id]
            val token = JwtConfig.makeToken(userId, row[Users.email])
            call.respond(AuthResponse(token, userId, row[Users.email], row[Users.displayName]))
        }
        } // rateLimit(AuthorizationRateLimit)
    }
}

fun ApplicationCall.userId(): Long? = principal<UserPrincipal>()?.userId
