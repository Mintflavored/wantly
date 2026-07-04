package com.nervs.wantly.backend.preview

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Минимальный HTTP CONNECT proxy, защищающий Playwright/Chromium от SSRF
 * и DNS rebinding.
 *
 * Принцип: Chromium настроен на этот proxy (Browser.newContext(setProxy)).
 * Каждый запрос (initial, redirect, subresource) проходит через CONNECT.
 * Proxy резолвит host через JVM и **подключается к конкретному IP напрямую,
 * не к hostname** — это атомарная операция, между резолвом и connect нет окна
 * для DNS rebinding. Если IP private/loopback/ULA — соединение сбрасывается.
 *
 * Поддерживается только CONNECT (HTTPS). Plain HTTP отклоняется — все
 * Preview-источники в реальном мире HTTPS, plain HTTP не нужен.
 *
 * TLS не терминируется — после CONNECT прокси пересылает только шифрованные
 * байты, сертификат валидируется Chromium'ом как обычно.
 *
 * Один экземпляр proxy == один preview-запрос. После page.close() вызывающим
 * должен вызвать [stop], чтобы освободить поток слушателя и все активные
 * client-сокеты.
 */
class EgressFilteringProxy {

    private val logger = LoggerFactory.getLogger("EgressFilteringProxy")

    private val server = ServerSocket().apply {
        bind(InetSocketAddress("127.0.0.1", 0))
    }

    /** Локальный порт, который нужно скормить в Playwright setProxy. */
    val port: Int = server.localPort

    private val clients = mutableListOf<Socket>()
    private val upstreams = mutableListOf<Socket>()
    @Volatile private var running = true

    init {
        thread(name = "egress-proxy-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                synchronized(clients) { clients.add(client) }
                thread(name = "egress-proxy-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        logger.debug("Proxy connection error: ${e.message}")
                    } finally {
                        runCatching { client.close() }
                        synchronized(clients) { clients.remove(client) }
                    }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val firstLine = reader.readLine() ?: return
        // Пропускаем headers до пустой строки.
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
        }

        val parts = firstLine.split(" ")
        if (parts.size < 2 || parts[0] != "CONNECT") {
            client.getOutputStream().apply {
                write(
                    "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                flush()
            }
            return
        }

        // target = host:port
        val target = parts[1]
        val colonIdx = target.lastIndexOf(':')
        if (colonIdx < 0) return
        val host = target.substring(0, colonIdx)
        val port = target.substring(colonIdx + 1).toIntOrNull() ?: return

        // Резолвим через JVM — атомарно с последующим connect.
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            reject(client, "DNS resolution failed for $host")
            return
        }

        // Любой private IP среди ответов → reject (fail-safe).
        if (addrs.any { isPrivate(it) }) {
            reject(client, "Target resolves to private address: ${addrs.joinToString { it.hostAddress }}")
            return
        }

        // Подключаемся к первому доступному публичному IP напрямую, не к hostname —
        // это закрывает DNS rebinding (новый lookup в этот момент уже не
        // состоится, IP зафиксирован в сокете). Если первый public address
        // не отвечает (IPv6 без маршрута, дохлый CDN node) — пробуем следующий.
        val upstream = try {
            connectToFirstReachablePublic(addrs, port)
        } catch (e: Exception) {
            reject(client, "Upstream connect failed: ${e.message}")
            return
        }
        synchronized(upstreams) { upstreams.add(upstream) }

        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            flush()
        }

