package com.slukhayka.audiobooks.data.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL

/**
 * ADR-0024 (#362) — the ranged GET seam the Playback Proxy consumes. The
 * observable contract, verified against a REAL loopback HTTP server (no
 * mocks of OkHttp): a Range header travels verbatim as an extra header, a
 * 206 upstream comes back with its status + Content-Range preserved, a plain
 * 200 still serves the whole body through the same method, and a 404 degrades
 * to null — never a throw. This is the transport-level half of receiver seek;
 * the proxy's own Range semantics are JVM-tested in [com.slukhayka.audiobooks.player.PlaybackProxyTest].
 */
class HttpFetcherRangeTest {

    private lateinit var server: UpstreamStub
    private val fetcher = HttpFetcher()

    @Before
    fun start() {
        server = UpstreamStub()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun stop() {
        server.stop()
    }

    @Test
    fun `a 206 upstream keeps status content-range and sliced bytes`() {
        server.partialBytes = ByteArray(8) { (it + 5).toByte() } // "bytes=5-12"
        server.partialTotal = 100L

        val response = fetcher.getRangeStream(
            url(),
            mapOf("Range" to "bytes=5-12", "Referer" to "https://sluhay.example/")
        )

        assertNotNull(response)
        assertEquals(206, response!!.status)
        assertEquals("bytes 5-12/100", response.contentRange)
        assertEquals(8, response.stream.readBytes().size)
        assertTrue(server.lastRangeHeader == "bytes=5-12")
        assertTrue(server.lastReferer == "https://sluhay.example/")
    }

    @Test
    fun `a 200 upstream serves the whole body with status 200`() {
        server.fullBytes = "whole-audio".toByteArray()

        val response = fetcher.getRangeStream(url())

        assertNotNull(response)
        assertEquals(200, response!!.status)
        assertNull(response.contentRange)
        assertEquals("whole-audio", response.stream.readBytes().decodeToString())
    }

    @Test
    fun `a missing file degrades to null`() {
        server.statusToServe = 404

        assertNull(fetcher.getRangeStream(url()))
    }

    private fun url(): String = "http://127.0.0.1:${server.listeningPort}/audio.mp3"

    /** Minimal controllable upstream: records what actually arrived on wire. */
    private class UpstreamStub : NanoHTTPD("127.0.0.1", 0) {
        var fullBytes: ByteArray = "full".toByteArray()
        var partialBytes: ByteArray = ByteArray(0)
        var partialTotal: Long = -1L
        var statusToServe: Int = 0

        var lastRangeHeader: String? = null
        var lastReferer: String? = null

        override fun serve(session: IHTTPSession): Response {
            lastRangeHeader = session.headers["range"]
            lastReferer = session.headers["referer"]
            return when {
                statusToServe != 0 -> newFixedLengthResponse(
                    Response.Status.lookup(statusToServe) ?: Response.Status.NOT_FOUND,
                    "text/plain", ""
                )
                session.headers["range"] != null -> newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, "audio/mpeg",
                    partialBytes.inputStream(), partialBytes.size.toLong()
                ).apply {
                    addHeader("Content-Range", "bytes 5-${5 + partialBytes.size - 1}/$partialTotal")
                }
                else -> newFixedLengthResponse(
                    Response.Status.OK, "audio/mpeg",
                    fullBytes.inputStream(), fullBytes.size.toLong()
                )
            }
        }
    }
}
