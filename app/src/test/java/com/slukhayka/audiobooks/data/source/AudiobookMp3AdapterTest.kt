package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 AudiobookMp3Adapter. Markup mirrors real
 * audiobook-mp3.com/uk pages captured during the T1 spike (book #6163,
 * «Клуб боягузів» — playlist `26720.pl.txt` on the redirectto.cc CDN).
 */
class AudiobookMp3AdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Клуб боягузів">
        <meta property="og:description" content="Студентські страшилки обертаються справжньою грою на виживання.">
        </head><body>
        <p>Автор: <a href="/uk-avtor-6163-andrij-kokotjuha">Андрій Кокотюха</a>.</p>
        <script src="/js/playerjs-ua.js?v=1.1"></script>
        <script>var player = new Playerjs({file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt"});</script>
        </body></html>
    """.trimIndent()

    // Real playerjs titles from the live capture (book #6192, «Соломон
    // Кейн» — playlist `26772.pl.txt`): «Автор N - Назва глави.mp3».
    private val playlistJson = """[{"title":"Роберт І. Говард 1 - Черепи серед Зірок.mp3","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3"},{"title":"Роберт І. Говард 2 - Правиця Долі.mp3","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-1.mp3"}]"""

    private val homepage = """
        <html><body>
        <article class="abook-item">
        <a class="image-abook" href="/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" title="Слухати аудіокнигу Клуб боягузів онлайн">
            <img class="b-showshort__cover_image" title="слухати аудіокнигу Клуб боягузів" src="https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp" alt="Аудіокнига Клуб боягузів">
        </a>
        </article>
        <a href="/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv">Андрій Кокотюха - Клуб боягузів</a>
        <a href="/uk-audio-1246-dzhek-london-zhaga-do-zhittja">Джек Лондон - Жага до життя</a>
        <a href="/uk-audio-6175-filis-doroti-dzheims-dim-tvoiei-mrii">Філіс Дороті Джеймс - Дім твоєї мрії</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `book page parses the playerjs JSON playlist into chapters`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" to bookPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv")

        assertEquals("Клуб боягузів", detail.title)
        assertEquals("Андрій Кокотюха", detail.author)
        // Spec-15 T5: og:description is the book's own blurb.
        assertEquals("Студентські страшилки обертаються справжньою грою на виживання.", detail.description)
        assertEquals(2, detail.chapters.size)
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3", detail.chapters[0].streamUrl)
        // Real chapter names from the playlist JSON, with the .mp3 stripped
        // (spec-35 #237 — same rule as the sluhay parser).
        assertEquals("Роберт І. Говард 1 - Черепи серед Зірок", detail.chapters[0].title)
        assertEquals("Роберт І. Говард 2 - Правиця Долі", detail.chapters[1].title)
    }

    @Test
    fun `playlist chapter titles strip the extension and fall back to Глава N`() = runBlocking {
        // A title without a dot passes through unchanged; blank/whitespace
        // titles fall back to «Глава N» (absent stays absent, ADR-0014).
        val json = """[{"title":"Роберт І. Говард 1 - Черепи серед Зірок.mp3","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3"},{"title":"Без розширення","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-1.mp3"},{"title":"","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-2.mp3"},{"title":"   ","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-3.mp3"}]"""
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" to bookPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to json
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv")

        assertEquals(4, detail.chapters.size)
        assertEquals("Роберт І. Говард 1 - Черепи серед Зірок", detail.chapters[0].title)
        assertEquals("Без розширення", detail.chapters[1].title)
        assertEquals("Глава 3", detail.chapters[2].title)
        assertEquals("Глава 4", detail.chapters[3].title)
    }

    @Test
    fun `book page without a playlist yields no chapters`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(mapOf("https://audiobook-mp3.com/uk-audio-1-x" to "<html><body>nope</body></html>"))
        )

        assertTrue(adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-1-x").chapters.isEmpty())
    }

    @Test
    fun `new feed parses real cyrillic title and author from the anchors`() = runBlocking {
        val adapter = AudiobookMp3Adapter(FakeFetcher(mapOf("https://audiobook-mp3.com/uk" to homepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(3, books.size)
        assertEquals("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv", books[0].url)
        assertEquals("audiobookmp3", books[0].sourceId)
        // «Автор - Назва» in real Cyrillic, no page fetch needed.
        assertEquals("Клуб боягузів", books[0].title)
        assertEquals("Андрій Кокотюха", books[0].author)
        assertEquals(
            "https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp",
            books[0].coverImageUrl
        )
        // Entries without a cover tile keep a null cover.
        assertEquals("Жага до життя", books[1].title)
        assertEquals("Джек Лондон", books[1].author)
        assertEquals(null, books[1].coverImageUrl)
        assertEquals("Дім твоєї мрії", books[2].title)
    }

    // Spec-15 T1: catalogue enumeration walks the /uk homepage's genre links
    // and parses each genre page (same tile markup as the feed).
    private val homeWithGenres = """
        <html><body>
        <a href="/uk-genre-1-ukrayinska-literatura">Українська література</a>
        <a href="/uk-genre-3-roman">Роман</a>
        <a href="/uk-genre-13-fantastika">Фантастика</a>
        </body></html>
    """.trimIndent()

    private val ukrLitPage = """
        <html><body>
        <article class="abook-item">
        <a class="image-abook" href="/uk-audio-7001-taras-shevchenko-kobzar" title="Слухати аудіокнигу Кобзар онлайн">
            <img class="b-showshort__cover_image" src="https://cdn.audiobook-mp3.com/audiobooks/uk/7/0/0/1/kobzar.webp" alt="Аудіокнига Кобзар">
        </a>
        </article>
        <a href="/uk-audio-7001-taras-shevchenko-kobzar">Тарас Шевченко - Кобзар</a>
        <a href="/uk-audio-7002-lesja-ukrajinka-lisova-pisnja">Леся Українка - Лісова пісня</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `catalogue enumerates genre pages into books with covers`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk" to homeWithGenres,
                    "https://audiobook-mp3.com/uk-genre-1-ukrayinska-literatura" to ukrLitPage,
                    "https://audiobook-mp3.com/uk-genre-3-roman" to ""
                ),
                fallback = "<html><body></body></html>"
            )
        )

        val books = adapter.fetchCatalog(limit = 40)

        assertEquals(2, books.size)
        assertEquals("Кобзар", books[0].title)
        assertEquals("Тарас Шевченко", books[0].author)
        assertEquals("https://audiobook-mp3.com/uk-audio-7001-taras-shevchenko-kobzar", books[0].url)
        assertEquals(
            "https://cdn.audiobook-mp3.com/audiobooks/uk/7/0/0/1/kobzar.webp",
            books[0].coverImageUrl
        )
        assertEquals("Лісова пісня", books[1].title)
        assertEquals("Леся Українка", books[1].author)
    }
}
