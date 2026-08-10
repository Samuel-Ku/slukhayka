package com.example.data.source

import com.example.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 FourReadAdapter (parser seam). Markup
 * mirrors real 4read.org pages captured during the T1 spike.
 */
class FourReadAdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Неостанній бій">
        <meta property="og:image" content="https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp">
        </head><body>
        <script>var player = new Playerjs({file:"https://4read.org/m3u/7589.txt"});</script>
        </body></html>
    """.trimIndent()

    private val playlistJson = """[{"title":"Глава 1","file":"https://4read.org/uploads/audio/7589/01.mp3"}]"""

    private val searchPage = """
        <html><body>
        <a href="https://4read.org/7589-neostannij-bij.html">Неостанній бій</a>
        <a href="https://4read.org/7611-vkradi-mene-zaraz.html">Вкради мене... Зараз!</a>
        </body></html>
    """.trimIndent()

    private val homepage = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7611-vkradi-mene-zaraz.html" class="poster__link"><div class="poster__title line-clamp">Вкради мене... Зараз!</div></a>
                <div class="poster__subtitle ws-nowrap">Сергій Оріанець</div>
            </div>
        </div>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7589-neostannij-bij.html" class="poster__link"><div class="poster__title line-clamp">Неостанній бій</div></a>
                <div class="poster__subtitle ws-nowrap">Костянтин Шелест</div>
            </div>
        </div>
    """.trimIndent()

    @Test
    fun `book page expands the playerjs playlist into chapters`() = runBlocking {
        val adapter = FourReadAdapter(
            FakeFetcher(
                mapOf(
                    "https://4read.org/7589-neostannij-bij.html" to bookPage,
                    "https://4read.org/m3u/7589.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://4read.org/7589-neostannij-bij.html")

        assertEquals("Неостанній бій", detail.title)
        assertEquals("https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp", detail.coverImageUrl)
        assertEquals(1, detail.chapters.size)
        assertEquals("https://4read.org/uploads/audio/7589/01.mp3", detail.chapters.single().streamUrl)
    }

    @Test
    fun `book page without a playlist yields no chapters instead of fake audio`() = runBlocking {
        val adapter = FourReadAdapter(
            FakeFetcher(mapOf("https://4read.org/empty.html" to "<html><body>nope</body></html>"))
        )

        val detail = adapter.fetchBookPage("https://4read.org/empty.html")

        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `search parses result links into source books`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap(), fallback = searchPage))

        val books = adapter.search("Неостанній")

        assertEquals(2, books.size)
        assertEquals("https://4read.org/7589-neostannij-bij.html", books[0].url)
        assertEquals("Неостанній бій", books[0].title)
        assertEquals("4read", books[0].sourceId)
    }

    @Test
    fun `new feed parses homepage posters`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(mapOf("https://4read.org/" to homepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        assertEquals("Вкради мене... Зараз!", books[0].title)
        assertEquals("Сергій Оріанець", books[0].author)
        assertEquals("https://4read.org/7611-vkradi-mene-zaraz.html", books[0].url)
    }
}
