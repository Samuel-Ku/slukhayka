package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.HttpFetcher
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1751 — the cast proxy's upstream door. A YouTube track persists a WATCH
 * URL (identity); the receiver can only play bytes, so the door must resolve
 * it through the same per-use resolver the local engine uses, cache the signed
 * result for the session, and re-resolve exactly once when it expires.
 *
 * These are pure JVM tests: no receiver, no CastContext, no network. The
 * [HttpFetcher] fake records every URL the proxy would have fetched.
 */
class CastUpstreamTest {

    private val watchUrl = "https://www.youtube.com/watch?v=ozaZXk5Qcwc"
    private val audioUrl =
        "https://rr1---sn-4g5e6nes.googlevideo.com/videoplayback?mime=audio%2Fmp4&expire=1"

    @Test
    fun `a youtube watch url is resolved to the audio url before any fetch`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response() }
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { resolvedWatch ->
                assertEquals(watchUrl, resolvedWatch)
                audioUrl
            },
            headers = { emptyMap() }
        )

        val response = upstream.open("ch0", null)

        assertNotNull(response)
        assertEquals(listOf(audioUrl), fetcher.requested)
        assertEquals("audio/mp4", response!!.contentType)
    }

    @Test
    fun `a failed resolution is an honest refusal and never fetches the watch page`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response() }
        var resolverCalls = 0
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { resolverCalls++; null },
            headers = { emptyMap() }
        )

        assertNull(upstream.open("ch0", null))
        assertEquals(1, resolverCalls)
        assertTrue("the watch URL must never reach the fetcher", fetcher.requested.isEmpty())
    }

    @Test
    fun `plain urls are fetched unchanged and never touch the resolver`() {
        val plainUrl = "https://lihtar.in.ua/audio/0.mp3"
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response() }
        val upstream = CastUpstream(
            chapterUrls = listOf(plainUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { error("a plain URL must not be resolved") },
            headers = { emptyMap() }
        )

        assertNotNull(upstream.open("ch0", null))
        assertEquals(listOf(plainUrl), fetcher.requested)
    }

    @Test
    fun `a chapter is resolved once and later range requests reuse the cached audio url`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response() }
        var resolverCalls = 0
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { resolverCalls++; audioUrl },
            headers = { emptyMap() }
        )

        upstream.open("ch0", null)
        upstream.open("ch0", "bytes=0-1023")

        assertEquals(1, resolverCalls)
        assertEquals(listOf(audioUrl, audioUrl), fetcher.requested)
    }

    @Test
    fun `an expired audio url is re-resolved exactly once and then honestly refused`() {
        val firstAudio = "$audioUrl&first"
        val secondAudio = "$audioUrl&second"
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { null }
        var resolverCalls = 0
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = {
                resolverCalls++
                if (resolverCalls == 1) firstAudio else secondAudio
            },
            headers = { emptyMap() }
        )

        assertNull(upstream.open("ch0", null))
        assertEquals(listOf(firstAudio, secondAudio), fetcher.requested)
        assertEquals(2, resolverCalls)

        // The budget is spent: a further request retries the cached URL once
        // more but must not extract a third time.
        assertNull(upstream.open("ch0", null))
        assertEquals(2, resolverCalls)
        assertEquals(listOf(firstAudio, secondAudio, secondAudio), fetcher.requested)
    }

    @Test
    fun `the origin content type wins over the extension guess`() {
        val plainUrl = "https://example.org/videoplayback"
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = "audio/mp4") }
        val upstream = CastUpstream(
            chapterUrls = listOf(plainUrl),
            bookId = "book-1",
            fetcher = fetcher,
            headers = { emptyMap() }
        )

        assertEquals("audio/mp4", upstream.open("ch0", null)!!.contentType)
    }

    private fun response(
        status: Int = 200,
        contentType: String? = "audio/mp4"
    ): HttpFetcher.RangeResponse = HttpFetcher.RangeResponse(
        stream = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
        status = status,
        contentLength = 3,
        contentRange = null,
        contentType = contentType
    )

    private class RecordingFetcher : HttpFetcher() {
        val requested = mutableListOf<String>()
        var onRequest: ((String) -> HttpFetcher.RangeResponse?)? = null

        override fun getRangeStream(
            url: String,
            extraHeaders: Map<String, String>
        ): HttpFetcher.RangeResponse? {
            requested += url
            return onRequest?.invoke(url)
        }
    }
}
