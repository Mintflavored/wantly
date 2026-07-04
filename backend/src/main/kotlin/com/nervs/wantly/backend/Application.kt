package com.nervs.wantly.backend

import com.nervs.wantly.backend.auth.JwtConfig
import com.nervs.wantly.backend.auth.UserPrincipal
import com.nervs.wantly.backend.auth.authRoutes
import com.nervs.wantly.backend.db.DatabaseFactory
import com.nervs.wantly.backend.dto.ErrorResponse
import com.nervs.wantly.backend.preview.previewRoutes
import com.nervs.wantly.backend.wishlist.wishRoutes
import com.nervs.wantly.backend.wishlist.wishlistRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

/**
 * Ktor-loadable entry point. application.conf указывает на этот метод —
 * EngineMain через reflection ищет `module(Application)` с одним параметром.
 * НЕ добавлять сюда параметры (Kotlin default args не работают через reflection).
 * Тесты используют overload [module] с configureDb=false.
 */
fun Application.module() {
    module(configureDb = true)
}

fun Application.module(configureDb: Boolean) {
    if (configureDb) {
        val dbUrl = environment.config.propertyOrNull("db.url")?.getString()
            ?: "jdbc:postgresql://localhost:5432/wantly"
        val dbUser = environment.config.propertyOrNull("db.user")?.getString() ?: "wantly"
        val dbPass = environment.config.propertyOrNull("db.password")?.getString()
            ?: System.getenv("WANTLY_DB_PASSWORD")
            ?: error("WANTLY_DB_PASSWORD not set")

        val dataSource = DatabaseFactory.init(dbUrl, dbUser, dbPass)

        // Закрыть Hikari pool при остановке приложения (Ktor testApplication,
        // dev reloads, graceful shutdown). Иначе каждый restart копит idle
        // PostgreSQL connections — можно исчерпать max_connections.
        environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
            runCatching {
                (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
                logger.info("HikariCP pool closed")
            }.onFailure { logger.warn("Failed to close HikariCP pool", it) }
        }
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = JwtConfig.realm
            verifier(JwtConfig.verifier())
            validate { credential -> JwtConfig.principal(credential) }
        }
    }

    install(StatusPages) {
        // 400 — клиент прислал плохой запрос (Ktor кидает при неудачной
        // десериализации тела, неверном path-параметре, и т.д.).
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный запрос"))
        }
        // 400 — явный IllegalArgumentException из route-хендлеров
        // (например, невалидный email/статус).
        exception<IllegalArgumentException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный запрос"))
        }
        // 404 — Ktor кидает when route/path не найден.
        exception<io.ktor.server.plugins.NotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Не найдено"))
        }
        // 500 — fallback. Без cause.message — не течёт internal detail
        // (stack trace, имена классов, SQL-текст и т.п.) наружу.
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Внутренняя ошибка сервера"),
            )
        }
    }

    routing {
        get("/health") { call.respondText("OK") }
        authRoutes()
        wishlistRoutes()
        wishRoutes()
        previewRoutes()
    }
}
