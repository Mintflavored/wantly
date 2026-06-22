package com.nervs.wantly.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URL

/**
 * Клиентский распознаватель ссылок (Фаза 1): best-effort через OpenGraph,
 * microdata и JSON-LD. Не использует JS-рендеринг, поэтому для SPA-сайтов
 * (Amazon, Shein, часть маркетплейсов) данные будут неполными — тогда
 * пользователь дополнит поля вручную. Надёжный парсинг приедет в Фазе 2
 * (сервер Ktor + Playwright с парсерами под конкретные магазины).
 */
class LinkPreviewService {

    suspend fun fetch(rawUrl: String): LinkPreview = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawUrl)
            ?: return@withContext LinkPreview(
                url = rawUrl,
                success = false,
                error = LinkPreviewError.INVALID_URL,
            )

        runCatching {
            val doc = Jsoup.connect(normalized)
                .userAgent(DESKTOP_UA)
                .referrer("https://www.google.com/")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .timeout(15_000)
                .maxBodySize(5 * 1024 * 1024) // 5 MB — защита от OOM на огромных страницах
                .followRedirects(true)
                .ignoreContentType(true)
                .get()

            val og = { prop: String ->
                doc.selectFirst("meta[property=$prop]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            }

            val title = og("og:title") ?: doc.title().takeIf { it.isNotBlank() }
            val description =
                og("og:description")
                    ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            val imageUrl = og("og:image")?.let { resolveUrl(normalized, it) }
            val siteName = og("og:site_name")
            val (price, currency) = extractPrice(doc, og)
            val store = siteName ?: hostOf(normalized)

            LinkPreview(
                url = normalized,
                title = title,
                description = description,
                imageUrl = imageUrl,
                price = price,
                currency = currency,
                storeName = store,
                success = title != null || imageUrl != null,
            )
        }.getOrElse {
            LinkPreview(
                url = normalized,
                storeName = hostOf(normalized),
                success = false,
                error = LinkPreviewError.LOAD_FAILED,
            )
        }
    }

    private fun extractPrice(
        doc: org.jsoup.nodes.Document,
        og: (String) -> String?,
    ): Pair<Double?, String?> {
        // 1. OpenGraph product
        og("product:price:amount")?.let { amount ->
            val price = parsePrice(amount)
            val currency = og("product:price:currency") ?: og("og:price:currency")
            if (price != null) return price to currency
        }

        // 2. Microdata (schema.org)
        listOf("[itemprop=price]", "[itemprop=lowPrice]", "[itemprop=highPrice]").forEach { sel ->
            doc.selectFirst(sel)?.let { el ->
                val raw = el.attr("content").ifBlank { el.text() }
                parsePrice(raw)?.let { return it to doc.selectFirst("[itemprop=priceCurrency]")?.attr("content") }
            }
        }

        // 3. JSON-LD (грубый поиск по тексту)
        doc.select("script[type=application/ld+json]").eachText().forEach { json ->
            val price = Regex(""""price"\s*:\s*"?([0-9]+[.,]?[0-9]*)"?""").find(json)
                ?.groupValues?.get(1)?.let { parsePrice(it) }
            val currency = Regex(""""priceCurrency"\s*:\s*"([A-Z]{3})"""").find(json)?.groupValues?.get(1)
            if (price != null) return price to currency
        }

        return null to null
    }

    /**
     * Надёжный парсинг цены для разных региональных форматов.
     *
     * Эвристика разделителей:
     * - Если есть и «.» и «,» → последний по позиции — десятичный, остальные убираем.
     *   Пример: «1,299.99» → 1299.99, «1.299,99 €» → 1299.99.
     * - Если только один тип разделителя и после него ≤ 2 цифр → десятичный.
     *   Пример: «1299.99» → 1299.99, «1299,99» → 1299.99.
     * - Если один разделитель и после него 3+ цифр → разделитель тысяч (убираем).
     *   Пример: «1,299» → 1299.0, «1.299» → 1299.0.
     * - Символы валюты, пробелы и прочий мусор игнорируются.
     */
    private fun parsePrice(raw: String): Double? {
        val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }
        if (cleaned.isEmpty()) return null

        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        val lastSep = maxOf(lastDot, lastComma)

        if (lastSep == -1) return cleaned.toDoubleOrNull()

        val hasBoth = lastDot != -1 && lastComma != -1
        val after = cleaned.substring(lastSep + 1)
        val isDecimalSeparator = hasBoth || after.length <= 2

        return if (isDecimalSeparator) {
            val intPart = cleaned.substring(0, lastSep).filter { it.isDigit() }
            val fracPart = after.filter { it.isDigit() }
            if (fracPart.isEmpty()) intPart.toDoubleOrNull()
            else "$intPart.$fracPart".toDoubleOrNull()
        } else {
            cleaned.filter { it.isDigit() }.toDoubleOrNull()
        }
    }

    private fun normalizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return runCatching { URL(withScheme).toExternalForm() }.getOrNull()
    }

    private fun resolveUrl(base: String, src: String): String? = runCatching {
        val b = URL(base)
        when {
            src.startsWith("//") -> URL("${b.protocol}:$src").toExternalForm()
            src.startsWith("http://") || src.startsWith("https://") -> src
            else -> URL(b, src).toExternalForm()
        }
    }.getOrDefault(src)

    private fun hostOf(url: String): String? =
        runCatching { URL(url).host.removePrefix("www.") }.getOrNull()

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}
