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
        <meta property="og:image" content="https://lihtar.in.ua/images/biblioteka/85/w_bojahuz-101.jpg">
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
        <a href="https://lihtar.in.ua/biblioteka/dytjacha-literatura/zahublena-stinka">Загублена стінка</a>
        </body></html>
    """.trimIndent()

    // Real book page: the author rides in og:description and may carry HTML
    // entities (live sample: «Наталія Дев&#039;ятко»).
    private val andriykoPage = """
        <html><head>
        <meta property="og:title" content="Андрійко та шахове королівство">
        <meta property="og:description" content="Наталія Дев&#039;ятко">
        <meta property="og:image" content="https://lihtar.in.ua/images/biblioteka/86/w_andriyko-ta-shakhove-korolivstvo.jpg">
        </head><body>
        <h1>Андрійко та шахове королівство</h1>
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
    fun `new feed enriches every entry from its book page - real title and author`() = runBlocking {
        val adapter = LihtarAdapter(
            FakeFetcher(
                mapOf(
                    "https://lihtar.in.ua/biblioteka" to libraryPage,
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura" to childCategoryPage,
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz" to bookPage,
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura/andriyko-ta-shakhove-korolivstvo" to andriykoPage
                    // zahublena-stinka intentionally has no page fixture: the
                    // entry must fall back to the transliterated slug.
                )
            )
        )

        val books = adapter.fetchNew(limit = 10)

        // The real Cyrillic title and the author come from each book page, so
        // Ukrainian queries match and the Work-level merge key can form.
        assertEquals(3, books.size)
        assertEquals("Боягуз", books[0].title)
        assertEquals("Микола Стеценко", books[0].author)
        assertEquals("lihtar", books[0].sourceId)
        // The book page's og:image becomes the feed card cover.
        assertEquals("https://lihtar.in.ua/images/biblioteka/85/w_bojahuz-101.jpg", books[0].coverImageUrl)
        // Entities in the author decode before the merge key normalizes it.
        assertEquals("Андрійко та шахове королівство", books[1].title)
        assertEquals("Наталія Дев'ятко", books[1].author)
        assertEquals("https://lihtar.in.ua/images/biblioteka/86/w_andriyko-ta-shakhove-korolivstvo.jpg", books[1].coverImageUrl)
        // A failed page fetch keeps the transliterated slug, best-effort.
        assertEquals("zahublena stinka", books[2].title.lowercase())
        assertEquals("", books[2].author)
        assertEquals(null, books[2].coverImageUrl)
    }
}
