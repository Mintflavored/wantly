package com.nervs.wantly.data

/**
 * Лимиты длин полей — зеркало серверных caps из
 * `backend/validation/Validators.kt`. Без них UI принимает произвольную длину,
 * сервер rejects с 400, а SyncManager не различает validation-400 от transient
 * → бесконечный retry + заблокированный logout. Обрезаем ввод на app-side.
 *
 * При изменении здесь — обновить и backend тоже (и наоборот).
 */
object FieldLimits {
    const val EMAIL_MAX = 255
    const val PASSWORD_MIN = 6
    const val PASSWORD_MAX = 72 // bcrypt truncate limit
    const val DISPLAY_NAME_MAX = 100

    const val WISHLIST_TITLE_MAX = 200
    const val WISHLIST_DESCRIPTION_MAX = 5_000

    const val WISH_TITLE_MAX = 500
    const val WISH_DESCRIPTION_MAX = 10_000
    const val WISH_STORE_MAX = 200
    const val URL_MAX = 2_048

    /** Верхний лимит цены — зеркало серверного MAX_PRICE. NaN/negative/over-limit
     *  reject'ятся сервером с 400 → без client-side clamp wish завис бы в retry. */
    const val PRICE_MAX = 1_000_000_000.0

    /** Обрезает value до [max] символов. Используется в onValueChange колбэках. */
    fun clamp(value: String, max: Int): String = if (value.length > max) value.take(max) else value
}
