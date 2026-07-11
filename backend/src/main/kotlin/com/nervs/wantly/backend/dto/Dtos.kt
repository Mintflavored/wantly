package com.nervs.wantly.backend.dto

import kotlinx.serialization.Serializable

// ── Auth ──────────────────────────────────────────────

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class AuthResponse(
    val token: String,
    val userId: Long,
    val email: String,
    val displayName: String?,
)

// ── Wishlist ──────────────────────────────────────────

@Serializable
data class WishlistDto(
    val id: Long,
    val title: String,
    val description: String?,
    val isShared: Boolean,
    val coverColor: Int,
    val wishCount: Int = 0,
    /** Присутствует только если isShared=true. Владелец использует его для построения
     *  share-link, получатель — для доступа через GET /api/shared/{token}. */
    val shareToken: String? = null,
)

@Serializable
data class CreateWishlistRequest(
    val title: String,
    val description: String? = null,
    val coverColor: Int = 0,
)

@Serializable
data class UpdateWishlistRequest(
    val title: String,
    val description: String? = null,
    val coverColor: Int = 0,
)

// ── Wish ──────────────────────────────────────────────

@Serializable
data class WishDto(
    val id: Long,
    val wishlistId: Long,
    val title: String,
    val description: String?,
    val url: String?,
    val imageUrl: String?,
    val price: Double?,
    val currency: String,
    val storeName: String?,
    val status: String,
)

@Serializable
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

@Serializable
data class UpdateWishStatusRequest(
    val status: String,
)

@Serializable
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

@Serializable
data class WishStatusResponse(val status: String)

@Serializable
data class SetShareRequest(
    val enabled: Boolean,
)

// ── Link preview ──────────────────────────────────────

@Serializable
data class PreviewRequest(
    val url: String,
)

@Serializable
data class PreviewResponse(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val storeName: String? = null,
    val success: Boolean = false,
    val error: String? = null,
)

// ── Common ────────────────────────────────────────────

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class WishlistDetailResponse(
    val wishlist: WishlistDto,
    val wishes: List<WishDto>,
)
