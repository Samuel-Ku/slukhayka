package com.example.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-13 T2 — the interception-layer parser seam. Fixtures mirror the real
 * `[REQ]` line format of the #71/#78 prototype (WebViewInterceptionPrototype
 * Activity): one line per request, `sub ` for subresources, `hdrs=`
 * when interesting headers (Range/Accept/Referer) were present.
 *
 * A real player issues several Range requests per track (bytes=0-,
 * bytes=32768-, ...), so the raw log has repeats; the parser collapses them
 * into the ordered, distinct chapter URLs.
 */
class WebViewInterceptLogParserTest {

    private val twoTrackLog = """
        [PAGE_FINISHED] https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html
        [REQ] MAIN https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html hdrs={}
        [REQ] sub  https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt hdrs={Accept=*/*}
        [REQ] sub  https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3 hdrs={Range=bytes=0-}
        [REQ] sub  https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3 hdrs={Range=bytes=32768-}
        [REQ] sub  https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3 hdrs={Range=bytes=65536-}
        [REQ] sub  https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-1.mp3 hdrs={Range=bytes=0-}
        [REQ] sub  https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-1.mp3 hdrs={Range=bytes=32768-}
        [REQ] sub  https://sluhay.com/engine/modules/playerjs.js hdrs={}
        [REQ] sub  https://sluhay.com/templates/.../style.css hdrs={}
    """.trimIndent()

    @Test
    fun `range repeats of the same track collapse to ordered distinct urls`() {
        val urls = WebViewInterceptLogParser.audioUrlsInOrder(twoTrackLog)

        assertEquals(2, urls.size)
        assertEquals(
            "https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3",
            urls[0]
        )
        assertEquals(
            "https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-1.mp3",
            urls[1]
        )
    }

    @Test
    fun `non-audio requests js css and the playlist never become audio`() {
        val requests = WebViewInterceptLogParser.audioRequests(twoTrackLog)

        // Raw request log: 3 Range requests of track-0 + 2 of track-1 (repeats
        // are collapsed later by audioUrlsInOrder). The playlist, js and css
        // lines must never count as audio.
        assertEquals(5, requests.size)
        assertTrue(requests.all { it.rangeRequested })
        assertTrue(requests.all { it.url.contains("track-") })
        assertTrue(requests.none { it.url.contains("pl.txt") })
        assertTrue(requests.none { it.url.contains(".js") || it.url.contains(".css") })
    }

    @Test
    fun `usedRangeRequests reports that seeking range requests happened`() {
        assertTrue(WebViewInterceptLogParser.usedRangeRequests(twoTrackLog))
        assertFalse(WebViewInterceptLogParser.usedRangeRequests("[REQ] sub  https://x.invalid/a.js hdrs={}"))
    }

    @Test
    fun `m4a and audio-accept requests count as audio even without a range`() {
        val log = """
            [REQ] sub  https://cdn.example.invalid/book/01.m4a hdrs={Accept=audio/mp4}
            [REQ] sub  https://cdn.example.invalid/book/02.m4a hdrs={Accept=audio/mp4}
        """.trimIndent()

        val urls = WebViewInterceptLogParser.audioUrlsInOrder(log)

        assertEquals(2, urls.size)
        assertTrue(urls[0].endsWith("01.m4a"))
    }

    @Test
    fun `chaptersFromUrls maps ordered urls to playable chapters`() {
        val urls = WebViewInterceptLogParser.audioUrlsInOrder(twoTrackLog)

        val chapters = WebViewInterceptLogParser.chaptersFromUrls(urls)

        assertEquals(2, chapters.size)
        assertEquals("Глава 1", chapters[0].title)
        assertEquals(urls[0], chapters[0].streamUrl)
        assertEquals("Глава 2", chapters[1].title)
        assertEquals(urls[1], chapters[1].streamUrl)
    }

    @Test
    fun `chaptersFromUrls dedupes and preserves order`() {
        val chapters = WebViewInterceptLogParser.chaptersFromUrls(
            listOf("a.mp3", "b.mp3", "a.mp3", "c.mp3")
        )

        assertEquals(3, chapters.size)
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), chapters.map { it.streamUrl })
    }

    @Test
    fun `empty log yields no audio`() {
        assertTrue(WebViewInterceptLogParser.audioUrlsInOrder("").isEmpty())
        assertTrue(WebViewInterceptLogParser.audioUrlsInOrder("[REQ] MAIN https://sluhay.com/").isEmpty())
    }
}
