package com.nervs.wantly.backend.preview

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.nervs.wantly.backend.dto.PreviewResponse
import org.slf4j.LoggerFactory
import java.net.URL

private val logger = LoggerFactory.getLogger("PreviewService")

/**
 * Серверный парсинг ссылок через Playwright + Chromium.
 *
 * В отличие от клиентского парсинга (Jsoup), Chromium выполняет JavaScript —
 * поэтому корректно работает с SPA-сайтами (Amazon, Shein, Ozon, Wildberries).
 *
 * Управление памятью: один экземпляр браузера держится тёплым (lazy),
 * страницы создаются и закрываются на каждый запрос. Это компромисс между
 * скоростью (~3 сек запуск браузера vs мгновенный warm-start) и RAM (~200 МБ idle).
 */
object PreviewService {

    @Volatile
    private var playwright: Playwright? = null

    @Volatile
    private var browser: Browser? = null

    private fun ensureBrowser(): Browser {
        browser?.let { if (it.isConnected) return it }
        synchronized(this) {
            browser?.let { if (it.isConnected) return it }
            logger.info("Запуск Chromium для PreviewService...")
            playwright = Playwright.create()
            browser = playwright!!.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(listOf(
                        "--no-sandbox",
                        "--disable-gpu",
                        "--disable-dev-shm-usage", // важно для контейнеров/малой RAM
                        "--single-process",
                    )),
            )
            logger.info("Chromium запущен")
            return browser!!
        }
    }

    fun fetch(rawUrl: String): PreviewResponse {
        val url = normalizeUrl(rawUrl)
            ?: return PreviewResponse(url = rawUrl, success = false, error = "Invalid URL")

        var page: Page? = null
        return try {
            page = ensureBrowser().newPage()
            page.setExtraHTTPHeaders(mapOf("Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8"))
            page.navigate(url, Page.NavigateOptions().setTimeout(20_000.0).setWaitUntil(
                com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED,
            ))

            // Ждём чуть-чуть, чтобы SPA успели отрендерить meta-теги
            Thread.sleep(1500)

            val title = metaContent(page, "og:title")
                ?: page.title()?.takeIf { it.isNotBlank() }
            val description = metaContent(page, "og:description")
                ?: metaContent(page, "description")
            val imageUrl = metaContent(page, "og:image")?.let { resolveUrl(url, it) }
            val siteName = metaContent(page, "og:site_name")
            val store = siteName ?: hostOf(url)

            // Цена: пробуем несколько стратегий
            val (price, currency) = extractPrice(page)

            PreviewResponse(
                url = url,
                title = title,
                description = description,
                imageUrl = imageUrl,
                price = price,
                currency = currency,
                storeName = store,
                success = title != null || imageUrl != null,
            )
        } catch (e: Exception) {
            logger.warn("Парсинг не удался: $url", e)
            PreviewResponse(url = url, storeName = hostOf(url), success = false, error = e.message)
        } finally {
            page?.close()
        }
    }

    /** OpenGraph product:price → microdata → JSON-LD. */
    private fun extractPrice(page: Page): Pair<Double?, String?> {
        // 1. OpenGraph product
        val ogPrice = metaContent(page, "product:price:amount")
        if (ogPrice != null) {
            val p = parsePrice(ogPrice)
            val c = metaContent(page, "product:price:currency") ?: metaContent(page, "og:price:currency")
            if (p != null) return p to c
        }

        // 2. JSON-LD
        val jsonLd = page.querySelectorAll("script[type='application/ld+json']")
        for (i in 0 until jsonLd.size) {
            val text = jsonLd[i].innerText() ?: continue
            val price = Regex(""""price"\s*:\s*"?([0-9]+[.,]?[0-9]*)"?""").find(text)
                ?.groupValues?.get(1)?.let { parsePrice(it) }
            val currency = Regex(""""priceCurrency"\s*:\s*"([A-Z]{3})"""").find(text)?.groupValues?.get(1)
            if (price != null) return price to currency
        }

        // 3. Microdata
        val microPrice = page.querySelector("[itemprop=price], [itemprop=lowPrice]")
        if (microPrice != null) {
            val raw = microPrice.getAttribute("content") ?: microPrice.innerText()
            val p = parsePrice(raw)
            if (p != null) {
                val c = page.querySelector("[itemprop=priceCurrency]")?.getAttribute("content")
                return p to c
            }
        }

        return null to null
    }

    private fun metaContent(page: Page, key: String): String? {
        // og:title, og:image и т.д. — property; description — name
        val byProperty = page.querySelector("meta[property='$key']")
        if (byProperty != null) return byProperty.getAttribute("content")?.trim()?.ifBlank { null }
        val byName = page.querySelector("meta[name='$key']")
        return byName?.getAttribute("content")?.trim()?.ifBlank { null }
    }

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
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
        val url = runCatching { URL(withScheme) }.getOrNull() ?: return null
        val host = url.host
        // Сначала reject нестандартные numeric IP forms (hex/octal/decimal),
        // которые Chromium каноникализирует, а Java InetAddress — не везде.
        if (looksLikeNonStandardIpLiteral(host)) return null
        if (isPrivateOrLoopback(host)) return null
        return url.toExternalForm()
    }

    /**
     * True если [host] похож на numeric IP literal в нестандартной форме,
     * которую Chromium каноникализирует (hex/octal/decimal single-integer),
     * а java.net.InetAddress парсит inconsistent по JDK. Rejectим на входе,
     * чтобы не зависеть от того, совпадёт ли Java-каноникализация с браузерной.
     *
     * Standard dotted-quad (127.0.0.1) и IPv6 ([::1]) НЕ reject-ятся здесь —
     * их корректно проверяет [isPrivateOrLoopback].
     */
    private fun looksLikeNonStandardIpLiteral(host: String): Boolean {
        // Standard dotted-quad — корректно проверится через InetAddress.
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return false
        // IPv6 в [] — корректно проверится.
        if (host.startsWith("[") && host.endsWith("]")) return false
        // Decimal single integer (2130706433 = 127.0.0.1).
        if (host.matches(Regex("""^\d{5,}$"""))) return true
        // Hex literal (0x7f000001).
        if (host.startsWith("0x") || host.startsWith("0X")) return true
        // Octal dotted (0251.0376.0251.0376) — сегмент с ведущим 0 и length > 1.
        if (host.contains(".") && host.split(".").any { it.matches(Regex("""^0\d+$""")) }) return true
        return false
    }

    /**
     * Защита от SSRF: запрещаем запросы на loopback / private / link-local
     * адреса. Без этого аутентифицированный пользователь мог бы заставить
     * сервер сходить на http://localhost:xxxx, http://169.254.169.254/
     * (cloud metadata), или внутренние RFC1918 диапазоны.
     *
     * Возвращает true при [UnknownHostException] — fail-safe лучше, чем
     * пропустить suspicious host, который Java не смогла резолвить.
     */
    private fun isPrivateOrLoopback(host: String): Boolean = try {
        // getAllByName возвращает все A/AAAA records. Любой private — reject.
        java.net.InetAddress.getAllByName(host).any { addr ->
            addr.isLoopbackAddress ||
                addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || // RFC1918: 10/8, 172.16/12, 192.168/16
                addr.hostAddress == "169.254.169.254"
        }
    } catch (e: java.net.UnknownHostException) {
        // Не смогли зарезолвить — fail-safe, отклоняем. Better safe.
        true
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
}
