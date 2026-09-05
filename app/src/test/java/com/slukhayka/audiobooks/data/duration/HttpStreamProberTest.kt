package com.slukhayka.audiobooks.data.duration

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * #516 — the chapter-duration probe must ride the SAME network policy as
 * playback and downloads: the shared OkHttp stack (app DNS / DoH / privacy
 * route) and the per-source header seam ([com.slukhayka.audiobooks.data.source.headersFor]).
 * The old raw-HttpURLConnection prober resolved names through the system
 * resolver and never sent a Referer, so under a broken system DNS (or on
 * Referer-gated CDNs) the pass silently starved the duration column.
 *
 * Verified against a REAL loopback HTTP server (no OkHttp mocks), mirroring
 * the repo convention of [com.slukhayka.audiobooks.data.source.HttpFetcherRangeTest].
 */
class HttpStreamProberTest {

    private lateinit var server: UpstreamStub

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
    fun `a gated track probes only with the owning source headers`() = runTest {
        server.fullBytes = validMpegWindow()

        val prober = HttpStreamProber(extraHeadersProvider = { sourceId, _ ->
            if (sourceId == "audiobookmp3") mapOf("Referer" to "https://audiobook-mp3.com/uk") else emptyMap()
        })
        val result = prober.probe("audiobookmp3", url())

        assertNotNull("a 200 window with a Referer must probe", result)
        assertEquals(windowBytesTotal, result!!.contentLength)
        assertEquals("the prober must send the source's own Referer", "https://audiobook-mp3.com/uk", server.lastReferer)
    }

    @Test
    fun `a track without headers keeps the plain GET`() = runTest {
        server.fullBytes = validMpegWindow()

        val prober = HttpStreamProber(extraHeadersProvider = { _, _ -> emptyMap() })
        val result = prober.probe("soundbooks", url())

        assertNotNull(result)
        assertNull("no Referer may leak onto headerless sources", server.lastReferer)
    }

    @Test
    fun `a gated track without its headers is refused by the server`() = runTest {
        // Proves the stub really gates — so the positive test above means
        // something and cannot pass against a permissive server.
        server.requireReferer = "https://audiobook-mp3.com/uk"
        server.fullBytes = validMpegWindow()

        val prober = HttpStreamProber(extraHeadersProvider = { _, _ -> emptyMap() })
        assertNull(prober.probe("audiobookmp3", url()))
    }

    @Test
    fun `a source refusing the HEAD degrades to null`() = runTest {
        server.statusToServe = 403

        val prober = HttpStreamProber(extraHeadersProvider = { _, _ -> emptyMap() })
        assertNull(prober.probe("audiobookmp3", url()))
    }

    /** The legacy seam stays callable: no sourceId → no extra headers. */
    @Test
    fun `the one-argument probe still works for legacy callers`() = runTest {
        server.fullBytes = validMpegWindow()

        val prober = HttpStreamProber()
        assertNotNull(prober.probe(url()))
    }

    // ------------------------------------------------------------------

    /** A real MPEG window: two CBR MPEG1 Layer III 128 kbps frames (417 B each). */
    private fun validMpegWindow(): ByteArray =
        (byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(417 - 4)) +
            (byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(417 - 4))

    private fun url(): String = "http://127.0.0.1:${server.listeningPort}/audio.mp3"

    /** Minimal controllable upstream; records the headers that hit the wire. */
    private class UpstreamStub : NanoHTTPD("127.0.0.1", 0) {
        var fullBytes: ByteArray = "full".toByteArray()
        var statusToServe: Int = 0
        var requireReferer: String? = null

        var lastReferer: String? = null
            private set

        override fun serve(session: IHTTPSession): Response {
            lastReferer = session.headers["referer"]
            if (statusToServe != 0) {
                return newFixedLengthResponse(
                    Response.Status.lookup(statusToServe) ?: Response.Status.NOT_FOUND,
                    "text/plain", ""
                )
            }
            if (requireReferer != null && session.headers["referer"] != requireReferer) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "")
            }
            // A HEAD response carries NO body — OkHttp (unlike the old
            // HttpURLConnection) fails the request when extra bytes follow.
            if (session.method == Method.HEAD) {
                return newFixedLengthResponse(
                    Response.Status.OK, "audio/mpeg", ByteArrayInputStream(ByteArray(0)), 0L
                ).apply { addHeader("Content-Length", fullBytes.size.toString()) }
            }
            return newFixedLengthResponse(
                Response.Status.OK, "audio/mpeg",
                fullBytes.inputStream(), fullBytes.size.toLong()
            )
        }
    }

    private companion object {
        /** What the stub reports as Content-Length — the prober's contentLength. */
        const val windowBytesTotal = 834L
    }
}
