package com.nervs.wantly.backend.validation

import com.nervs.wantly.backend.dto.CreateWishRequest
import com.nervs.wantly.backend.dto.CreateWishlistRequest
import com.nervs.wantly.backend.dto.LoginRequest
import com.nervs.wantly.backend.dto.PreviewRequest
import com.nervs.wantly.backend.dto.RegisterRequest
import com.nervs.wantly.backend.dto.UpdateWishRequest
import com.nervs.wantly.backend.dto.UpdateWishStatusRequest
import com.nervs.wantly.backend.dto.UpdateWishlistRequest

/**
 * Typed-exception для ошибок валидации. Расширяет [IllegalArgumentException],
 * но StatusPages ловит именно [ValidationException] отдельным handler'ом над
 * библиотечным [IllegalArgumentException] — поэтому сообщение доходит до клиента.
 * Случайные library exceptions (BCrypt, Exposed, kotlinx-serialization) НЕ
 * являются [ValidationException] и по-прежнему дают generic «Некорректный запрос»
 * — никакие внутренние детали не утекают.
 */
class ValidationException(message: String) : IllegalArgumentException(message)

/** Бросает [ValidationException], если predicate ложен. */
private fun requireField(condition: Boolean, message: String) {
    if (!condition) throw ValidationException(message)
}

// ── Константы из БД / app ──────────────────────────────────────────────────

/**
 * Допустимые значения status в wishes. Зеркало app-side enum
 * `WishStatus` (WANTED/RESERVED/PURCHASED). Backend не зависит от Android-модуля,
 * поэтому список захардкожен — при изменении enum'а обновлять здесь тоже.
 */
private val WISH_STATUSES = setOf("WANTED", "RESERVED", "PURCHASED")

/**
 * Допустимые индексы coverColor в wishlist. Соответствует app-side палитре
 * `WishlistAccents` (6 цветов: Rose, Purple, Amber, Teal, Blue, Coral).
 */
private val COVER_COLOR_RANGE = 0..5

/** ISO 4217: трёхбуквенный код валюты. */
private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")

/** Простой email-check: что-то@что-то.домен. Не full RFC-822, но хватает для UI. */
private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

private const val MAX_EMAIL_LENGTH = 255
private const val MIN_PASSWORD_LENGTH = 6
private const val MAX_PASSWORD_LENGTH = 72 // bcrypt truncate limit
private const val MAX_DISPLAY_NAME_LENGTH = 100

private const val MAX_WISHLIST_TITLE = 200
private const val MAX_WISHLIST_DESCRIPTION = 5_000

private const val MAX_WISH_TITLE = 500
private const val MAX_WISH_DESCRIPTION = 10_000
private const val MAX_WISH_STORE = 200
private const val MAX_URL_LENGTH = 2_048
private const val MAX_PRICE = 1_000_000_000.0

private const val MAX_PREVIEW_URL = 2_048

// ── Auth ───────────────────────────────────────────────────────────────────

fun RegisterRequest.validate() {
    requireField(email.isNotBlank(), "Email не указан")
    requireField(EMAIL_REGEX.containsMatchIn(email), "Email некорректный")
    requireField(email.length <= MAX_EMAIL_LENGTH, "Email слишком длинный")
    requireField(password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH,
        "Пароль должен быть от $MIN_PASSWORD_LENGTH до $MAX_PASSWORD_LENGTH символов")
    displayName?.let {
        requireField(it.length <= MAX_DISPLAY_NAME_LENGTH, "Имя слишком длинное")
    }
}

/**
 * Login не раскрывает детали (что именно не так) — это намеренно, чтобы не
 * помогать перебору. Любая ошибка → generic 400. Успешный/неуспешный login всё
 * равно различается на уровне email-существования через 401.
 */
fun LoginRequest.validate() {
    requireField(email.isNotBlank() && password.isNotBlank(), "Некорректный запрос")
}

// ── Wishlist ───────────────────────────────────────────────────────────────

fun CreateWishlistRequest.validate() = validateWishlistFields(title, description, coverColor)

fun UpdateWishlistRequest.validate() = validateWishlistFields(title, description, coverColor)

