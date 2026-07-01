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
    private val validityMs = 36L * 60 * 60 * 1000

    fun makeToken(userId: Long, email: String): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(email)
            .withClaim("userId", userId)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + validityMs))
            .sign(Algorithm.HMAC256(secret))

    fun verifier() = JWT.require(Algorithm.HMAC256(secret))
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    fun principal(credential: JWTCredential): UserPrincipal? {
        val userId = credential.payload.getClaim("userId")?.asLong() ?: return null
        return UserPrincipal(userId, credential.payload.subject)
    }
}
