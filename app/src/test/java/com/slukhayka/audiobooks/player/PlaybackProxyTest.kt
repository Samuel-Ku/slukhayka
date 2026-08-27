package com.slukhayka.audiobooks.player

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spec #361 («Проксі відтворення») — end-to-end JVM tests of the local HTTP
 * proxy against REAL sockets on loopback. Every test spins up its own
 * [PlaybackProxy] on an ephemeral port with an in-memory fake upstream (the
 * seam production wiring will adapt HttpFetcher onto) and stops it in the
 * tear-down, so no test can leak a listener into another.
 *
 * The fake upstream mirrors a real origin: it parses the forwarded Range
 * header itself, answers 206 with an honest Content-Range, refuses
 * unsatisfiable ranges with null, and records every range header it was
 * handed so the tests can assert verbatim forwarding.
 *
 * [PlaybackProxy.wifiBindAddress] stays deliberately untested here: a JVM
 * cannot control which network interfaces exist, so its "no wifi" outcome is
 * environment-dependent noise — the function stays thin and degrades to null.
 */
class PlaybackProxyTest {

    /** The upstream bytes every fake serves — indexes 0..9 are handy for ranges. */
    private val payload = "0123456789".toByteArray(Charsets.US_ASCII)

    /**
     * A stand-in origin server behind [PlaybackProxy.Upstream]: serves the
     * payload whole or sliced, exactly like an honest HTTP file server would,
     * or returns null when it has nothing honest to serve ([failAll]).
     */
    private class FakeUpstream(
        private val bytes: ByteArray,
        private val contentType: String? = "audio/mpeg",
        private val failAll: Boolean = false
    ) : PlaybackProxy.Upstream {

        /** The last Range header the proxy forwarded, for verbatim assertions. */
        var lastRangeHeaderSeen: String? = null
            private set

        override fun open(subPath: String?, rangeHeader: String?): PlaybackProxy.UpstreamResponse? {
            lastRangeHeaderSeen = rangeHeader
            if (failAll) return null
            val slice = sliceFor(rangeHeader)
                ?: return null  // unsatisfiable — nothing honest to serve
            return PlaybackProxy.UpstreamResponse(
                status = if (rangeHeader == null) 200 else 206,
                body = ByteArrayInputStream(slice.second),
                contentLength = slice.second.size.toLong(),
                contentRange = slice.first?.let {
                    "bytes $it-${it + slice.second.size - 1}/${bytes.size}"
                },
                contentType = contentType
            )
        }

        /** Parses `bytes=a-b` / `bytes=a-` like an origin server; null when unsatisfiable. */
        private fun sliceFor(rangeHeader: String?): Pair<Int?, ByteArray>? {
            if (rangeHeader == null) return null to bytes
            val match = RANGE_REGEX.matchEntire(rangeHeader.trim()) ?: return null to bytes
            val start = match.groupValues[1].toInt()
            if (start >= bytes.size) return null
            val end = minOf(match.groupValues[2].toIntOrNull() ?: (bytes.size - 1), bytes.size - 1)
            return start to bytes.copyOfRange(start, end + 1)
        }
    }

    private lateinit var fakeUpstream: FakeUpstream
    private lateinit var proxy: PlaybackProxy
    private lateinit var started: PlaybackProxy.Started

    @Before
    fun setUp() {
        restart(failAll = false, contentType = "audio/mpeg")
    }

    @After
    fun tearDown() {
        proxy.stop()
    }

    @Test
    fun `full GET serves upstream bytes verbatim with 200`() {
        val response = httpGet(url("book/chapter-01.mp3"))

        assertEquals(200, response.code)
        assertArrayEquals(payload, response.body)
        assertEquals("10", response.header("Content-Length"))
        assertEquals("audio/mpeg", response.header("Content-Type"))
    }

