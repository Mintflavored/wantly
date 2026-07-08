package com.nervs.wantly.backend.util

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Извлекает реальный IP клиента для rate-limiting.
 *
 * За nginx (единственный entry point в production) — remoteHost = 127.0.0.1,
 * поэтому читаем X-Real-IP (nginx ставит через `proxy_set_header X-Real-IP`).
 * Fallback на X-Forwarded-For (первый entry) → origin.remoteHost (тесты/прямой доступ).
 *
 * Доверяем header'ам, потому что прямого доступа к Ktor нет — только через nginx.
 */
fun ApplicationCall.getClientIp(): String =
    request.headers["X-Real-IP"]?.takeIf { it.isNotBlank() }
        ?: request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: request.origin.remoteHost