private fun validateWishlistFields(title: String, description: String?, coverColor: Int) {
    requireField(title.isNotBlank(), "Название списка обязательно")
    requireField(title.length <= MAX_WISHLIST_TITLE, "Название списка слишком длинное")
    description?.let {
        requireField(it.length <= MAX_WISHLIST_DESCRIPTION, "Описание списка слишком длинное")
    }
    requireField(coverColor in COVER_COLOR_RANGE, "Некорректный цвет списка")
}

// ── Wish ───────────────────────────────────────────────────────────────────

fun CreateWishRequest.validate() = validateWishFields(
    title = title,
    description = description,
    url = url,
    imageUrl = imageUrl,
    price = price,
    currency = currency,
    storeName = storeName,
    status = status,
)

fun UpdateWishRequest.validate() = validateWishFields(
    title = title,
    description = description,
    url = url,
    imageUrl = imageUrl,
    price = price,
    currency = currency,
    storeName = storeName,
    status = status,
)

private fun validateWishFields(
    title: String,
    description: String?,
    url: String?,
    imageUrl: String?,
    price: Double?,
    currency: String,
    storeName: String?,
    status: String?,
) {
    requireField(title.isNotBlank(), "Название желания обязательно")
    requireField(title.length <= MAX_WISH_TITLE, "Название желания слишком длинное")
    description?.let {
        requireField(it.length <= MAX_WISH_DESCRIPTION, "Заметка слишком длинная")
    }
    validateUrl(url, "URL")
    validateUrl(imageUrl, "Ссылка на фото")
    validatePrice(price)
    requireField(currency.matches(CURRENCY_REGEX), "Валюта некорректная (ожидается код ISO 4217, например RUB)")
    storeName?.let {
        requireField(it.length <= MAX_WISH_STORE, "Название магазина слишком длинное")
    }
    status?.let {
        requireField(it in WISH_STATUSES, "Статус некорректный")
    }
}

private fun validateUrl(value: String?, fieldName: String) {
    value?.let {
        requireField(it.length <= MAX_URL_LENGTH, "$fieldName слишком длинный")
        requireField(it.startsWith("http://") || it.startsWith("https://"),
            "$fieldName должен начинаться с http:// или https://")
    }
}

/**
 * Нормализация URL: если схема отсутствует, добавляет `https://`. Соответствует
 * клиентскому паттерну — Android-форма сохраняет raw `example.com` (preview
 * нормализует отдельно), а серверная валидация требует http(s)://. Без этой
 * нормализации легитимные schemeless URL'ы reject'ятся с 400 → SyncManager
 * бесконечно ретраит. Применять В ДОКУМЕНТЕ НУЖНО до validate().
 *
 * Не валидирует сам URL — только добавляет схему. Полную проверку делает
 * validateUrl + PreviewService.normalizeUrl (https-only, egress constraints).
 */
fun normalizeWishUrl(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return "https://$trimmed"
}

/**
 * Нормализация валюты: trim + uppercase. App принимает `usd`/`eur` в lowercase
 * (onCurrencyChange stores raw), server validation требует `^[A-Z]{3}$`.
 * Без uppercase-normalization легитимные значения reject'ятся → бесконечный
 * retry в SyncManager. Применять до validate() и при записи в БД.
 */
fun normalizeCurrency(raw: String): String = raw.trim().uppercase()

private fun validatePrice(price: Double?) {
    price?.let {
        requireField(!it.isNaN() && it.isFinite() && it >= 0 && it <= MAX_PRICE,
            "Цена некорректная")
    }
}

fun UpdateWishStatusRequest.validate() {
    requireField(status in WISH_STATUSES, "Статус некорректный")
}

// ── Preview ────────────────────────────────────────────────────────────────

fun PreviewRequest.validate() {
    requireField(url.isNotBlank(), "URL не указан")
    requireField(url.length <= MAX_PREVIEW_URL, "URL слишком длинный")
    // Scheme/format-validation остаётся в PreviewService.normalizeUrl — там сложная
    // логика (https-only, egress-proxy constraints). Здесь только длина/пустота.
}
