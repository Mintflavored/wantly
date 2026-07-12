package com.nervs.wantly.backend.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.nervs.wantly.backend.AUTHORIZATION_RATE_LIMIT
import com.nervs.wantly.backend.db.DatabaseFactory.dbQuery
import com.nervs.wantly.backend.db.Users
import com.nervs.wantly.backend.dto.*
import com.nervs.wantly.backend.validation.validate
import com.auth0.jwt.JWT
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

fun Route.authRoutes() {
    route("/api/auth") {
        rateLimit(AUTHORIZATION_RATE_LIMIT) {
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
                val email = req.email.trim().lowercase()
                val user = dbQuery {
                    Users.selectAll().where { Users.id eq userId }.single()
                }
                call.respond(AuthResponse(
                    token = JwtConfig.makeAccessToken(userId, email),
                    refreshToken = JwtConfig.makeRefreshToken(userId, email),
                    userId = userId,
                    email = user[Users.email],
                    displayName = user[Users.displayName],
                ))
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
                val email = row[Users.email]
                call.respond(AuthResponse(
                    token = JwtConfig.makeAccessToken(userId, email),
                    refreshToken = JwtConfig.makeRefreshToken(userId, email),
                    userId = userId,
                    email = email,
                    displayName = row[Users.displayName],
                ))
            }

            post("/refresh") {
                val req = call.receive<RefreshRequest>()
                try {
                    val decoded = JwtConfig.verifier().verify(req.refreshToken)
                    val credential = JWTCredential(decoded)
                    val principal = JwtConfig.refreshPrincipal(credential)
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Недействительный refresh token"))
                    // Выдаём новую пару токенов.
                    call.respond(AuthResponse(
                        token = JwtConfig.makeAccessToken(principal.userId, principal.email),
                        refreshToken = JwtConfig.makeRefreshToken(principal.userId, principal.email),
                        userId = principal.userId,
                        email = principal.email,
                        displayName = dbQuery {
                            Users.selectAll().where { Users.id eq principal.userId }.singleOrNull()?.get(Users.displayName)
                        },
                    ))
                } catch (e: JWTVerificationException) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Недействительный или истекший refresh token"))
                }
            }
        }
    }
}

fun ApplicationCall.userId(): Long? = principal<UserPrincipal>()?.userId
