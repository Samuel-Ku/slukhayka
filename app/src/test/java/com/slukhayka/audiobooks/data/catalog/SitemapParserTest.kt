package com.slukhayka.audiobooks.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #526 — the bounded sitemap reader: urlset AND sitemap-index forms, canonical
 * normalization, host allowlisting, canonical dedup, and honest bounds
 * (oversized → miss, malformed → empty).
 */
class SitemapParserTest {

    private val acceptAll: (String) -> Boolean = { true }

    private fun urlset(vararg locs: String): String =
        "<urlset>" + locs.joinToString("") { "<url><loc>$it</loc></url>" } + "</urlset>"

    @Test
    fun `a urlset yields its accepted locs`() {
        val parsed = SitemapParser.parse(
            urlset(
                "https://audiobook.co.ua/igra-dzheralda/",
                "https://audiobook.co.ua/tini-zabutykh/"
            ),
            acceptAll
        )

        assertEquals(
            listOf("https://audiobook.co.ua/igra-dzheralda", "https://audiobook.co.ua/tini-zabutykh"),
            parsed!!.map { it.canonicalUrl }
        )
    }

    @Test
    fun `a sitemap index is read through its own locs`() {
        val index = """
            <sitemapindex>
              <sitemap><loc>https://audiobook.co.ua/post-sitemap.xml</loc></sitemap>
              <sitemap><loc>https://audiobook.co.ua/post-sitemap2.xml</loc></sitemap>
            </sitemapindex>
        """.trimIndent()

        val parsed = SitemapParser.parse(index, acceptAll)

        assertEquals(2, parsed!!.size)
    }

    @Test
    fun `locs are canonicalized and deduplicated`() {
        val parsed = SitemapParser.parse(
            urlset(
                "HTTPS://Audiobook.CO.UA/Kniga/?utm=1#top",
                "https://audiobook.co.ua/Kniga",
                "https://audiobook.co.ua/Kniga/",
                "ftp://audiobook.co.ua/nope",
                "not a url"
            ),
            acceptAll
        )

        assertEquals(listOf("https://audiobook.co.ua/Kniga"), parsed!!.map { it.canonicalUrl })
        assertEquals("the raw loc is preserved for fetching", "HTTPS://Audiobook.CO.UA/Kniga/?utm=1", parsed.first().url)
    }

    @Test
    fun `the host allowlist decides on the canonical form`() {
        val parsed = SitemapParser.parse(
            urlset(
                "https://audiobook.co.ua/kniga/",
                "https://chytaylo.com.ua/books/kniga",
                "https://evil.example/kniga"
            )
        ) { canonical -> canonical.startsWith("https://audiobook.co.ua/") }

        assertEquals(listOf("https://audiobook.co.ua/kniga"), parsed!!.map { it.canonicalUrl })
    }

    @Test
    fun `an oversized document is a miss, never parsed`() {
        val oversized = "<urlset>" + "<url><loc>https://audiobook.co.ua/k/</loc></url>".repeat(120_000) + "</urlset>"
        assertTrue(oversized.length > SitemapPolicy.MAX_BYTES)

        assertNull(SitemapParser.parse(oversized, acceptAll))
    }

    @Test
    fun `the extracted set is capped`() {
        val many = buildString {
            append("<urlset>")
            repeat(SitemapPolicy.MAX_ENTRIES + 50) { index ->
                append("<url><loc>https://audiobook.co.ua/k$index</loc></url>")
            }
            append("</urlset>")
        }

        assertEquals(SitemapPolicy.MAX_ENTRIES, SitemapParser.parse(many, acceptAll)!!.size)
    }

    @Test
    fun `a malformed document is an empty miss`() {
        assertTrue(SitemapParser.parse("<html>nope</html>", acceptAll)!!.isEmpty())
        assertTrue(SitemapParser.parse("", acceptAll)!!.isEmpty())
    }

    @Test
    fun `the sitemap ttl is a week`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, SitemapPolicy.SITEMAP_TTL_MS)
    }
}
