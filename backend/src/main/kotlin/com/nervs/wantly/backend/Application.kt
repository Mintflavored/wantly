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

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val dbUrl = environment.config.propertyOrNull("db.url")?.getString()
        ?: "jdbc:postgresql://localhost:5432/wantly"
    val dbUser = environment.config.propertyOrNull("db.user")?.getString() ?: "wantly"
    val dbPass = environment.config.propertyOrNull("db.password")?.getString()
        ?: System.getenv("WANTLY_DB_PASSWORD")
        ?: error("WANTLY_DB_PASSWORD not set")

    DatabaseFactory.init(dbUrl, dbUser, dbPass)

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
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Внутренняя ошибка: ${cause.message}"),
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
