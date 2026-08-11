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

    // Real search page: each hit is a .poster block with the Cyrillic title in
    // poster__title, the author in the first poster__subtitle and the duration
    // clock in the second (captured during this session's live fetch).
    private val searchPage = """
        <html><body>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/5359-taras-shevchenko-kobzar.html" class="poster__link"><div class="poster__title line-clamp">Кобзар</div></a>
                <div class="poster__subtitle ws-nowrap">Тарас Шевченко</div>
                <div class="poster__subtitle"><span class="lcomm__link fal fa-clock" aria-hidden="true"></span><span class="js-duration" data-time="02:19:35"></span></div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2025-05/medium/shevchenko-taras-kobzar.webp" alt="Шевченко Тарас - Кобзар">
            </div>
        </div>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7199-nechista-sila.html" class="poster__link"><div class="poster__title line-clamp">Нечиста сила</div></a>
                <div class="poster__subtitle ws-nowrap">Іван Андрусяк</div>
            </div>
        </div>
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
    fun `search parses poster blocks with real title author and cover`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap(), fallback = searchPage))

        val books = adapter.search("Кобзар")

        assertEquals(2, books.size)
        assertEquals("https://4read.org/5359-taras-shevchenko-kobzar.html", books[0].url)
        assertEquals("Кобзар", books[0].title)
        // The real Cyrillic author replaces the old "4read.org" placeholder so
        // the same Work found on another source can merge.
        assertEquals("Тарас Шевченко", books[0].author)
        assertEquals("https://4read.org/uploads/posts/2025-05/medium/shevchenko-taras-kobzar.webp", books[0].coverImageUrl)
        assertEquals("4read", books[0].sourceId)
        assertEquals("Нечиста сила", books[1].title)
        assertEquals("Іван Андрусяк", books[1].author)
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
