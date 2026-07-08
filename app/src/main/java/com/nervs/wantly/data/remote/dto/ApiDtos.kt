package com.nervs.wantly.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val token: String,
    @SerialName("userId") val userId: Long,
    val email: String,
    val displayName: String? = null,
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class WishlistDto(
    val id: Long,
    val title: String,
    val description: String? = null,
    val isShared: Boolean = false,
    val coverColor: Int = 0,
    val wishCount: Int = 0,
    /** Присутствует только для isShared=true списков, возвращаемых владельцу.
     *  В публичном GET /api/shared/{token} — null (получатель не видит токен). */
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

@Serializable
data class SetShareRequest(
    val enabled: Boolean,
)

@Serializable
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
data class UpdateStatusRequest(
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

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class WishlistDetailResponse(
    val wishlist: WishlistDto,
    val wishes: List<WishDto>,
)
