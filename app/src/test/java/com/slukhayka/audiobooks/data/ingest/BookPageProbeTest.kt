package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1487 (slice 2) — the shared page probe of an unknown host. Pure: HTML in,
 * the page's own facts out; nothing invented, nothing executed.
 */
class BookPageProbeTest {

    private val pageUrl = "https://unknown-site.example/book"

    @Test
    fun `og meta and media elements are read in page order`() {
        val html = """
            <html><head>
              <meta property="og:title" content="Неначе сон — Іван Франко">
              <meta property="book:author" content="Іван Франко">
              <meta property="og:image" content="/covers/nenache-son.jpg">
              <meta property="og:description" content="Повість Івана Франка">
            </head><body>
              <audio src="/audio/part-1.mp3" title="Частина 1"></audio>
              <audio src="/audio/part-2.mp3" title="Частина 2"></audio>
            </body></html>
        """.trimIndent()

        val page = BookPageProbe.parse(html, pageUrl)!!

        assertEquals("Неначе сон — Іван Франко", page.title)
        assertEquals("Іван Франко", page.author)
        assertEquals("https://unknown-site.example/covers/nenache-son.jpg", page.coverImageUrl)
        assertEquals("Повість Івана Франка", page.description)
        assertEquals(
            listOf(
                ProbedAudio("https://unknown-site.example/audio/part-1.mp3", "Частина 1"),
                ProbedAudio("https://unknown-site.example/audio/part-2.mp3", "Частина 2")
            ),
            page.audios
        )
    }

    @Test
    fun `json ld contentUrl supplies the playlist and the author object`() {
        val html = """
            <html><head><title>Сторінка</title>
            <script type="application/ld+json">
            {"@type":"Audiobook","name":"Доктор Сон","author":{"@type":"Person","name":"Стівен Кінг"},
             "contentUrl":"https://cdn.example/a.mp3"}
            </script>
            <script type="application/ld+json">
            {"@type":"AudioObject","contentUrl":"https://cdn.example/b.mp3"}
            </script></head></html>
        """.trimIndent()

        val page = BookPageProbe.parse(html, pageUrl)!!

        assertEquals("Стівен Кінг", page.author)
        assertEquals(
            listOf("https://cdn.example/a.mp3", "https://cdn.example/b.mp3"),
            page.audios.map { it.url }
        )
    }

    @Test
    fun `json ld contentUrl is the only door to a stream url without a media extension`() {
        // A playlist endpoint (`/stream/42`) carries no `.mp3` suffix, so the
        // bare-URL scan cannot see it: only the schema.org contentUrl does.
        val html = """
            <html><head><script type="application/ld+json">
            {"@type":"AudioObject","contentUrl":"https://cdn.example/stream/42"}
            </script></head></html>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/stream/42"),
            BookPageProbe.parse(html, pageUrl)!!.audios.map { it.url }
        )
    }

    @Test
    fun `bare media urls in the markup are found and deduplicated`() {
        val html = """
            <html><body>
            <a href="https://cdn.example/book.mp3">завантажити</a>
            <a href="https://cdn.example/book.mp3">дубль</a>
            <script>var audio = "https://cdn.example/book-2.m4a?token=abc";</script>
            <audio src="https://cdn.example/book.mp3"></audio>
            </body></html>
        """.trimIndent()

        val page = BookPageProbe.parse(html, pageUrl)!!

        assertEquals(
            listOf(
                "https://cdn.example/book-2.m4a?token=abc",
                "https://cdn.example/book.mp3"
            ).sorted(),
            page.audios.map { it.url }.sorted()
        )
        assertEquals(2, page.audios.size)
    }

    @Test
    fun `a readable page without audio keeps its facts and no chapters`() {
        val html = """
            <html><head><meta property="og:title" content="Книга без аудіо"></head></html>
        """.trimIndent()

        val page = BookPageProbe.parse(html, pageUrl)!!

        assertEquals("Книга без аудіо", page.title)
        assertTrue(page.audios.isEmpty())
    }

    @Test
    fun `empty and oversized input is unreadable, never a blank page`() {
        assertNull(BookPageProbe.parse("", pageUrl))
        assertNull(BookPageProbe.parse("   ", pageUrl))
        val huge = "<html>" + "x".repeat(BookPageProbe.MAX_HTML_CHARS) + "</html>"
        assertNull(BookPageProbe.parse(huge, pageUrl))
    }

    @Test
    fun `data javascript and blob urls never become tracks`() {
        val html = """
            <html><body>
            <audio src="data:audio/mp3;base64,AAAA"></audio>
            <audio src="javascript:alert(1)"></audio>
            <audio src="blob:https://unknown-site.example/123"></audio>
            </body></html>
        """.trimIndent()

        assertTrue(BookPageProbe.parse(html, pageUrl)!!.audios.isEmpty())
    }

    @Test
    fun `entities are decoded and the title tag is the fallback`() {
        val html = """
            <html><head><title>Книга &amp; повість &ndash; Автор</title></head></html>
        """.trimIndent()

        assertEquals("Книга & повість – Автор", BookPageProbe.parse(html, pageUrl)!!.title)
    }

    @Test
    fun `a chapter title the markup did not carry falls back to an ordinal`() {
        val page = ProbedPage(
            url = pageUrl,
            title = "Книга",
            author = null,
            coverImageUrl = null,
            description = null,
            audios = listOf(
                ProbedAudio("https://cdn.example/1.mp3"),
                ProbedAudio("https://cdn.example/2.mp3", "Фінал")
            )
        )

        val detail = page.toSourceBookDetail()

        assertEquals("Книга", detail.title)
        assertEquals("", detail.author)
        assertEquals(
            listOf("Частина 1", "Фінал"),
            detail.chapters.map { it.title }
        )
        assertEquals(
            listOf("https://cdn.example/1.mp3", "https://cdn.example/2.mp3"),
            detail.chapters.map { it.streamUrl }
        )
    }
}
