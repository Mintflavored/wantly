package com.nervs.wantly.backend

import com.nervs.wantly.backend.auth.JwtConfig
import com.nervs.wantly.backend.db.Users
import com.nervs.wantly.backend.db.Wishes
import com.nervs.wantly.backend.db.Wishlists
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты API на in-memory H2 (MODE=PostgreSQL).
 *
 * DatabaseFactory/Flyway/HikariCP НЕ используются — SchemaUtils.create
 * поднимает схему прямо в H2. Application.module(configureDb=false)
 * пропускает production DB init.
 */
class ApplicationTest {

    @BeforeTest
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        transaction {
            SchemaUtils.create(Users, Wishlists, Wishes)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            Wishes.deleteAll()
            Wishlists.deleteAll()
            Users.deleteAll()
        }
    }

    // ── Helpers ──────────────────────────────────────────

    private fun testApp(block: suspend (client: io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { moduleWithDb(configureDb = false) }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            block(client)
        }

    @kotlinx.serialization.Serializable
    data class AuthResponse(
        val token: String,
        val userId: Long,
        val email: String,
        val displayName: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class WishlistDto(
        val id: Long,
        val title: String,
        val description: String? = null,
        val coverColor: Int = 0,
    )

    @kotlinx.serialization.Serializable
    data class CreateWishlistRequest(
        val title: String,
        val description: String? = null,
        val coverColor: Int = 0,
    )

    @kotlinx.serialization.Serializable
    data class WishlistDetailResponse(
        val wishlist: WishlistDto,
        val wishes: List<Map<String, String?>> = emptyList(),
    )

    private suspend fun io.ktor.client.HttpClient.register(
        email: String,
        password: String = "test123",
        name: String? = "Test User",
    ): AuthResponse {
        val resp = post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to name,
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body()
    }

    private suspend fun io.ktor.client.HttpClient.login(
        email: String,
        password: String = "test123",
    ): AuthResponse {
        val resp = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to password))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body()
    }

    // ── Health ───────────────────────────────────────────

    @Test
    fun `health endpoint returns OK`() = testApp { client ->
        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("OK", resp.bodyAsText())
    }

    // ── Auth ─────────────────────────────────────────────

    @Test
    fun `register creates account and returns JWT`() = testApp { client ->
        val auth = client.register("test@example.com")
        assertNotNull(auth.token)
        assertTrue(auth.token.isNotEmpty())
        assertEquals("test@example.com", auth.email)
        assertEquals("Test User", auth.displayName)
    }

    @Test
    fun `login with valid credentials returns JWT`() = testApp { client ->
        client.register("login@example.com")
        val auth = client.login("login@example.com")
        assertNotNull(auth.token)
        assertEquals("login@example.com", auth.email)
    }

    @Test
    fun `login with wrong password returns 401`() = testApp { client ->
        client.register("wrong@example.com")
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "wrong@example.com", "password" to "WRONG"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `login with unknown email returns 401`() = testApp { client ->
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "ghost@example.com", "password" to "x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `register duplicate email returns 409 Conflict`() = testApp { client ->
        client.register("dup@example.com")
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "dup@example.com", "password" to "test123"))
        }
        // AuthRoutes проверяет existing email ДО insert и возвращает 409.
        // Строгий ассерт ловит регрессию: если guard уберут, дубликат свалится
        // в unique-constraint → 500, и этот тест это заметит.
        assertEquals(
            HttpStatusCode.Conflict,
            resp.status,
            "Expected 409 Conflict for duplicate email, got ${resp.status}",
        )
    }

    // ── Wishlist CRUD ────────────────────────────────────

    @Test
    fun `create wishlist returns 201 with data`() = testApp { client ->
        val auth = client.register("wl@example.com")
        val resp = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Birthday", "My wishes", 2))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val dto: WishlistDto = resp.body()
        assertEquals("Birthday", dto.title)
        assertEquals(2, dto.coverColor)
    }

    @Test
    fun `get wishlists returns user lists only`() = testApp { client ->
        val alice = client.register("alice@example.com")
        val bob = client.register("bob@example.com")

        client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Alice's list"))
        }
        client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Bob's list"))
        }

        val aliceLists: List<WishlistDto> = client.get("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
        }.body()
        assertEquals(1, aliceLists.size)
        assertEquals("Alice's list", aliceLists[0].title)

        val bobLists: List<WishlistDto> = client.get("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }.body()
        assertEquals(1, bobLists.size)
        assertEquals("Bob's list", bobLists[0].title)
    }

    @Test
    fun `delete wishlist returns 204 and removes it`() = testApp { client ->
        val auth = client.register("del@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("To delete"))
        }.body()

        val delResp = client.delete("/api/wishlists/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
        }
        assertEquals(HttpStatusCode.NoContent, delResp.status)

        val remaining: List<WishlistDto> = client.get("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
        }.body()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `get wishlist detail by other user returns 404`() = testApp { client ->
        val alice = client.register("owner@example.com")
        val bob = client.register("intruder@example.com")

        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Alice's private"))
        }.body()

        val resp = client.get("/api/wishlists/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `unauthenticated request returns 401`() = testApp { client ->
        val resp = client.get("/api/wishlists")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
