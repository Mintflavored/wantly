package com.nervs.wantly.backend.preview

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.nervs.wantly.backend.db.PreviewCacheTable
import com.nervs.wantly.backend.dto.PreviewResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("PreviewService")

/**
 * Single-worker dispatcher для Playwright. Playwright Java не thread-safe —
 * [Browser.newContext] / [Page.navigate] на одном Browser нельзя вызывать
 * из разных threads без внешней синхронизации (см. Playwright Java docs,
 * "Running tests in parallel"). Limited(1) гарантирует что одновременно
 * работает только один preview, даже под concurrency. Не держит Netty
 * event-loop thread.
 */
private val playwrightDispatcher = Dispatchers.IO.limitedParallelism(1)

// ── In-memory TTL кэш для preview результатов ──────────────────────────
// Без Caffeine/Guava — ConcurrentHashMap + timestamp. TTL 1 час.
// Кэшируем только success=true (ошибки парсинга — временные, не кэшируем).
// Size-guard: при превышении MAX_SIZE — clear() (проще чем LRU для ~1MB).

private data class CacheEntry(val response: PreviewResponse, val expiresAt: Long)

private val json = Json { ignoreUnknownKeys = true }

private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 час
private const val CACHE_MAX_SIZE = 2000

private val previewCache = ConcurrentHashMap<String, CacheEntry>()

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

    /**
     * Playwright-fetch с blocking navigate + Thread.sleep — оборачиваем в
     * [playwrightDispatcher] (single-worker), чтобы не занимать Netty
     * event-loop thread и при этом держать Playwright API single-threaded
     * (он не thread-safe — см. комментарий у dispatcher declaration).
     *
     * Сначала проверяет in-memory TTL кэш — cache hit обходит Chromium
     * полностью (мгновенный ответ для повторных ссылок).
     */
    suspend fun fetch(rawUrl: String): PreviewResponse {
        val normalized = normalizeUrl(rawUrl)

        // L1: in-memory cache (мгновенно, без DB/Chromium).
        if (normalized != null) {
            val l1 = previewCache[normalized]
            if (l1 != null && l1.expiresAt > System.currentTimeMillis()) {
                logger.debug("L1 cache hit: $normalized")
                return l1.response
            }
        }

        // L2: PostgreSQL cache (переживает restart, ~1ms round-trip).
        if (normalized != null) {
            val l2 = l2Lookup(normalized)
            if (l2 != null) {
                // Populate L1 from L2, сохраняя оригинальный expires_at из L2.
                // Важно: нельзя ставить свежий CACHE_TTL_MS — иначе строка, которая
                // истекает через минуту, проживёт в L1 ещё час после restart.
                previewCache[normalized] = CacheEntry(l2.response, l2.expiresAt)
                logger.debug("L2 cache hit: $normalized")
                return l2.response
            }
        }

        // Cache miss — через single-worker dispatcher (Playwright blocking).
        val result = withContext(playwrightDispatcher) { fetchBlocking(rawUrl) }

        // Кэшируем только успешные результаты (ошибки — временные).
        if (result.success && normalized != null) {
            if (previewCache.size >= CACHE_MAX_SIZE) {
                previewCache.clear()
            }
            val expiresAt = System.currentTimeMillis() + CACHE_TTL_MS
            previewCache[normalized] = CacheEntry(result, expiresAt)
            // L2 write (fire-and-forget — не блокируем ответ).
            l2Store(normalized, result, expiresAt)
        }
        return result
    }

    /** L2 lookup: SELECT из PostgreSQL. Lazy cleanup expired entries.
     *  Возвращает [L2Entry] с response и оригинальным expires_at (epoch ms). */
    private data class L2Entry(val response: PreviewResponse, val expiresAt: Long)

    private suspend fun l2Lookup(url: String): L2Entry? = runCatching {
        com.nervs.wantly.backend.db.DatabaseFactory.dbQuery {
            // Lazy cleanup: удаляем expired записи.
            val nowStr = Clock.System.now().toString()
            TransactionManager.current().exec("DELETE FROM preview_cache WHERE expires_at <= '$nowStr'")
            // Lookup.
            PreviewCacheTable.selectAll()
                .where { PreviewCacheTable.url eq url }
                .singleOrNull()
                ?.let { row ->
                    val response = json.decodeFromString(
                        PreviewResponse.serializer(),
                        row[PreviewCacheTable.responseJson],
                    )
                    val expiresAt = row[PreviewCacheTable.expiresAt].toEpochMilliseconds()
                    L2Entry(response, expiresAt)
                }
        }
    }.getOrNull()

    /** L2 store: UPSERT в PostgreSQL. */
    private suspend fun l2Store(url: String, response: PreviewResponse, expiresAtMs: Long) {
        runCatching {
            com.nervs.wantly.backend.db.DatabaseFactory.dbQuery {
                val jsonStr = json.encodeToString(PreviewResponse.serializer(), response)
                val expires = kotlinx.datetime.Instant.fromEpochMilliseconds(expiresAtMs)
                val existing = PreviewCacheTable.selectAll()
                    .where { PreviewCacheTable.url eq url }
                    .singleOrNull()
                if (existing != null) {
                    PreviewCacheTable.update({ PreviewCacheTable.url eq url }) {
                        it[PreviewCacheTable.responseJson] = jsonStr
                        it[PreviewCacheTable.expiresAt] = expires
                    }
                } else {
                    PreviewCacheTable.insert {
                        it[PreviewCacheTable.url] = url
                        it[PreviewCacheTable.responseJson] = jsonStr
                        it[PreviewCacheTable.expiresAt] = expires
                    }
                }
            }
        }
    }

    private fun fetchBlocking(rawUrl: String): PreviewResponse {
        val url = normalizeUrl(rawUrl)
            ?: return PreviewResponse(url = rawUrl, success = false, error = "Invalid URL")

        var page: Page? = null
        var context: com.microsoft.playwright.BrowserContext? = null
        var proxy: EgressFilteringProxy? = null
        return try {
            // Egress proxy: все запросы Chromium (initial, redirects, subresources)
            // идут через локальный CONNECT-proxy, который резолвит host и подключается
            // к конкретному IP напрямую (atomic resolver+connect) — это закрывает
            // DNS rebinding TOCTOU и SSRF на приватные диапазоны.
            proxy = EgressFilteringProxy()
            context = ensureBrowser().newContext(
                com.microsoft.playwright.Browser.NewContextOptions()
                    .setProxy(
                        com.microsoft.playwright.options.Proxy("http://127.0.0.1:${proxy.port}"),
                    ),
            )
            page = context.newPage()
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
            context?.close()
            proxy?.stop()
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
        // EgressFilteringProxy поддерживает только CONNECT (HTTPS). Если
        // пропустить http://, Chromium пришлёт absolute-form request и
        // получит 405 от proxy → preview тихо упадёт. Поэтому явно
        // отклоняем plain http:// — клиентский fallback (Jsoup) разберётся.
        val url = runCatching { URL(withScheme) }.getOrNull() ?: return null
        if (url.protocol != "https") return null
        return url.toExternalForm()
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
