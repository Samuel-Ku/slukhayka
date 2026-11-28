package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.AudioStreamSpec
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.ResolvedAudioStream
import com.slukhayka.audiobooks.data.source.YouTubeStreamResolver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * Resolution hands back the URL together with the MIME family of its bytes,
 * because the cast playlist already declared a family to the receiver: a
 * stream of another family is refused, never mislabelled. A stale-URL reset
 * is conditional on the entry still being the one that failed, so a
 * concurrent refresh survives.
 *
 * These are pure JVM tests: no receiver, no CastContext, no network. The
 * [HttpFetcher] fake records every URL the proxy would have fetched.
 */
class CastUpstreamTest {

    private val watchUrl = "https://www.youtube.com/watch?v=ozaZXk5Qcwc"
    private val audioUrl =
        "https://rr1---sn-4g5e6nes.googlevideo.com/videoplayback?mime=audio%2Fmp4&expire=1"
    private val webmUrl =
        "https://rr1---sn-4g5e6nes.googlevideo.com/videoplayback?mime=audio%2Fwebm&expire=1"

    @Test
    fun `a youtube watch url is resolved to the audio url before any fetch`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = null) }
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { resolvedWatch ->
                assertEquals(watchUrl, resolvedWatch)
                ResolvedAudioStream(audioUrl, "audio/mp4")
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

    /**
     * #1751 review — the witness for the P2: a resolver result that is NOT
     * M4A cannot be served under the playlist's `audio/mp4` declaration. The
     * chapter is refused (the receiver starts no playback; the controller
     * reports the cast ERROR outcome) instead of being handed mislabelled
     * bytes.
     */
    @Test
    fun `a non-m4a resolver result is refused instead of mislabelled`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response() }
        var resolverCalls = 0
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = {
                resolverCalls++
                ResolvedAudioStream(webmUrl, "audio/webm")
            },
            headers = { emptyMap() }
        )

        assertNull("a WebM stream is not the family the playlist declared", upstream.open("ch0", null))
        assertTrue("no bytes of the wrong family may reach the receiver", fetcher.requested.isEmpty())
        assertEquals(1, resolverCalls)

        // The refusal is stable, and the budget ends it (no endless extraction).
        assertNull(upstream.open("ch0", "bytes=0-1023"))
        assertNull(upstream.open("ch0", "bytes=0-1023"))
        assertEquals(2, resolverCalls)
        assertTrue(fetcher.requested.isEmpty())
    }

    /**
     * #1751 review, P2 — the witness for the unknown-family path: a
     * non-M4A direct candidate whose extractor gives no MIME (the resolver
     * tests pin that it stays null) must never be served as the playlist's
     * declared `audio/mp4`, even when the range response also omits
     * Content-Type. Silence is not evidence of MP4: the chapter is refused
     * before any byte reaches the receiver.
     */
    @Test
    fun `an unknown family is refused instead of being served as the declared mp4`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = null) }
        var resolverCalls = 0
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { resolverCalls++; ResolvedAudioStream(audioUrl, null) },
            headers = { emptyMap() }
        )

        assertNull("an unproven family may not pass under the MP4 declaration", upstream.open("ch0", null))
        assertEquals(1, resolverCalls)
        assertTrue("no unproven bytes may reach the receiver", fetcher.requested.isEmpty())

        // The refusal is stable, and the budget ends it (no endless extraction).
        assertNull(upstream.open("ch0", "bytes=0-1023"))
        assertNull(upstream.open("ch0", "bytes=0-1023"))
        assertEquals(2, resolverCalls)
        assertTrue(fetcher.requested.isEmpty())
    }

    /**
     * #1751 review, P2 — the same witness through the real resolver seam: a
     * non-M4A direct candidate whose extractor knows no MIME resolves to an
     * unknown family, and the cast door refuses it before any fetch. This is
     * the exact production path the reviewers named (opus candidate, missing
     * `ext`, range response without Content-Type).
     */
    @Test
    fun `a real non-m4a candidate with unknown mime never reaches the receiver as mp4`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = null) }
        val resolver = YouTubeStreamResolver(
            extractAudioStreams = {
                listOf(
                    AudioStreamSpec(
                        webmUrl,
                        isM4a = false,
                        isDirectUrl = true,
                        bitrateKbps = 96,
                        mimeType = null
                    )
                )
            }
        )
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = resolver::resolveStream,
            headers = { emptyMap() }
        )

        assertNull(upstream.open("ch0", null))
        assertTrue("no unproven bytes may reach the receiver", fetcher.requested.isEmpty())
    }

    /**
     * #1751 review, P2 — even with a proven family, an origin that names a
     * different audio/video family contradicts what the playlist declared, so
     * the contradictory response is refused rather than handed over.
     */
    @Test
    fun `an origin naming another family is refused instead of contradicting the declaration`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = "audio/webm") }
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { ResolvedAudioStream(audioUrl, "audio/mp4") },
            headers = { emptyMap() }
        )

        assertNull(upstream.open("ch0", null))
    }

    /**
     * #1751 review, P2 — refusing on an origin MIME mismatch happens AFTER
     * the fetch opened the body. Nobody downstream will ever see that stream,
     * so the refusal itself must release it exactly once or every later range
     * request leaks a socket.
     */
    @Test
    fun `an origin family mismatch closes the fetched body exactly once`() {
        val body = TrackingInputStream()
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = "audio/webm", stream = body) }
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { ResolvedAudioStream(audioUrl, "audio/mp4") },
            headers = { emptyMap() }
        )

        assertNull(upstream.open("ch0", null))
        assertEquals("the refused body must be released once", 1, body.closeCount.get())
    }

    /**
     * #1751 review, P2 — the opposite ownership rule: a response that is
     * handed to PlaybackProxy must NOT be closed early; the proxy's
     * SelfClosingStream owns it from here.
     */
    @Test
    fun `a playable response transfers body ownership without closing it`() {
        val body = TrackingInputStream()
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = null, stream = body) }
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { ResolvedAudioStream(audioUrl, "audio/mp4") },
            headers = { emptyMap() }
        )

        assertNotNull(upstream.open("ch0", null))
        assertEquals("the proxy owns the body and closes it later", 0, body.closeCount.get())
    }

    /**
     * #1751 review, P2 — an origin type that names no family (for example
     * `application/octet-stream`) is not evidence against the resolver's
     * proven family, and it must not replace it either.
     */
    @Test
    fun `an unhelpful origin type does not replace the proven family`() {
        val fetcher = RecordingFetcher()
        fetcher.onRequest = { response(contentType = "application/octet-stream") }
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = { ResolvedAudioStream(audioUrl, "audio/mp4") },
            headers = { emptyMap() }
        )

        assertEquals("audio/mp4", upstream.open("ch0", null)!!.contentType)
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
            resolver = { resolverCalls++; ResolvedAudioStream(audioUrl, "audio/mp4") },
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
                if (resolverCalls == 1) {
                    ResolvedAudioStream(firstAudio, "audio/mp4")
                } else {
                    ResolvedAudioStream(secondAudio, "audio/mp4")
                }
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

    /**
     * #1751 review — the witness for the second P2. Three range requests fetch
     * the SAME stale URL and fail; the first one re-resolves and caches the
     * fresh URL, then the two late failures must not delete it. Before the
     * conditional reset the fresh entry disappeared, the last resolve attempt
     * was spent, and the chapter returned null for every later request even
     * though a usable URL had just been cached.
     *
     * Latches make the interleaving deterministic: the refreshing request's
     * stale fetch fails first, and the other two fail only AFTER the fresh URL
     * is cached.
     */
    @Test
    fun `a late error of the stale url does not delete a concurrently refreshed url`() {
        val staleUrl = "$audioUrl&stale"
        val freshUrl = "$audioUrl&fresh"
        val staleFetchCalls = AtomicInteger(0)
        val staleFetches = CountDownLatch(3)
        val releaseRefresher = CountDownLatch(1)
        val freshUrlCached = CountDownLatch(1)

        val fetcher = RecordingFetcher()
        fetcher.onRequest = { url ->
            when (url) {
                staleUrl -> {
                    staleFetches.countDown()
                    if (staleFetchCalls.incrementAndGet() == 1) {
                        // The refreshing request fails first, then re-resolves.
                        releaseRefresher.await(5, TimeUnit.SECONDS)
                    } else {
                        // The late failures wait until the cache is refreshed.
                        freshUrlCached.await(5, TimeUnit.SECONDS)
                    }
                    null
                }
                freshUrl -> {
                    freshUrlCached.countDown()
                    response(contentType = null)
                }
                else -> null
            }
        }
        val resolverCalls = AtomicInteger(0)
        val upstream = CastUpstream(
            chapterUrls = listOf(watchUrl),
            bookId = "book-1",
            fetcher = fetcher,
            resolver = {
                if (resolverCalls.incrementAndGet() == 1) {
                    ResolvedAudioStream(staleUrl, "audio/mp4")
                } else {
                    ResolvedAudioStream(freshUrl, "audio/mp4")
                }
            },
            headers = { emptyMap() }
        )

        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = (1..3).map {
                pool.submit<PlaybackProxy.UpstreamResponse?> { upstream.open("ch0", null) }
            }
            assertTrue(
                "all three range requests must reach the stale URL first",
                staleFetches.await(10, TimeUnit.SECONDS)
            )
            releaseRefresher.countDown()

            results.forEachIndexed { number, result ->
                assertNotNull(
                    "request #$number: a fresh URL was usable, so no request may be refused",
                    result.get(10, TimeUnit.SECONDS)
                )
            }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("the stale URL is extracted once and re-resolved once", 2, resolverCalls.get())
        assertEquals("each request starts on the stale URL", 3, fetcher.requested.count { it == staleUrl })
        assertEquals(
            "every request ends on the one fresh URL, so the refresh was not lost",
            3,
            fetcher.requested.count { it == freshUrl }
        )
        // The cache is still healthy for the next range request.
        assertNotNull(upstream.open("ch0", "bytes=0-1023"))
        assertEquals(4, fetcher.requested.count { it == freshUrl })
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
        contentType: String? = "audio/mp4",
        stream: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    ): HttpFetcher.RangeResponse = HttpFetcher.RangeResponse(
        stream = stream,
        status = status,
        contentLength = 3,
        contentRange = null,
        contentType = contentType
    )

    /** Counts close() calls so ownership can be asserted exactly. */
    private class TrackingInputStream : InputStream() {
        val closeCount = AtomicInteger(0)

        override fun read(): Int = -1

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class RecordingFetcher : HttpFetcher() {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
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
