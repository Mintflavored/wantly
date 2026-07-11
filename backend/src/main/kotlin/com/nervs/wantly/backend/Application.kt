package com.nervs.wantly.backend

import com.nervs.wantly.backend.auth.JwtConfig
import com.nervs.wantly.backend.auth.UserPrincipal
import com.nervs.wantly.backend.auth.authRoutes
import com.nervs.wantly.backend.db.DatabaseFactory
import com.nervs.wantly.backend.db.DatabaseFactory.dbQuery
import com.nervs.wantly.backend.dto.ErrorResponse
import com.nervs.wantly.backend.preview.previewRoutes
import com.nervs.wantly.backend.util.getClientIp
import com.nervs.wantly.backend.wishlist.sharedWishlistRoutes
import com.nervs.wantly.backend.wishlist.wishRoutes
import com.nervs.wantly.backend.wishlist.wishlistRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("Application")

/** Имя rate-limit провайдера для auth endpoints (login/register): 5 попыток/min. */
val AUTHORIZATION_RATE_LIMIT = RateLimitName("authorization")

/** Имя rate-limit провайдера для preview endpoint: 5/min (Chromium = ~200MB RAM + single worker). */
val PREVIEW_RATE_LIMIT = RateLimitName("preview")

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

/**
 * Ktor-loadable entry point. application.conf указывает на этот метод —
 * EngineMain через reflection ищет `module(Application)` с одним параметром.
 * Это ЕДИНСТВЕННЫЙ public метод с именем `module`, чтобы Ktor 3.x не пытался
 * резолвить overload'ы. Тесты используют [moduleWithDb].
 */
fun Application.module() {
    moduleWithDb(configureDb = true)
}

/**
 * Internal setup. Тесты вызывают с configureDb=false чтобы пропустить
 * HikariCP/Flyway и использовать свою in-memory H2.
 */
internal fun Application.moduleWithDb(configureDb: Boolean) {
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
        // App — нативное Android приложение, CORS не нужен (нет browser origin).
        // anyHost() spec-OK без credentials (auth через Bearer header, не cookies).
        // Если в будущем будет web-frontend, заменить на host("wantlyapp.ru").
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
        // 400 — явная ошибка валидации из route-хендлеров
        // (Validators.kt бросает ValidationException с конкретным сообщением).
        // Handler стоит ВЫШЕ IllegalArgumentException — Ktor выбирает наиболее
        // конкретный matching handler, поэтому ValidationException матчится сюда,
        // а случайные library IllegalArgumentException — в fallback ниже.
        exception<com.nervs.wantly.backend.validation.ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Некорректный запрос"),
            )
        }
        // 400 — fallback для library IllegalArgumentException (BCrypt, Exposed и т.п.).
        // Сообщение НЕ прокидывается — не утекают internal details.
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
        // 429 — RateLimit plugin отверг запрос. Без этого handler'а Ktor
        // возвращает пустой body, Android client показывает generic "Ошибка сервера".
        // ErrorResponse сохраняет JSON-формат + Retry-After header от plugin'а.
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("Слишком много запросов. Попробуйте позже."),
            )
        }
    }

    // Логирование каждого запроса: method, path, status, duration.
    // dep ktor-server-call-logging уже в classpath.
    // Пропускаем /api/shared/{token} — token не должен попадать в логи,
    // иначе любой с доступом к логам читает чужие shared-списки.
    install(CallLogging) {
        // Пропускаем /api/shared/{token} — token не должен попадать в логи.
        filter { call ->
            val uri = call.request.local.uri
            !uri.startsWith("/api/shared/")
        }
    }

    // Rate limiting: глобальный (60 req/min/IP) + auth-жёсткий (5 req/min/IP).
    // requestKey = X-Real-IP (nginx ставит) с fallback на remoteHost (тесты).
    install(RateLimit) {
        // global {} — применяется ко ВСЕМ запросам автоматически (в отличие от
        // register {} который только определяет провайдер для явного rateLimit()).
        // 500/min: достаточно для sync (pullInternal делает 1+N запросов для N списков,
        // power user с 400+ wishlists не упрётся). Abuse backpressure работает.
        global {
            rateLimiter(500, 60.seconds)
            requestKey { call -> call.getClientIp() }
        }
        register(AUTHORIZATION_RATE_LIMIT) {
            rateLimiter(5, 60.seconds)
            requestKey { call -> call.getClientIp() }
        }
        // Preview = Chromium ~200MB RAM + single-worker. 5/min — жёсткий лимит.
        register(PREVIEW_RATE_LIMIT) {
            rateLimiter(5, 60.seconds)
            requestKey { call -> call.getClientIp() }
        }
    }

    routing {
        get("/health") { call.respondText("OK") }
        // Readiness probe: проверяет DB коннект (SELECT 1 — constant-time,
        // не зависит от размера таблицы). nginx/LB использует для rotation.
        get("/health/ready") {
            try {
                // SELECT 1 — реальный DB ping. Exposed лениво инициализирует
                // connection — пустая транзакция может НЕ проверить коннект.
                // SELECT 1 гарантирует checkout + round-trip.
                dbQuery { org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec("SELECT 1") }
                call.respondText("OK")
            } catch (e: Exception) {
                logger.warn("Readiness check failed", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("База данных недоступна"))
            }
        }
        authRoutes()
        wishlistRoutes()
        wishRoutes()
        previewRoutes()
        // Публичный доступ к shared wishlist — БЕЗ authenticate (как /health).
        // Только isShared=true списки доступны через share_token.
        sharedWishlistRoutes()
    }
}
