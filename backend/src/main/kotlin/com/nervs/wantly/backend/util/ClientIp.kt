package com.nervs.wantly.backend.util

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Извлекает реальный IP клиента для rate-limiting.
 *
 * Доверяем X-Real-IP / X-Forwarded-For ТОЛЬКО когда peer = loopback (nginx proxy).
 * Если Ktor доступен напрямую (port 8080, dev/test) — игнорируем spoofable headers
 * и используем TCP peer. Это закрывает bypass: атакующий стучится на 8080 напрямую,
 * подделывает X-Real-IP на каждый запрос → fresh rate-limit bucket каждый раз.
 */
fun ApplicationCall.getClientIp(): String {
    val peer = request.origin.remoteHost
    // Доверяем proxy headers только от loopback (nginx на том же хосте).
    if (peer == "127.0.0.1" || peer == "0:0:0:0:0:0:0:1" || peer == "::1") {
        return request.headers["X-Real-IP"]?.takeIf { it.isNotBlank() }
            ?: request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: peer
    }
    // Прямой доступ (не через nginx) — используем TCP peer, header'ам не верим.
    return peer
}
