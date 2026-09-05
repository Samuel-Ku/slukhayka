package com.slukhayka.audiobooks.data.privacy

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Loopback-only forward proxy for a DIRECT WebView session. CONNECT carries
 * opaque TLS bytes: WebView still owns certificates, origins and cookies.
 * Hostnames resolve through the app's DNS; explicit Tor/proxy routes never
 * use this door. Closing the screen closes its listener and every tunnel.
 */
internal class BrowserDnsProxy(
    private val resolve: (String) -> List<InetAddress> = TransportDns::lookup,
    private val directAllowed: () -> Boolean = { TransportPrivacy.current() == NetworkRoute.Direct }
) : Closeable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS, SynchronousQueue()) {
        Thread(it, "browser-dns-tunnel").apply { isDaemon = true }
    }
    private val listener = ServerSocket(0, 32, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val proxyRule: String = "http://127.0.0.1:${listener.localPort}"

    init {
        Thread({
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                sockets += client
                try { workers.execute { serve(client) } } catch (_: Exception) { closeSocket(client) }
            }
        }, "browser-dns-accept").apply { isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        var upstream: Socket? = null
        var tunnelStarted = false
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val header = readHeader(input)
            val lines = header.removeSuffix("\r\n\r\n").split("\r\n")
            val request = lines.first().split(' ')
            require(request.size == 3 && request[2].startsWith("HTTP/1."))
            val connect = request[0] == "CONNECT"
            val uri = URI(if (connect) "https://${request[1]}" else request[1])
            require(uri.host != null && uri.userInfo == null)
            require(if (connect) uri.rawPath.isNullOrEmpty() else uri.scheme == "http")
            val port = if (uri.port == -1) { if (connect) 443 else 80 } else uri.port
            require(port in 1..65535)
            check(directAllowed()) { "Direct route no longer selected" }
            val addresses = resolve(uri.host)
            for (address in addresses) {
                check(directAllowed() && !listener.isClosed)
                val candidate = Socket()
                sockets += candidate
                try {
                    candidate.connect(InetSocketAddress(address, port), 8_000)
                    upstream = candidate
                    break
                } catch (_: IOException) { closeSocket(candidate) }
            }
            val remote = upstream ?: throw IOException("No reachable address")
            check(directAllowed() && !listener.isClosed)
            remote.soTimeout = 30_000
            client.soTimeout = 30_000
            if (connect) {
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                // One plain-HTTP request per connection keeps absolute URLs
                // from a subsequent origin from being sent to this host.
                val path = uri.rawPath.orEmpty().ifEmpty { "/" } +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val forwarded = lines.drop(1).filterNot {
                    it.startsWith("Proxy-", true) || it.startsWith("Connection:", true)
                }
                remote.getOutputStream().write(
                    ("${request[0]} $path ${request[2]}\r\n" +
                        forwarded.joinToString("\r\n") + "\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1)
                )
            }
            tunnelStarted = true
            workers.execute {
                try { input.copyTo(remote.getOutputStream()) } catch (_: IOException) { }
                finally { runCatching { remote.shutdownOutput() } }
            }
            remote.getInputStream().copyTo(client.getOutputStream())
        } catch (_: Exception) {
            if (!tunnelStarted) runCatching {
                client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
            }
        } finally {
            upstream?.let(::closeSocket)
            closeSocket(client)
        }
    }

    private fun readHeader(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < 32_768) {
            val next = input.read()
            if (next == -1) throw IOException("Incomplete proxy request")
            bytes.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) return bytes.toString("ISO-8859-1")
        }
        throw IOException("Proxy request header too large")
    }

    private fun closeSocket(socket: Socket) {
        sockets -= socket
        runCatching { socket.close() }
    }

    override fun close() {
        listener.close()
        sockets.toList().forEach(::closeSocket)
        workers.shutdownNow()
    }
}
