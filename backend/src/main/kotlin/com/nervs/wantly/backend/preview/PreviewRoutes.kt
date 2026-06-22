package com.nervs.wantly.backend.preview

import com.nervs.wantly.backend.auth.userId
import com.nervs.wantly.backend.dto.ErrorResponse
import com.nervs.wantly.backend.dto.PreviewRequest
import com.nervs.wantly.backend.dto.PreviewResponse
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.previewRoutes() {
    authenticate("auth-jwt") {
        post("/api/preview") {
            // Вызов userId() гарантирует аутентификацию
            call.userId()
            val req = call.receive<PreviewRequest>()

            if (req.url.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("URL не указан"))
                return@post
            }

            val result = PreviewService.fetch(req.url)
            call.respond(result)
        }
    }
}
