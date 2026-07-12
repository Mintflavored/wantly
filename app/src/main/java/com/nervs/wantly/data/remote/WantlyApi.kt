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
class WantlyApi(
    private val tokenProvider: () -> String?,
    private val refreshTokenProvider: () -> String? = { null },
    private val onTokensRefreshed: ((String, String) -> Unit)? = null,
) {

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

    suspend fun refreshToken(refreshToken: String): AuthResponse =
        post("api/auth/refresh", RefreshRequest(refreshToken))

    // ── Wishlists ─────────────────────────────────────────

    suspend fun getWishlists(): List<WishlistDto> = get("api/wishlists")

    suspend fun createWishlist(req: CreateWishlistRequest): WishlistDto =
        post("api/wishlists", req)

    suspend fun updateWishlist(id: Long, req: UpdateWishlistRequest): WishlistDto =
        patch("api/wishlists/$id", req)

    suspend fun deleteWishlist(id: Long) {
        request("api/wishlists/$id", method = "DELETE")
    }

    suspend fun getWishlistDetail(id: Long): WishlistDetailResponse =
        get("api/wishlists/$id")

    /** Set isShared на сервере (не blind toggle — передаёт desired state).
     *  Возвращает обновлённый WishlistDto с shareToken. */
    suspend fun setShare(id: Long, enabled: Boolean): WishlistDto =
        patch("api/wishlists/$id/share", SetShareRequest(enabled))

    /** Публичный доступ к shared wishlist — без JWT (работает в guest mode). */
    suspend fun getSharedWishlist(token: String): WishlistDetailResponse =
        get("api/shared/$token")

    // ── Wishes ────────────────────────────────────────────

    suspend fun createWish(wishlistId: Long, req: CreateWishRequest): WishDto =
        post("api/wishlists/$wishlistId/wishes", req)

    suspend fun updateWish(wishId: Long, req: UpdateWishRequest): WishDto =
        patch("api/wishes/$wishId", req)

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

    private suspend inline fun <reified Req, reified Resp> patch(
        path: String,
        body: Req,
    ): Resp = withContext(Dispatchers.IO) {
        val jsonBody = json.encodeToString(serializer(), body).toRequestBody(jsonMedia)
        val resp = doRequest(path, "PATCH", jsonBody)
        json.decodeFromString(serializer(), resp)
    }

    private suspend inline fun <reified Resp> post(
        path: String,
    ): Resp = withContext(Dispatchers.IO) {
        val resp = doRequest(path, "POST", null)
        json.decodeFromString(serializer(), resp)
    }

    /** PATCH без meaningful body (для toggle-endpoint'ов). OkHttp требует body
     *  для PATCH — отправляем пустой JSON {} (сервер игнорирует тело на toggle). */
    private suspend inline fun <reified Resp> patch(
        path: String,
    ): Resp = withContext(Dispatchers.IO) {
        val resp = doRequest(path, "PATCH", "{}".toRequestBody(jsonMedia))
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

    private suspend fun doRequest(path: String, method: String, body: okhttp3.RequestBody?): String {
        val response = executeWithAuth(path, method, body)
        // 401: пробуем refresh токена, затем retry один раз.
        if (response.code == 401 && path != "api/auth/refresh") {
            val rt = refreshTokenProvider()
            if (rt != null) {
                runCatching {
                    val refreshResp = refreshTokenBlocking(rt)
                    onTokensRefreshed?.invoke(refreshResp.token, refreshResp.refreshToken)
                }.onSuccess {
                    // Retry с новым access token.
                    val retryResp = executeWithAuth(path, method, body)
                    if (!retryResp.isSuccessful) {
                        throw ApiException(retryResp.code, parseError(retryResp.errorBody))
                    }
                    return retryResp.body
                }
            }
        }
        if (!response.isSuccessful) {
            throw ApiException(response.code, parseError(response.errorBody))
        }
        return response.body
    }

    /** Execute HTTP request, return (code, body, errorBody). */
    private fun executeWithAuth(path: String, method: String, body: okhttp3.RequestBody?): HttpResult {
        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .method(method, body)
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.header("Content-Type", "application/json") }

        client.newCall(builder.build()).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            return HttpResult(resp.code, responseBody)
        }
    }

    private data class HttpResult(val code: Int, val body: String) {
        val isSuccessful get() = code in 200..299
        val errorBody get() = body
    }

    /** Synchronous refresh-token call (inside withContext(Dispatchers.IO)). */
    private fun refreshTokenBlocking(refreshToken: String): AuthResponse {
        val req = RefreshRequest(refreshToken)
        val jsonBody = json.encodeToString(serializer(), req).toRequestBody(jsonMedia)
        val builder = Request.Builder()
            .url("$BASE_URL${"api/auth/refresh"}")
            .method("POST", jsonBody)
            .header("Content-Type", "application/json")
        client.newCall(builder.build()).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, parseError(respBody))
            }
            return json.decodeFromString(serializer(), respBody)
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
