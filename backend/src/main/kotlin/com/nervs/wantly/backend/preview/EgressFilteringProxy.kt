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

        // Подключаемся к первому публичному IP напрямую, не к hostname —
        // это закрывает DNS rebinding (новый lookup в этот момент уже не
        // состоится, IP зафиксирован в сокете).
        val upstream = try {
            Socket().apply { connect(InetSocketAddress(addrs.first(), port), 10_000) }
        } catch (e: Exception) {
            reject(client, "Upstream connect failed: ${e.message}")
            return
        }

        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            flush()
        }

        bridge(client, upstream)
    }

    /** Bidirectional byte tunnel between client and upstream until EOF. */
    private fun bridge(client: Socket, upstream: Socket) {
        val clientToUpstream = thread(isDaemon = true) {
            try {
                client.getInputStream().copyTo(upstream.getOutputStream())
                upstream.getOutputStream().flush()
            } finally {
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
    }

    private fun reject(client: Socket, reason: String) {
        logger.warn("Proxy reject: $reason")
        client.getOutputStream().apply {
            write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
    }

    /** Остановить слушателя и закрыть все активные client-соединения. */
    fun stop() {
        running = false
        runCatching { server.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private fun isPrivate(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || // RFC1918
            addr.hostAddress == "169.254.169.254" ||
            isIpv6Ula(addr)

    /** RFC4193 unique-local fc00::/7. */
    private fun isIpv6Ula(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 16) return false
        return bytes[0] == 0xFC.toByte() || bytes[0] == 0xFD.toByte()
    }
}
