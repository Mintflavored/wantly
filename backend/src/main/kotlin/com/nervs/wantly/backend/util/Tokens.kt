package com.nervs.wantly.backend.util

import java.security.SecureRandom
import java.util.Base64

/**
 * Генерация opaque share-token: 16 случайных байт → base64url без padding (22 символа).
 * 128 бит энтропии — не угадываем, не требует DB round-trip для проверки уникальности
 * (DB UNIQUE констрейнт ловит коллизию, но 2^128 делает её практически невозможной).
 */
private val secureRandom = SecureRandom()

fun generateShareToken(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
