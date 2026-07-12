package com.nervs.wantly.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.jwt.JWTCredential
import java.util.Date

data class UserPrincipal(val userId: Long, val email: String)

object JwtConfig {
    private const val ISSUER = "wantly"
    private const val AUDIENCE = "wantly-users"
    val secret: String = System.getenv("WANTLY_JWT_SECRET")
        ?: error("WANTLY_JWT_SECRET env var is required (run backend/deploy/setup_env.sh.example)")
    val realm = "Wantly"

    private const val ACCESS_VALIDITY_MS = 36L * 60 * 60 * 1000 // 36 часов
    private const val REFRESH_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней

    /** Access token: короткоживущий (36h), используется для API запросов. */
    fun makeAccessToken(userId: Long, email: String): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(email)
            .withClaim("userId", userId)
            .withClaim("type", "access")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + ACCESS_VALIDITY_MS))
            .sign(Algorithm.HMAC256(secret))

    /** Refresh token: долгоживущий (30 дней), используется только для /refresh. */
    fun makeRefreshToken(userId: Long, email: String): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(email)
            .withClaim("userId", userId)
            .withClaim("type", "refresh")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + REFRESH_VALIDITY_MS))
            .sign(Algorithm.HMAC256(secret))

    fun verifier() = JWT.require(Algorithm.HMAC256(secret))
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    /**
     * Извлекает principal из access-token.
     *
     * Принимает как `type=access` (новые токены), так и токены без claim `type` —
     * legacy access-token от предыдущего деплоя (до PR #15), у которых не было claim `type`.
     * Это позволяет уже залогиненным клиентам продолжать работу после rollout,
     * пока их 36h access-token не истечёт и клиент не получит новую пару через /refresh.
     * Refresh-токены при этом по-прежнему требуют `type=refresh` (см. [refreshPrincipal]).
     */
    fun principal(credential: JWTCredential): UserPrincipal? {
        val type = credential.payload.getClaim("type")?.asString()
        if (type != null && type != "access") return null
        val userId = credential.payload.getClaim("userId")?.asLong() ?: return null
        return UserPrincipal(userId, credential.payload.subject)
    }

    /** Извлекает principal из refresh-token (type=refresh). */
    fun refreshPrincipal(credential: JWTCredential): UserPrincipal? {
        val type = credential.payload.getClaim("type")?.asString()
        if (type != "refresh") return null
        val userId = credential.payload.getClaim("userId")?.asLong() ?: return null
        return UserPrincipal(userId, credential.payload.subject)
    }
}
