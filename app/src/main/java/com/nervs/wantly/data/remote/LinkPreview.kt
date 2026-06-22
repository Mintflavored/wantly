package com.nervs.wantly.data.remote

import androidx.annotation.StringRes
import com.nervs.wantly.R

/**
 * Тип ошибки распознавания ссылки. Data-слой не имеет доступа к Context,
 * поэтому здесь только код, а локализованный текст маппится в UI через
 * [LinkPreviewError.messageRes] + stringResource().
 */
enum class LinkPreviewError(@StringRes val messageRes: Int) {
    INVALID_URL(R.string.error_invalid_url),
    LOAD_FAILED(R.string.error_load_failed),
}

/**
 * Результат распознавания ссылки. [success]=false означает, что автоматически
 * вытащить данные не вышло — тогда пользователь заполняет поля вручную.
 *
 * В Фазе 1 парсинг идёт на клиенте (OpenGraph/microdata/JSON-LD) — это работает
 * для многих сайтов, но не для JS-heavy SPA (Amazon, Shein, часть маркетплейсов).
 * В Фазе 2 этот же класс будет наполняться сервером (Ktor + Playwright).
 */
data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val storeName: String? = null,
    val success: Boolean = false,
    val error: LinkPreviewError? = null,
)
