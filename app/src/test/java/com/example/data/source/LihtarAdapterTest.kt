package com.example.data.source

import com.example.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 LihtarAdapter. Markup mirrors real
 * lihtar.in.ua pages captured during the T1 spike («Боягуз» by Микола
 * Стеценко).
 */
class LihtarAdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Боягуз">
        <meta property="og:description" content="Микола Стеценко">
        </head><body>
        <a href="https://web.lihtar.in.ua/library/dytjacha-literatura/mykola-stecenko-bojahuz/bojahuz" target="_blank" class="lbutton detbtn">Слухати 'Боягуз'</a>
        </body></html>
    """.trimIndent()

    private val playerPage = """
        <html><body>
        <audio id="player" class="player" src="https://web.lihtar.in.ua/audio/library/854/-dlja-ditey-slukhaty-onlayn-bojahuzdytjacha-literatura-0nmcgoa6zik-converted.mp3" autoplay onended="nextsound()"></audio>
        </body></html>
    """.trimIndent()

    // The /biblioteka landing lists category groups only — the feed enumerates
    // each category page to find the books (mirrors the real site).
    private val libraryPage = """
        <html><body>
        <a href="https://lihtar.in.ua/biblioteka/khudozhnja-literatura" class="groupitem">Художня література</a>
        <a href="https://lihtar.in.ua/biblioteka/dytjacha-literatura" class="groupitem">Дитяча література</a>
        </body></html>
    """.trimIndent()

    private val childCategoryPage = """
        <html><body>
        <a href="https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz">Боягуз</a>
        <a href="https://lihtar.in.ua/biblioteka/dytjacha-literatura/andriyko-ta-shakhove-korolivstvo">Андрійко та шахове королівство</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `book page follows the listen link to the direct mp3`() = runBlocking {
        val adapter = LihtarAdapter(
            FakeFetcher(
                mapOf(
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz" to bookPage,
                    "https://web.lihtar.in.ua/library/dytjacha-literatura/mykola-stecenko-bojahuz/bojahuz" to playerPage
                )
            )
        )

        val detail = adapter.fetchBookPage("https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz")

        assertEquals("Боягуз", detail.title)
        assertEquals("Микола Стеценко", detail.author)
        assertEquals(1, detail.chapters.size)
        assertEquals(
            "https://web.lihtar.in.ua/audio/library/854/-dlja-ditey-slukhaty-onlayn-bojahuzdytjacha-literatura-0nmcgoa6zik-converted.mp3",
            detail.chapters.single().streamUrl
        )
    }

    @Test
    fun `book page without a listen link yields no chapters`() = runBlocking {
        val adapter = LihtarAdapter(
            FakeFetcher(mapOf("https://lihtar.in.ua/x" to "<html><body>nope</body></html>"))
        )

        assertTrue(adapter.fetchBookPage("https://lihtar.in.ua/x").chapters.isEmpty())
    }

    @Test
    fun `new feed enumerates category pages to find the books`() = runBlocking {
        val adapter = LihtarAdapter(
            FakeFetcher(
                mapOf(
                    "https://lihtar.in.ua/biblioteka" to libraryPage,
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura" to childCategoryPage
                )
            )
        )

        val books = adapter.fetchNew(limit = 10)

        // Only the category with a fixture contributes; the other yields none.
        assertEquals(2, books.size)
        assertEquals("https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz", books[0].url)
        assertEquals("lihtar", books[0].sourceId)
        assertEquals("bojahuz", books[0].title.lowercase())
    }
}
