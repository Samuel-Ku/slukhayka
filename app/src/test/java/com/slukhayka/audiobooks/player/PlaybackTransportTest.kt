package com.slukhayka.audiobooks.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.slukhayka.audiobooks.data.privacy.BrowserIdentity
import com.slukhayka.audiobooks.data.privacy.PlaybackRedirects
import com.slukhayka.audiobooks.data.privacy.TransportClients
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #516 — the PLAYBACK data source rides the SAME network policy as downloads
 * and page fetches: one OkHttp engine (app DNS / DoH / privacy route / one
 * browser identity), per-source headers through the ordinary seam, and the
 * SEC-018 redirect rule preserved — same-protocol redirects are followed,
 * a cleartext downgrade (https→http) is refused. The old
 * `DefaultHttpDataSource` opened its own sockets with the system resolver —
 * under a broken system DNS playback failed even though the app could
 * resolve names itself.
 *
 * Built on the REAL production playback engine
 * ([TransportClients.playbackOkHttp]) against a REAL loopback origin (no
 * OkHttp mocks), mirroring [com.slukhayka.audiobooks.player.PlaybackProxyTest].
 * Robolectric is on because Media3's `DataSpec` needs `android.net.Uri`;
 * 127.0.0.1 itself needs no name resolution, so the loopback tests never
 * depend on the host resolver.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackTransportTest {

    private lateinit var server: TransportStub
    private lateinit var factory: OkHttpDataSource.Factory

    @Before
    fun start() {
        server = TransportStub()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        factory = OkHttpDataSource.Factory(TransportClients.playbackOkHttp)
    }

    @After
    fun stop() {
        server.stop()
    }

    @Test
    fun `per-source headers and the browser identity travel on the wire`() {
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url()))
            .setHttpRequestHeaders(mapOf("Referer" to "https://sluhay.com/"))
            .build()

        val bytes = readAll(dataSource, dataSpec)

        assertArrayEquals(PAYLOAD, bytes)
        assertEquals("the owning source's Referer must reach the origin", "https://sluhay.com/", server.lastReferer)
        assertEquals("the shared browser identity, not a hardcoded UA", BrowserIdentity.currentUserAgent(), server.lastUserAgent)
    }

    @Test
    fun `a Range request reaches the origin and serves the slice`() {
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url()))
            .setPosition(5L)
            .setLength(5L)
            .build()

        val bytes = readAll(dataSource, dataSpec)

        assertEquals("seek must ride Range like it always did", "bytes=5-9", server.lastRange)
        assertEquals("56789", bytes.decodeToString())
        assertEquals("bytes 5-9/10", server.lastContentRange)
    }

    @Test
    fun `a same-protocol redirect is followed to the origin`() {
        val redirector = RedirectStub("http://127.0.0.1:${server.listeningPort}/audio.mp3")
        redirector.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val dataSource = factory.createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse("http://127.0.0.1:${redirector.listeningPort}/go"))
                .build()

            val bytes = readAll(dataSource, dataSpec)

            assertArrayEquals("the redirect chain must land on the real audio", PAYLOAD, bytes)
            assertEquals(1, server.audioRequests)
        } finally {
            redirector.stop()
        }
    }

    @Test
    fun `the redirect policy refuses a cleartext downgrade and allows the same scheme`() {
        // The pure SEC-018 rule the playback engine enforces on every hop.
        assertFalse("https→http is a cleartext downgrade — refused", PlaybackRedirects.follow("https://cdn.example/a.mp3", "http://cdn.example/a.mp3"))
        assertTrue("same scheme rides on", PlaybackRedirects.follow("https://cdn.example/a.mp3", "https://cdn.example/b.mp3"))
        assertTrue("a relative Location resolves against the request", PlaybackRedirects.follow("https://cdn.example/dir/a.mp3", "b.mp3"))
    }

    /** Reads the whole response body through the Media3 data source contract. */
    private fun readAll(dataSource: OkHttpDataSource, dataSpec: DataSpec): ByteArray {
        dataSource.open(dataSpec)
        val out = ArrayList<Byte>(PAYLOAD.size)
        val buffer = ByteArray(1024)
        while (true) {
            val n = dataSource.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            for (i in 0 until n) out.add(buffer[i])
        }
        dataSource.close()
        return out.toByteArray()
    }

    private fun url(): String = "http://127.0.0.1:${server.listeningPort}/audio.mp3"

    /** Minimal controllable origin; honors Range and records what hit the wire. */
    private class TransportStub : NanoHTTPD("127.0.0.1", 0) {
        var lastReferer: String? = null
            private set
        var lastUserAgent: String? = null
            private set
        var lastRange: String? = null
            private set
        var lastContentRange: String? = null
            private set
        var audioRequests: Int = 0
            private set

        override fun serve(session: IHTTPSession): Response {
            audioRequests++
            lastReferer = session.headers["referer"]
            lastUserAgent = session.headers["user-agent"]
            lastRange = session.headers["range"]
            val range = session.headers["range"]
            if (range != null) {
                val match = RANGE_REGEX.matchEntire(range.trim())
                if (match != null) {
                    val start = match.groupValues[1].toInt()
                    val slice = PAYLOAD.copyOfRange(start, PAYLOAD.size)
                    lastContentRange = "bytes $start-${PAYLOAD.size - 1}/${PAYLOAD.size}"
                    return newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT, "audio/mpeg",
                        slice.inputStream(), slice.size.toLong()
                    ).apply { addHeader("Content-Range", lastContentRange) }
                }
            }
            return newFixedLengthResponse(
                Response.Status.OK, "audio/mpeg",
                PAYLOAD.inputStream(), PAYLOAD.size.toLong()
            )
        }
    }

    /** Answers 302 with a Location header — the redirect hop. */
    private class RedirectStub(private val location: String) : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
            response.addHeader("Location", location)
            return response
        }
    }

    private companion object {
        val PAYLOAD = "0123456789".toByteArray(Charsets.US_ASCII)
        val RANGE_REGEX = Regex("""^bytes=(\d+)-(\d*)$""")
    }
}