        bridge(client, upstream)
    }

    /**
     * Перебираем все resolved addresses, пока один не подключится.
     * Считаем, что isPrivate уже отфильтровал плохие — тут пробуем
     * только публичные, чтобы preview не падал на единственном дохлом A-record.
     */
    private fun connectToFirstReachablePublic(addrs: Array<InetAddress>, port: Int): Socket {
        var lastError: Exception? = null
        for (addr in addrs) {
            // Защита — на всякий случай не подключаемся к private даже если
            // проверка выше что-то пропустила.
            if (isPrivate(addr)) continue
            try {
                return Socket().apply { connect(InetSocketAddress(addr, port), 10_000) }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No usable public addresses")
    }

    /** Bidirectional byte tunnel between client and upstream until EOF. */
    private fun bridge(client: Socket, upstream: Socket) {
        val clientToUpstream = thread(isDaemon = true) {
            try {
                client.getInputStream().copyTo(upstream.getOutputStream())
                upstream.getOutputStream().flush()
            } finally {
                // Закрываем output в обе стороны — будит copyTo на обеих сторонах.
                runCatching { upstream.shutdownOutput() }
            }
        }
        val upstreamToClient = thread(isDaemon = true) {
            try {
                upstream.getInputStream().copyTo(client.getOutputStream())
                client.getOutputStream().flush()
            } finally {
                runCatching { client.shutdownOutput() }
            }
        }
        clientToUpstream.join()
        upstreamToClient.join()
        runCatching { client.close() }
        runCatching { upstream.close() }
        synchronized(upstreams) { upstreams.remove(upstream) }
    }

    private fun reject(client: Socket, reason: String) {
        logger.warn("Proxy reject: $reason")
        client.getOutputStream().apply {
            write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
    }

    /** Остановить слушателя и закрыть все активные client и upstream сокеты. */
    fun stop() {
        running = false
        runCatching { server.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        synchronized(upstreams) {
            upstreams.forEach { runCatching { it.close() } }
            upstreams.clear()
        }
    }

    private fun isPrivate(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || // RFC1918
            addr.hostAddress == "169.254.169.254" ||
            isIpv6Ula(addr) ||
            isCarrierGradeNat(addr) ||
            isSpecialUseIpv4(addr)

    /** RFC4193 unique-local fc00::/7. */
    private fun isIpv6Ula(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 16) return false
        return bytes[0] == 0xFC.toByte() || bytes[0] == 0xFD.toByte()
    }

    /** RFC6598 Carrier-Grade NAT 100.64.0.0/10. isSiteLocalAddress их НЕ покрывает. */
    private fun isCarrierGradeNat(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        return bytes[0] == 100.toByte() && bytes[1] in 64..127
    }

    /**
     * Прочие IPv4 special-use ranges, которые не является глобально-маршрутизируемыми
     * и не должны быть доступны через /api/preview:
     * - 0.0.0.0/8 — this-network
     * - 192.0.0.0/24, 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 — IETF / TEST-NET
     * - 198.18.0.0/15 — benchmarking
     * - 240.0.0.0/4 — reserved (включая 255.255.255.255 broadcast)
     *
     * Источник: RFC 6890.
     */
    private fun isSpecialUseIpv4(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        val o0 = bytes[0].toInt() and 0xFF
        val o1 = bytes[1].toInt() and 0xFF
        val o2 = bytes[2].toInt() and 0xFF
        return when {
            o0 == 0 -> true                                  // 0.0.0.0/8
            o0 == 192 && o1 == 0 && o2 == 0 -> true          // 192.0.0.0/24
            o0 == 192 && o1 == 0 && o2 == 2 -> true          // 192.0.2.0/24 (TEST-NET-1)
            o0 == 192 && o1 == 88 && o2 == 99 -> true        // 192.88.99.0/24 (6to4 anycast)
            o0 == 198 && (o1 == 18 || o1 == 19) -> true      // 198.18.0.0/15 (benchmark)
            o0 == 198 && o1 == 51 && o2 == 100 -> true       // 198.51.100.0/24 (TEST-NET-2)
            o0 == 203 && o1 == 0 && o2 == 113 -> true        // 203.0.113.0/24 (TEST-NET-3)
            o0 >= 240 -> true                                // 240.0.0.0/4 (reserved + broadcast)
            else -> false
        }
    }
}
