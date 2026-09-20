package com.slukhayka.audiobooks.data.privacy

import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
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

    /** NewPipe's own extraction identity — deliberately not a mobile token. */
    private companion object {
        const val NEWPIPE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    @Test
    fun `audio requests carry browser fetch metadata and preserve ranges while page requests stay unchanged`() {
        TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
        try {
            Origin().use { origin ->
                for (calls in listOf(TransportClients.playbackCalls, TransportClients.audioCalls, TransportClients.calls)) {
                    calls.newCall(Request.Builder().url(origin.url).header("Range", "bytes=17-31").build())
                        .execute().close()
                }
                val headers = origin.requestHeaders.toList()
                assertEquals(3, headers.size)
                for (media in headers.take(2)) {
                    assertEquals("no-cors", media["sec-fetch-mode"])
                    assertEquals("audio", media["sec-fetch-dest"])
                    assertEquals("bytes=17-31", media["range"])
                }
                assertNull(headers.last()["sec-fetch-mode"])
                assertNull(headers.last()["sec-fetch-dest"])
            }
        } finally { TransportPrivacy.install(PrivacyPrefs()) }
    }

    @Test
    fun `HTTPS upgrade is restricted to the known Archive endpoint`() {
        val legacy = "http://archive.org/download/a%20book/part.mp3?key=a%2Fb".toHttpUrl()
        assertEquals("https://archive.org/download/a%20book/part.mp3?key=a%2Fb", KnownSourceHttps.upgrade(legacy).toString())
        for (unchanged in listOf(
            "http://archive.org:8080/chapter.mp3", "http://archive.org.example/chapter.mp3",
            "http://example.org/chapter.mp3", "https://archive.org/chapter.mp3"
        )) {
            val url = unchanged.toHttpUrl()
            assertEquals(url, KnownSourceHttps.upgrade(url))
        }
    }

    @Test
    fun `legacy Archive audio uses HTTPS before connection and keeps its Range`() {
        TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
        try {
            for (base in listOf(TransportClients.okHttp, TransportClients.playbackHttp)) {
                val client = base.newBuilder().addInterceptor { chain ->
                    assertEquals("https", chain.request().url.scheme)
                    assertEquals("bytes=0-0", chain.request().header("Range"))
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(206).message("Partial").body(byteArrayOf(0).toResponseBody()).build()
                }.build()
                client.newCall(Request.Builder().url("http://archive.org/download/book/chapter.mp3")
                    .header("Range", "bytes=0-0").build()).execute().close()
            }
        } finally { TransportPrivacy.install(PrivacyPrefs()) }
    }

    /**
     * #772 — the regression guard for the YouTube extraction path.
     *
     * The shared client stamps the app-wide mobile WebView User-Agent onto
     * every request. YouTube answers a `Mobile` token by redirecting to
     * `m.youtube.com`, whose markup NewPipeExtractor cannot parse, so
     * extraction died with `Could not get ytInitialData`. The extraction seam
     * therefore asks for its OWN identity; the client must then send the
     * User-Agent its protocol gave it (here: NewPipe's own) and never the
     * app-wide one.
     *
     * This runs entirely against the local origin — no YouTube, no network —
     * because the defect is OUR header substitution, provable in isolation.
     */
    @Test
    fun `a request carrying its own identity keeps its User-Agent while page requests keep the browser identity`() {
        TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
        try {
            Origin().use { origin ->
                // The extraction seam's own header, exactly as NewPipe sets it.
                TransportClients.calls.newCall(
                    BrowserIdentity.ownIdentityRequest(
                        Request.Builder().url(origin.url).header("User-Agent", NEWPIPE_USER_AGENT).build()
                    )
                ).execute().close()
                // An ordinary page request keeps the app-wide browser identity.
                TransportClients.calls.newCall(Request.Builder().url(origin.url).build()).execute().close()

                val headers = origin.requestHeaders.toList()
                assertEquals(2, headers.size)
                assertEquals(
                    "the extraction seam's own identity must survive the shared client",
                    NEWPIPE_USER_AGENT, headers.first()["user-agent"]
                )
                assertEquals(
                    "every other source keeps the app-wide browser identity",
                    BrowserIdentity.currentUserAgent(), headers.last()["user-agent"]
                )
                assertNotEquals(
                    "the mobile app identity is what triggers YouTube's m.youtube.com redirect",
                    NEWPIPE_USER_AGENT, headers.last()["user-agent"]
                )
            }
        } finally { TransportPrivacy.install(PrivacyPrefs()) }
    }

    private class Origin(val response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok") : AutoCloseable {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val requestHeaders = java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>>()
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
                                        val headers = mutableMapOf<String, String>()
                                        while (true) {
                                            val line = reader.readLine()?.takeIf { it.isNotEmpty() } ?: break
                                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                                        }
                                        requestHeaders.add(headers)
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
    fun `4read audio stays refused while its metadata and covers stay accessible`() {
        Origin().use { origin ->
            try {
                TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
                val metadata = TransportClients.okHttp.newBuilder()
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                    }).build()
                for (host in listOf("4read.org", "cdn.4read.org")) {
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
            Origin("HTTP/1.1 302 Redirect\r\nLocation: https://4read.org/stream?id=1\r\nContent-Length: 0\r\n\r\n").use { origin ->
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
    fun `reasd recordings reach playback and downloads but their notice redirects never reach the target`() {
        try {
            TransportPrivacy.install(PrivacyPrefs(dohEnabled = false))
            for (response in listOf(
                "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 2\r\n\r\nok",
                "HTTP/1.1 302 Redirect\r\nLocation: /notice/4read-notice.mp3\r\nContent-Length: 0\r\n\r\n"
            )) {
                Origin(response).use { origin ->
                    for (base in listOf(TransportClients.okHttp, TransportClients.playbackHttp)) {
                        val client = base.newBuilder().dns(object : okhttp3.Dns {
                            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                        }).build()
                        val request = AudioNoticePolicy.audioRequest(Request.Builder()
                            .url(origin.url.replace("127.0.0.1", "reasd.org"))
                            .header("Range", "bytes=0-1").build())
                        if (response.startsWith("HTTP/1.1 200")) {
                            client.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
                        } else {
                            try {
                                client.newCall(request).execute().use { fail("Notice redirect was followed") }
                            } catch (error: IOException) {
                                assertTrue(AudioNoticePolicy.causedByNotice(error))
                            }
                        }
                    }
                    assertEquals("Only the book request, never the notice request", 2, origin.requests.get())
                    assertTrue(origin.requestHeaders.all { it["range"] == "bytes=0-1" })
                }
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
