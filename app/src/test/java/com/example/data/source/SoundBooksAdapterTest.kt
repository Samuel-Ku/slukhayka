package com.example.data.source

import com.example.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 SoundBooksAdapter. Markup mirrors real
 * sound-books.net pages captured during the T1 spike (book #4111, «Темна
 * матерія»).
 */
class SoundBooksAdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Темна матерія">
        </head><body>
        <script>
        PlayerLang     = {prev: 'Попередній'}
        var player = new Playerjs({file:"https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u"});
        </script>
        </body></html>
    """.trimIndent()

    private val m3u = """
        https://arch.sound-books.net/4111/Темна матерія-01.mp3
        https://arch.sound-books.net/4111/Темна матерія-02.mp3
    """.trimIndent()

    private val homepage = """
        <html><body>
        <a href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html">Темна матерія</a>
        <a href="https://sound-books.net/ukrainska-literatura/2850-statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.html">Статут внутрішньої служби Збройних Сил України</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `book page follows the m3u playlist to direct mp3 chapters`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html" to bookPage,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html")

        assertEquals("Темна матерія", detail.title)
        assertEquals(2, detail.chapters.size)
        assertEquals("https://arch.sound-books.net/4111/Темна матерія-01.mp3", detail.chapters[0].streamUrl)
        assertEquals("https://arch.sound-books.net/4111/Темна матерія-02.mp3", detail.chapters[1].streamUrl)
        // Chapter title comes from the m3u file name.
        assertEquals("Темна матерія-01", detail.chapters[0].title)
    }

    @Test
    fun `book page without a playlist yields no chapters`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(mapOf("https://sound-books.net/x.html" to "<html><body>no player</body></html>"))
        )

        assertTrue(adapter.fetchBookPage("https://sound-books.net/x.html").chapters.isEmpty())
    }

    @Test
    fun `new feed parses recent book links`() = runBlocking {
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to homepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        assertEquals("Темна матерія", books[0].title)
        assertEquals("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html", books[0].url)
        assertEquals("soundbooks", books[0].sourceId)
    }
}