    @Test
    fun `a Range of bytes=5- is forwarded verbatim and served as 206 with the tail`() {
        val response = httpGet(url("book/chapter-01.mp3"), range = "bytes=5-")

        assertEquals(206, response.code)
        assertArrayEquals("56789".toByteArray(Charsets.US_ASCII), response.body)
        assertEquals("bytes 5-9/10", response.header("Content-Range"))
        assertEquals("5", response.header("Content-Length"))
        assertEquals("bytes", response.header("Accept-Ranges"))
        assertEquals(
            "the proxy must forward the Range header untouched",
            "bytes=5-",
            fakeUpstream.lastRangeHeaderSeen
        )
    }

    @Test
    fun `a bounded Range of bytes=2-7 serves exactly bytes 2 through 7 inclusive`() {
        val response = httpGet(url("book/chapter-01.mp3"), range = "bytes=2-7")

        assertEquals(206, response.code)
        assertArrayEquals("234567".toByteArray(Charsets.US_ASCII), response.body)
        assertEquals("6", response.header("Content-Length"))
        assertEquals("bytes 2-7/10", response.header("Content-Range"))
    }

    @Test
    fun `a wrong token never reaches upstream and answers 403`() {
        val response = httpGet("http://127.0.0.1:${started.port}/wrong-token/book/chapter-01.mp3")

        assertEquals(403, response.code)
        assertEquals(0, response.body.size)
        assertNull("no byte may leave through a wrong token", fakeUpstream.lastRangeHeaderSeen)
    }

    @Test
    fun `a missing token path answers 403 too`() {
        val root = httpGet("http://127.0.0.1:${started.port}/")
        assertEquals(403, root.code)

        val barePrefix = httpGet("http://127.0.0.1:${started.port}${started.pathPrefix}")
        assertEquals(403, barePrefix.code)
    }

    @Test
    fun `an upstream failure without a Range answers 502 with an empty body`() {
        restart(failAll = true)

        val response = httpGet(url("book/chapter-01.mp3"))

        assertEquals(502, response.code)
        assertEquals(0, response.body.size)
    }

    @Test
    fun `an unsatisfiable range answers 416`() {
        val response = httpGet(url("book/chapter-01.mp3"), range = "bytes=50-")

        assertEquals(416, response.code)
    }

    @Test
    fun `after stop connections are refused and isRunning flips`() {
        assertTrue(proxy.isRunning)
        proxy.stop()
        assertFalse(proxy.isRunning)

        try {
            httpGet(url("book/chapter-01.mp3"))
            throw AssertionError("connection after stop() must be refused")
        } catch (expected: IOException) {
            // refused — the listener socket is gone
        }
    }

    @Test
    fun `a missing content type omits the header instead of fabricating one`() {
        restart(contentType = null)

        val response = httpGet(url("book/chapter-01.mp3"))

        assertEquals(200, response.code)
        assertNull(response.header("Content-Type"))
    }

    /** Stops the previous instance and starts a fresh one for this test only. */
    private fun restart(failAll: Boolean = false, contentType: String? = "audio/mpeg") {
        if (::proxy.isInitialized) proxy.stop()
        fakeUpstream = FakeUpstream(payload, contentType = contentType, failAll = failAll)
        proxy = PlaybackProxy(fakeUpstream)
        started = requireNotNull(proxy.start()) { "loopback proxy must start" }
    }

    private fun url(path: String): String =
        "http://127.0.0.1:${started.port}${started.pathPrefix}/$path"

    private fun httpGet(url: String, range: String? = null): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            if (range != null) connection.setRequestProperty("Range", range)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Connection", "close")
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            HttpResponse(code, stream?.readBytes() ?: ByteArray(0)) { name ->
                connection.getHeaderField(name)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Status + body + named-header lookup, so error responses read like normal ones. */
    private class HttpResponse(
        val code: Int,
        val body: ByteArray,
        private val headerOf: (String) -> String?
    ) {
        fun header(name: String): String? = headerOf(name)
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private val RANGE_REGEX = Regex("""^bytes=(\d+)-(\d*)$""")
    }
}
