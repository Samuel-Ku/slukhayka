package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.source.UNKNOWN_SOURCE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

/**
 * #1487 (slice 1) — a book page of a KNOWN source is routed by the existing
 * registry, and the link's canonical form is what dedup and the import door
 * see. Pure: no network, no Android.
 */
class KnownSourceLinkResolverTest {

    /** The registry shape of production: `sourceIdForUrl` reads these hosts. */
    private val sources = mapOf(
        "audiobook.co.ua" to "audiobookcoua",
        "sound-books.net" to "soundbooks",
        "audiobook-mp3.com" to "audiobookmp3"
    )

    private fun sourceFor(url: String): String {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        return sources[host] ?: UNKNOWN_SOURCE_ID
    }

    private fun resolve(raw: String): KnownSourceLink? =
        KnownSourceLinkResolver.resolve(raw, ::sourceFor)

    @Test
    fun `a book page of a known source resolves to its registry id and canonical url`() {
        assertEquals(
            KnownSourceLink(
                sourceId = "audiobookcoua",
                url = "https://audiobook.co.ua/doktor-son-stiven-king"
            ),
            resolve("https://audiobook.co.ua/doktor-son-stiven-king/")
        )
        assertEquals(
            KnownSourceLink(
                sourceId = "soundbooks",
                url = "https://sound-books.net/audiobook/pid-kupolom"
            ),
            resolve("http://www.sound-books.net/audiobook/pid-kupolom")
        )
    }

    @Test
    fun `tracking query and fragment are dropped from the canonical form`() {
        assertEquals(
            "https://audiobook.co.ua/doktor-son-stiven-king",
            KnownSourceLinkResolver.canonicalUrl(
                "https://audiobook.co.ua/doktor-son-stiven-king/?utm_source=chat&fbclid=1#chapters"
            )
        )
    }

    @Test
    fun `the home page of a known source is not a book page`() {
        assertNull(resolve("https://audiobook.co.ua/"))
        assertNull(resolve("https://sound-books.net"))
    }

    @Test
    fun `an unknown host is not a known source link`() {
        assertNull(resolve("https://example.com/books/some-book"))
        assertNull(resolve("https://youtu.be/abc12345678"))
        assertNull(resolve("https://t.me/bookchannel/42"))
    }

    @Test
    fun `a non-http link is refused instead of guessed`() {
        assertNull(KnownSourceLinkResolver.canonicalUrl("ftp://audiobook.co.ua/book"))
        assertNull(KnownSourceLinkResolver.canonicalUrl("file:///tmp/book.html"))
        assertNull(KnownSourceLinkResolver.canonicalUrl("audiobook.co.ua/book"))
        assertNull(KnownSourceLinkResolver.canonicalUrl("   "))
        assertNull(KnownSourceLinkResolver.canonicalUrl("not a url at all"))
    }

    @Test
    fun `a path keeps its case and inner slashes`() {
        assertEquals(
            "https://audiobook.co.ua/Series/Book-1",
            KnownSourceLinkResolver.canonicalUrl("https://Audiobook.Co.Ua/Series/Book-1/")
        )
    }
}
