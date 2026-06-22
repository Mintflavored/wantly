package com.nervs.wantly.data.remote

import com.nervs.wantly.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * API-клиент к серверу Wantly (https://wantlyapp.ru).
 *
 * В гостевом режиме все вызовы игнорируются — данные хранятся локально (Room).
 * После регистрации/входа клиент активируется и работает с сервером.
 */
class WantlyApi(private val tokenProvider: () -> String?) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ── Auth ──────────────────────────────────────────────

    suspend fun register(email: String, password: String, displayName: String?): AuthResponse =
        post("api/auth/register", AuthRequest(email, password, displayName))

    suspend fun login(email: String, password: String): AuthResponse =
        post("api/auth/login", AuthRequest(email, password))

    // ── Wishlists ─────────────────────────────────────────

    suspend fun getWishlists(): List<WishlistDto> = get("api/wishlists")

    suspend fun createWishlist(req: CreateWishlistRequest): WishlistDto =
        post("api/wishlists", req)

    suspend fun deleteWishlist(id: Long) {
        request("api/wishlists/$id", method = "DELETE")
    }

    // ── Wishes ────────────────────────────────────────────

    suspend fun createWish(wishlistId: Long, req: CreateWishRequest): WishDto =
        post("api/wishlists/$wishlistId/wishes", req)

    suspend fun updateWishStatus(wishId: Long, status: String) {
        val req = UpdateStatusRequest(status)
        request("api/wishes/$wishId/status", method = "PATCH", body = req)
    }

    suspend fun deleteWish(wishId: Long) {
        request("api/wishes/$wishId", method = "DELETE")
    }

    // ── Preview ───────────────────────────────────────────

    suspend fun preview(url: String): PreviewResponse =
        post("api/preview", PreviewRequest(url))

    // ── HTTP helpers ──────────────────────────────────────

    private suspend inline fun <reified Req, reified Resp> post(
        path: String,
        body: Req,
    ): Resp = withContext(Dispatchers.IO) {
        val jsonBody = json.encodeToString(serializer(), body).toRequestBody(jsonMedia)
        val resp = doRequest(path, "POST", jsonBody)
        json.decodeFromString(serializer(), resp)
    }

    private suspend inline fun <reified Resp> post(
        path: String,
    ): Resp = withContext(Dispatchers.IO) {
        val resp = doRequest(path, "POST", null)
        json.decodeFromString(serializer(), resp)
    }

    private suspend inline fun <reified Resp> get(
        path: String,
    ): Resp = withContext(Dispatchers.IO) {
        val resp = doRequest(path, "GET", null)
        json.decodeFromString(serializer(), resp)
    }

    private suspend inline fun <reified Req> request(
        path: String,
        method: String,
        body: Req,
    ): String = withContext(Dispatchers.IO) {
        val jsonBody = json.encodeToString(serializer(), body).toRequestBody(jsonMedia)
        doRequest(path, method, jsonBody)
    }

    private suspend fun request(path: String, method: String): String =
        withContext(Dispatchers.IO) { doRequest(path, method, null) }

    private fun doRequest(path: String, method: String, body: okhttp3.RequestBody?): String {
        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .method(method, body)
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.header("Content-Type", "application/json") }

        client.newCall(builder.build()).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, parseError(responseBody))
            }
            return responseBody
        }
    }

    private fun parseError(body: String): String =
        runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrDefault("Ошибка сервера")

    companion object {
        // Продакшен: https://wantlyapp.ru/
        // Эмулятор для локальной разработки: http://10.0.2.2:8080/
        // Trailing slash обязателен: пути ("api/preview") конкатенируются с BASE_URL.
        const val BASE_URL = "https://wantlyapp.ru/"
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
