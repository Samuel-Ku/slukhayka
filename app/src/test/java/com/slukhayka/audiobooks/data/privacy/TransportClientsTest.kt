package com.slukhayka.audiobooks.data.privacy

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TransportClientsTest {
    private class Origin : AutoCloseable {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val workers = Executors.newCachedThreadPool()
        val url get() = "http://127.0.0.1:${server.localPort}/audio"
        init {
            workers.submit {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        sockets.add(socket)
                        workers.submit {
                            socket.use {
                                try {
                                    val reader = it.getInputStream().bufferedReader()
                                    while (reader.readLine() != null) {
                                        while (!reader.readLine().isNullOrEmpty()) { }
                                        requests.incrementAndGet()
                                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                                        it.getOutputStream().flush()
                                    }
                                } catch (_: IOException) { }
                            }
                        }
                    }
                } catch (_: IOException) { }
            }
        }
        override fun close() {
            server.close()
            sockets.forEach { it.close() }
            workers.shutdownNow()
        }
    }

    @Test
    fun `warm direct connections cannot bypass a newly selected proxy or Tor`() {
        Origin().use { origin ->
            try {
                for (mode in listOf(RouteMode.CUSTOM_PROXY, RouteMode.MAX_PRIVACY)) {
                    TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
                    val factory = TransportClients.playbackCalls
                    val oldClient = TransportClients.okHttp
                    val request = Request.Builder().url(origin.url).build()
                    oldClient.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
                    val count = origin.requests.get()
                    TransportPrivacy.install(PrivacyPrefs(routeMode = mode, proxyAddress = "127.0.0.1:1", dohEnabled = false))
                    assertNotSame(oldClient.connectionPool, TransportClients.okHttp.connectionPool)
                    for (call in listOf(factory.newCall(request), oldClient.newCall(request))) {
                        try {
                            call.execute().use { fail("Unavailable private route must not reach origin") }
                        } catch (_: IOException) { }
                    }
                    assertEquals(count, origin.requests.get())
                    TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
                    factory.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
                }
            } finally { TransportPrivacy.install(PrivacyPrefs()) }
        }
    }

    @Test
    fun `route change cancels a synchronous call whose body is still open`() {
        Origin().use { origin ->
            try {
                TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
                val call = TransportClients.playbackCalls.newCall(Request.Builder().url(origin.url).build())
                call.execute().use {
                    assertFalse(call.isCanceled())
                    TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.MAX_PRIVACY, dohEnabled = false))
                    assertTrue(call.isCanceled())
                }
            } finally { TransportPrivacy.install(PrivacyPrefs()) }
        }
    }
}
