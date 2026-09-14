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
    private class Origin(val response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok") : AutoCloseable {
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
                                        it.getOutputStream().write(response.toByteArray())
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
    fun `both playback and downloads reject notice redirects before following them`() {
        try {
            TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
            for (status in listOf(301, 302, 303, 307, 308)) {
                Origin("HTTP/1.1 $status Redirect\r\nLocation: https://reasd.org/notice/4read-notice.mp3?x=1\r\nContent-Length: 0\r\n\r\n").use { origin ->
                    for (factory in listOf(TransportClients.calls, TransportClients.playbackCalls)) {
                        try {
                            factory.newCall(Request.Builder().url(origin.url).build()).execute().use {
                                fail("The 52-second notice must never reach the player or downloader")
                            }
                        } catch (error: IOException) {
                            assertTrue("Expected the notice verdict, got $error", AudioNoticePolicy.causedByNotice(error))
                        }
                    }
                    assertEquals(2, origin.requests.get())
                }
            }
        } finally { TransportPrivacy.install(PrivacyPrefs()) }
    }

    @Test
    fun `a stored notice URL is blocked before DNS or network`() {
        try {
            TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
            for (factory in listOf(TransportClients.calls, TransportClients.playbackCalls)) {
                try {
                    factory.newCall(Request.Builder().url("https://reasd.org/notice/4read-notice.mp3").build())
                        .execute().use { fail("Stored notice must be blocked too") }
                } catch (error: IOException) {
                    assertTrue(AudioNoticePolicy.causedByNotice(error))
                }
            }
        } finally { TransportPrivacy.install(PrivacyPrefs()) }
    }

    @Test
    fun `audio refusal covers both domains while their metadata and covers stay accessible`() {
        Origin().use { origin ->
            try {
                TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
                val metadata = TransportClients.okHttp.newBuilder()
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                    }).build()
                for (host in listOf("reasd.org", "4read.org", "cdn.reasd.org")) {
                    val base = origin.url.replace("127.0.0.1", host)
                    metadata.newCall(Request.Builder().url("$base/cover.jpg").build()).execute().use {
                        assertEquals(200, it.code)
                    }
                    val before = origin.requests.get()
                    for (factory in listOf(TransportClients.audioCalls, TransportClients.playbackCalls)) {
                        try {
                            factory.newCall(Request.Builder().url("$base/extensionless-stream").build())
                                .execute().use { fail("Refused audio must not reach DNS or origin") }
                        } catch (error: IOException) {
                            assertTrue(AudioNoticePolicy.causedByNotice(error))
                        }
                    }
                    assertEquals(before, origin.requests.get())
                }
            } finally { TransportPrivacy.install(PrivacyPrefs()) }
        }
    }

    @Test
    fun `audio redirects to refused hosts are blocked even without an mp3 extension`() {
        try {
            TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
            Origin("HTTP/1.1 302 Redirect\r\nLocation: https://reasd.org/stream?id=1\r\nContent-Length: 0\r\n\r\n").use { origin ->
                for (factory in listOf(TransportClients.audioCalls, TransportClients.playbackCalls)) {
                    try {
                        factory.newCall(Request.Builder().url(origin.url).build()).execute().use { fail("Forbidden hop") }
                    } catch (error: IOException) {
                        assertTrue(AudioNoticePolicy.causedByNotice(error))
                    }
                }
                assertEquals(2, origin.requests.get())
            }
        } finally { TransportPrivacy.install(PrivacyPrefs()) }
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
