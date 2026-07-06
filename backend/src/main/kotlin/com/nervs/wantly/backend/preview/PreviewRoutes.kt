package com.nervs.wantly.backend.preview

import com.nervs.wantly.backend.auth.userId
import com.nervs.wantly.backend.dto.PreviewRequest
import com.nervs.wantly.backend.dto.PreviewResponse
import com.nervs.wantly.backend.validation.validate
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
            req.validate() // blank/length; scheme-формат остаётся в PreviewService

            val result = PreviewService.fetch(req.url)
            call.respond(result)
        }
    }
}
