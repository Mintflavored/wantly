package com.nervs.wantly.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nervs.wantly.backend.auth.JwtConfig
import com.nervs.wantly.backend.db.PreviewCacheTable
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
import kotlin.test.assertNotEquals
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
            SchemaUtils.create(Users, Wishlists, Wishes, PreviewCacheTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            Wishes.deleteAll()
            Wishlists.deleteAll()
            Users.deleteAll()
            PreviewCacheTable.deleteAll()
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
        val refreshToken: String,
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
        val isShared: Boolean = false,
        val wishCount: Int = 0,
        val shareToken: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class CreateWishlistRequest(
        val title: String,
        val description: String? = null,
        val coverColor: Int = 0,
    )

    @kotlinx.serialization.Serializable
    data class UpdateWishlistRequest(
        val title: String,
        val description: String? = null,
        val coverColor: Int = 0,
    )

    @kotlinx.serialization.Serializable
    data class CreateWishRequest(
        val title: String,
        val description: String? = null,
        val url: String? = null,
        val imageUrl: String? = null,
        val price: Double? = null,
        val currency: String = "RUB",
        val storeName: String? = null,
        val status: String = "WANTED",
    )

    @kotlinx.serialization.Serializable
    data class UpdateWishRequest(
        val title: String,
        val description: String? = null,
        val url: String? = null,
        val imageUrl: String? = null,
        val price: Double? = null,
        val currency: String = "RUB",
        val storeName: String? = null,
        val status: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class WishDto(
        val id: Long,
        val wishlistId: Long,
        val title: String,
        val description: String? = null,
        val url: String? = null,
        val imageUrl: String? = null,
        val price: Double? = null,
        val currency: String = "RUB",
        val storeName: String? = null,
        val status: String = "WANTED",
    )

    @kotlinx.serialization.Serializable
    data class WishlistDetailResponse(
        val wishlist: WishlistDto,
        val wishes: List<Map<String, String?>> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    data class ErrorResponse(val error: String)

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
    fun `update wishlist returns 200 with new fields`() = testApp { client ->
        val auth = client.register("edit-wl@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Old title", "Old desc", 0))
        }.body()

        val resp = client.patch("/api/wishlists/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateWishlistRequest("New title", "New desc", 3))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val dto: WishlistDto = resp.body()
        assertEquals("New title", dto.title)
        assertEquals("New desc", dto.description)
        assertEquals(3, dto.coverColor)
        assertEquals(created.id, dto.id)
    }

    @Test
    fun `update wishlist owned by another user returns 404`() = testApp { client ->
        val alice = client.register("edit-owner@example.com")
        val bob = client.register("edit-intruder@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Alice's list"))
        }.body()

        val resp = client.patch("/api/wishlists/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateWishlistRequest("Hijacked"))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)

        // Содержимое не изменилось.
        val detail: WishlistDetailResponse = client.get("/api/wishlists/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
        }.body()
        assertEquals("Alice's list", detail.wishlist.title)
    }

    @Test
    fun `update wish returns 200 with new fields`() = testApp { client ->
        val auth = client.register("edit-wish@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val created: WishDto = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Old wish", price = 100.0, status = "RESERVED"))
        }.body()

        val resp = client.patch("/api/wishes/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateWishRequest(title = "Edited wish", price = 250.0, storeName = "Shop"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val dto: WishDto = resp.body()
        assertEquals("Edited wish", dto.title)
        assertEquals(250.0, dto.price)
        assertEquals("Shop", dto.storeName)
        // status optional в UpdateWishRequest: не передан → сохраняется старый.
        assertEquals("RESERVED", dto.status)
    }

    @Test
    fun `update wish with status field updates status`() = testApp { client ->
        val auth = client.register("edit-wish-status@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val created: WishDto = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", status = "WANTED"))
        }.body()

        val resp = client.patch("/api/wishes/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateWishRequest(title = "Wish", status = "PURCHASED"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val dto: WishDto = resp.body()
        assertEquals("PURCHASED", dto.status)
    }

    @Test
    fun `update wish owned by another user returns 404`() = testApp { client ->
        val alice = client.register("wish-owner@example.com")
        val bob = client.register("wish-intruder@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val wish: WishDto = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Alice's wish"))
        }.body()

        val resp = client.patch("/api/wishes/${wish.id}") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateWishRequest(title = "Hijacked"))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `unauthenticated request returns 401`() = testApp { client ->
        val resp = client.get("/api/wishlists")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ── Валидация запросов ──────────────────────────────────────────────

    private suspend fun assertBadRequest(
        client: io.ktor.client.HttpClient,
        resp: io.ktor.client.statement.HttpResponse,
        expectedMessage: String,
    ) {
        assertEquals(HttpStatusCode.BadRequest, resp.status, "Expected 400, got ${resp.status}")
        val body: ErrorResponse = resp.body()
        assertEquals(expectedMessage, body.error)
    }

    @Test
    fun `register rejects blank email with specific message`() = testApp { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "", "password" to "test123"))
        }
        assertBadRequest(client, resp, "Email не указан")
    }

    @Test
    fun `register rejects malformed email with specific message`() = testApp { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "not-an-email", "password" to "test123"))
        }
        assertBadRequest(client, resp, "Email некорректный")
    }

    @Test
    fun `register rejects short password with specific message`() = testApp { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "short@example.com", "password" to "12345"))
        }
        assertBadRequest(client, resp, "Пароль должен быть от 6 до 72 символов")
    }

    @Test
    fun `register rejects overlong password with specific message`() = testApp { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "long@example.com", "password" to "x".repeat(73)))
        }
        assertBadRequest(client, resp, "Пароль должен быть от 6 до 72 символов")
    }

    @Test
    fun `register rejects overlong displayName with specific message`() = testApp { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "email" to "name@example.com",
                    "password" to "test123",
                    "displayName" to "x".repeat(101),
                ),
            )
        }
        assertBadRequest(client, resp, "Имя слишком длинное")
    }

    @Test
    fun `login with blank fields returns 400 without detail`() = testApp { client ->
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "", "password" to ""))
        }
        assertBadRequest(client, resp, "Некорректный запрос")
    }

    @Test
    fun `create wishlist rejects blank title with specific message`() = testApp { client ->
        val auth = client.register("wlblank@example.com")
        val resp = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("   "))
        }
        assertBadRequest(client, resp, "Название списка обязательно")
    }

    @Test
    fun `create wishlist rejects overlong title with specific message`() = testApp { client ->
        val auth = client.register("wllong@example.com")
        val resp = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("x".repeat(201)))
        }
        assertBadRequest(client, resp, "Название списка слишком длинное")
    }

    @Test
    fun `create wishlist rejects invalid coverColor with specific message`() = testApp { client ->
        val auth = client.register("wlcolor@example.com")
        val resp = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Title", null, 99))
        }
        assertBadRequest(client, resp, "Некорректный цвет списка")
    }

    @Test
    fun `create wish rejects invalid currency with specific message`() = testApp { client ->
        val auth = client.register("wishcurrency@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", currency = "RUBLE"))
        }
        assertBadRequest(client, resp, "Валюта некорректная (ожидается код ISO 4217, например RUB)")
    }

    // ── Regression: серверная нормализация совпадает с клиентской ────────
    // (codex P2 x2 — "Normalize currency/URL before validating")
    //
    // App принимает `usd` и `example.com` raw — без server-side нормализации
    // легитимные значения reject'ились бы 400, и SyncManager бесконечно ретраил.

    @Test
    fun `create wish accepts lowercase currency and normalizes to uppercase`() = testApp { client ->
        val auth = client.register("wishlowcurrency@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", currency = "usd"))
        }
        assertEquals(HttpStatusCode.Created, resp.status, "Expected 201, got ${resp.status}")
        val dto: WishDto = resp.body()
        assertEquals("USD", dto.currency) // нормализовано
    }

    @Test
    fun `create wish accepts schemeless URL and normalizes to https`() = testApp { client ->
        val auth = client.register("wishschemeless@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", url = "example.com/item"))
        }
        assertEquals(HttpStatusCode.Created, resp.status, "Expected 201, got ${resp.status}")
        val dto: WishDto = resp.body()
        assertEquals("https://example.com/item", dto.url) // нормализовано
    }

    @Test
    fun `update wish accepts lowercase currency and schemeless url`() = testApp { client ->
        val auth = client.register("wishupdatenorm@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val wish: WishDto = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish"))
        }.body()
        val resp = client.patch("/api/wishes/${wish.id}") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateWishRequest(title = "Wish", currency = "eur", url = "shop.example"))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "Expected 200, got ${resp.status}")
        val dto: WishDto = resp.body()
        assertEquals("EUR", dto.currency)
        assertEquals("https://shop.example", dto.url)
    }

    // ── Regression: scheme detection должен быть case-insensitive + reject не-HTTP ──
    // (codex P2 — "Preserve existing URL schemes during normalization")

    @Test
    fun `create wish preserves uppercase HTTPS scheme without corrupting url`() = testApp { client ->
        val auth = client.register("wishuppercase@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", url = "HTTPS://shop.example/item"))
        }
        assertEquals(HttpStatusCode.Created, resp.status, "Expected 201, got ${resp.status}")
        val dto: WishDto = resp.body()
        // Не должно быть "https://HTTPS://..." — схема распознана, URL сохранён как есть.
        assertEquals("HTTPS://shop.example/item", dto.url)
    }

    @Test
    fun `create wish rejects non-HTTP scheme with specific message`() = testApp { client ->
        val auth = client.register("wishftp@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", url = "ftp://shop.example/file"))
        }
        assertBadRequest(client, resp, "URL должен использовать схему http:// или https://")
    }

    // ── Regression: scheme без // (javascript:, mailto:) не должен fall-through ──
    // (codex P2 — "Reject URI schemes without slashes before prefixing")
    @Test
    fun `create wish rejects javascript scheme without corrupting url`() = testApp { client ->
        val auth = client.register("wishjs@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", url = "javascript:alert(1)"))
        }
        // Без расширенного regex это fall-through'нуло бы в https://javascript:alert(1)
        // и сохранило corrupted/XSS-payload. Теперь reject'ится чисто.
        assertBadRequest(client, resp, "URL должен использовать схему http:// или https://")
    }

    @Test
    fun `create wish rejects invalid status with specific message`() = testApp { client ->
        val auth = client.register("wishstatus@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", status = "FOO"))
        }
        assertBadRequest(client, resp, "Статус некорректный")
    }

    @Test
    fun `create wish rejects negative price with specific message`() = testApp { client ->
        val auth = client.register("wishprice@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val resp = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish", price = -50.0))
        }
        assertBadRequest(client, resp, "Цена некорректная")
    }

    @Test
    fun `update wish status rejects unknown status with specific message`() = testApp { client ->
        val auth = client.register("wishstatuspatch@example.com")
        val list: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        val wish: WishDto = client.post("/api/wishlists/${list.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Wish"))
        }.body()
        val resp = client.patch("/api/wishes/${wish.id}/status") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("status" to "BOGUS"))
        }
        assertBadRequest(client, resp, "Статус некорректный")
    }

    // ── Sharing ─────────────────────────────────────────────────────────

    @Test
    fun `toggle share on wishlist generates token and returns it`() = testApp { client ->
        val auth = client.register("share-on@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Birthday"))
        }.body()

        val resp = client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to true))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val dto: WishlistDto = resp.body()
        assertEquals(true, dto.isShared)
        assertNotNull(dto.shareToken)
        assertTrue(dto.shareToken!!.length == 22) // base64url 16 bytes
    }

    @Test
    fun `toggle share off clears token`() = testApp { client ->
        val auth = client.register("share-off@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        // Включаем
        client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to true))
        }
        // Выключаем
        val resp = client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to false))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val dto: WishlistDto = resp.body()
        assertEquals(false, dto.isShared)
        assertEquals(null, dto.shareToken)
    }

    @Test
    fun `toggle share on wishlist owned by another user returns 404`() = testApp { client ->
        val alice = client.register("share-owner@example.com")
        val bob = client.register("share-intruder@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Alice's list"))
        }.body()

        val resp = client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to true))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `get shared wishlist by token returns detail without auth`() = testApp { client ->
        val auth = client.register("shared-host@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("Shared"))
        }.body()
        // Добавляем wish
        client.post("/api/wishlists/${created.id}/wishes") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishRequest(title = "Gift"))
        }
        // Включаем share
        val shared: WishlistDto = client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to true))
        }.body()
        val token = shared.shareToken!!

        // Публичный доступ — без Authorization header
        val resp = client.get("/api/shared/$token")
        assertEquals(HttpStatusCode.OK, resp.status)
        val detail: WishlistDetailResponse = resp.body()
        assertEquals("Shared", detail.wishlist.title)
        assertEquals(true, detail.wishlist.isShared)
        assertEquals(null, detail.wishlist.shareToken) // не утекает в публичный ответ
        assertEquals(1, detail.wishes.size)
    }

    @Test
    fun `get shared wishlist with invalid token returns 404`() = testApp { client ->
        val resp = client.get("/api/shared/nonexistent-token-12345")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `get shared wishlist after share revoked returns 404`() = testApp { client ->
        val auth = client.register("shared-revoke@example.com")
        val created: WishlistDto = client.post("/api/wishlists") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateWishlistRequest("List"))
        }.body()
        // Включаем
        val shared: WishlistDto = client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to true))
        }.body()
        val token = shared.shareToken!!
        // Выключаем
        client.patch("/api/wishlists/${created.id}/share") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to false))
        }
        // Старая ссылка больше не работает
        val resp = client.get("/api/shared/$token")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // ── Rate limiting ───────────────────────────────────────────────────

    @Test
    fun `login rate limit returns 429 after 5 auth attempts`() = testApp { client ->
        // register считается первым auth-запросом (он внутри rateLimit блока).
        client.register("ratelimit@example.com")
        // Ещё 4 неудачных login — итого 5 auth-запросов (все в норме).
        repeat(4) {
            val resp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("email" to "ratelimit@example.com", "password" to "WRONG"))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
        // 6-й auth-запрос — 429 (rate limit превышен: лимит 5/min).
        val overLimit = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "ratelimit@example.com", "password" to "WRONG"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, overLimit.status)
    }

    // ── Health readiness ────────────────────────────────────────────────

    @Test
    fun `health ready returns 200 when DB available`() = testApp { client ->
        val resp = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("OK", resp.bodyAsText())
    }

    // ── JWT Refresh ─────────────────────────────────────────────────────

    @Test
    fun `refresh returns new access and refresh token`() = testApp { client ->
        val auth = client.register("refresh-test@example.com")
        assertNotNull(auth.refreshToken)

        val resp = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refreshToken" to auth.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val newAuth: AuthResponse = resp.body()
        assertNotNull(newAuth.token)
        assertNotNull(newAuth.refreshToken)
        // Токены могут совпадать в одной миллисекунде (iat), но оба должны быть валидными.
        assertEquals(auth.userId, newAuth.userId)
        assertEquals(auth.email, newAuth.email)
    }

    @Test
    fun `refresh with access token instead of refresh returns 401`() = testApp { client ->
        val auth = client.register("refresh-access@example.com")

        val resp = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refreshToken" to auth.token))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `refresh with invalid token returns 401`() = testApp { client ->
        val resp = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refreshToken" to "invalid-token-string"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ── Legacy access-token (без claim `type`, от предыдущего деплоя) ────

    @Test
    fun `legacy access token without type claim is accepted`() = testApp { client ->
        // Регистрируем пользователя, чтобы получить реальный userId/email.
        val auth = client.register("legacy-token@example.com")

        // Эмулируем access-token от предыдущего деплоя: тот же secret, но БЕЗ claim `type`.
        val legacyToken = JWT.create()
            .withIssuer("wantly")
            .withAudience("wantly-users")
            .withSubject(auth.email)
            .withClaim("userId", auth.userId)
            // намеренно отсутствует withClaim("type", ...)
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60 * 60 * 1000))
            .sign(Algorithm.HMAC256(JwtConfig.secret))

        // Защищённый endpoint должен принять legacy access-token без принудительного re-login.
        val resp = client.get("/api/wishlists") {
            header("Authorization", "Bearer $legacyToken")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
