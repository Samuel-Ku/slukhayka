package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 AudiobookMp3Adapter. Markup mirrors real
 * audiobook-mp3.com/uk pages captured during the T1 spike (book #6163,
 * «Клуб боягузів» — playlist `26720.pl.txt` on the redirectto.cc CDN) and
 * the spec-35 #237 field inventory (book #6192, «Соломон Кейн»).
 */
class AudiobookMp3AdapterTest {

    // Live page shape (spec-35 #237): og:description is a TEMPLATE («Слухати
    // аудіокниги онлайн — <Назва>, безкоштовно…»), og:image is broken (double
    // prefix — the cover is only the visible abook_image), the real blurb
    // rides in the first <p> of .abook-desc, and the profile fields live in
    // the panel-info rows (Автор:/Виконавець:/Жанр:/fa-clock-o).
    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Клуб боягузів">
        <meta property="og:description" content="Слухати аудіокниги онлайн — Клуб боягузів,  безкоштовно та без реєстрації.">
        <meta property="og:image" content="https://audiobook-mp3.comhttps://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp">
        </head><body>
        <article class="abook-page">
        <img class="abook_image" title="Слухати аудіокнигу Клуб боягузів онлайн" src="https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp" alt="Аудіокнига Клуб боягузів">
        <div class="panel-info">
            <div class="panel-item">
                <i class="fa fa-user"></i> <span>Автор:</span>
                <a rel="author" href="/uk-avtor-6163-andrij-kokotjuha">Андрій Кокотюха</a>
            </div>
            <div class="panel-item">
                <i class="fa fa-microphone"></i> <span>Виконавець:</span>
                <a rel="performer" href="/vikonavec-2211-oleksandr-tkachenko">Олександр Ткаченко</a>
            </div>
            <div class="panel-item">
                <i class="fa fa-cog" aria-hidden="true"></i> <span>Жанр:</span>
                <a href="/uk-genre-5-dytectyvy">Детектив</a>, <a href="/uk-genre-9-mistika">Містика</a>
            </div>
            <div class="panel-item">
                <i class="fa fa-clock-o"></i> 06:12:33
            </div>
        </div>
        <div class="abook-desc">
            <h2>Клуб боягузів — резюме книги</h2>
            <div class="fullentry_info book">Клуб боягузів - опис та короткий зміст аудіокниги.</div>
            <p>Студентські страшилки обертаються справжньою грою на виживання.</p>
        </div>
        <script src="/js/playerjs-ua.js?v=1.1"></script>
        <script>var player = new Playerjs({file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt"});</script>
        </article>
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

    // Live card shape (spec-35 #237): the narrator (a-info-item fa-microphone),
    // the duration (a-info-item fa-clock-o) and the genre links (abook-genre)
    // ride in the same <article class="abook-item"> block as the cover and the
    // «Автор - Назва» title anchor.
    private val richHomepage = """
        <html><body>
        <article class="abook-item">
        <a class="image-abook" href="/uk-audio-6192-robert-govard-solomon-kejn" title="Слухати аудіокнигу Соломон Кейн онлайн">
            <img class="b-showshort__cover_image" title="слухати аудіокнигу Соломон Кейн" src="https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/9/2/robert-govard-solomon-kejn.webp" alt="Аудіокнига Соломон Кейн">
        </a>
        <header class="abook-item-header">
            <h2 class="abook-title">
                <a href="/uk-audio-6192-robert-govard-solomon-kejn" title="Слухати аудіокнигу Соломон Кейн онлайн">Роберт Говард - Соломон Кейн</a>
            </h2>
            <div class="abook-info">
                <div class="abook-genre">
                    <a href="/uk-genre-47-svitova-literatura">Світова література</a>, <a href="/uk-genre-15-prigodi">Пригоди</a>,
                </div>
            </div>
        </header>
        <div class="abook-content">Соломон Кейн — суворий пуританин, високий чоловік у чорному плащі та крислатому капелюсі.</div>
        <div class="content-abook-info">
            <div class="a-info-item">
                <i class="fa fa-microphone"></i>
                <a rel="performer" href="/vikonavec-1058-kostjantin-sharkov">Костянтин Шарков</a>
            </div>
            <div class="a-info-item"><i class="fa fa-clock-o"></i> 09:53:26</div>
        </div>
        </article>
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
        // The real blurb from .abook-desc — never the og:description template.
        assertEquals("Студентські страшилки обертаються справжньою грою на виживання.", detail.description)
        assertEquals(2, detail.chapters.size)
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3", detail.chapters[0].streamUrl)
        // Real chapter names from the playlist JSON, with the .mp3 stripped
        // (spec-35 #237 — same rule as the sluhay parser).
        assertEquals("Роберт І. Говард 1 - Черепи серед Зірок", detail.chapters[0].title)
        assertEquals("Роберт І. Говард 2 - Правиця Долі", detail.chapters[1].title)
    }

    // Spec-35 T4 — the page's profile fields are preserved, per field.
    @Test
    fun `book page preserves cover narrator duration and genres`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" to bookPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv")

        // Cover is the visible abook_image, NOT the broken og:image (double
        // prefix — the fixture carries one and it must be ignored).
        assertEquals(
            "https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp",
            detail.coverImageUrl
        )
        assertEquals("Олександр Ткаченко", detail.narrator)
        assertEquals(6 * 3600L + 12 * 60L + 33L, detail.totalDurationSeconds)
        assertEquals(listOf("Детектив", "Містика"), detail.genres)
        // Negative findings (#237): no series/cycle and no rating on the page —
        // never fabricated (ADR-0014).
        assertNull(detail.series)
        assertNull(detail.rating)
    }

    @Test
    fun `book page description is the visible blurb - never the og template`() = runBlocking {
        // The fixture's og:description IS the site-wide template; the adapter
        // must take the real blurb from .abook-desc instead (spec-35 #237).
        assertTrue(bookPage.contains("Слухати аудіокниги онлайн — Клуб боягузів"))
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" to bookPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv")

        assertEquals("Студентські страшилки обертаються справжньою грою на виживання.", detail.description)
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

    // Spec-35 T4 negative test: a page without the narrator/duration/genre
    // panel rows keeps those fields absent (ADR-0014).
    @Test
    fun `book page without narrator duration or genres keeps them empty`() = runBlocking {
        val minimalPage = """
            <html><head>
            <meta property="og:title" content="Клуб боягузів">
            </head><body>
            <p>Автор: <a href="/uk-avtor-6163-andrij-kokotjuha">Андрій Кокотюха</a>.</p>
            <script>var player = new Playerjs({file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt"});</script>
            </body></html>
        """.trimIndent()
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" to minimalPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to "[]"
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv")

        assertEquals("Андрій Кокотюха", detail.author)
        assertEquals("", detail.narrator)
        assertNull(detail.totalDurationSeconds)
        assertTrue(detail.genres.isEmpty())
        assertEquals("", detail.description)
        assertNull(detail.coverImageUrl)
        assertNull(detail.series)
        assertNull(detail.rating)
    }

    private val mistoPage = """
        <html><head>
        <meta property="og:title" content="Валер’ян Підмогильний — Місто слухати онлайн аудіокнигу безкоштовно audiobook-mp3.com/uk">
        <meta property="og:description" content="Слухати аудіокниги онлайн — Валер’ян Підмогильний — Місто.">
        <meta property="og:image" content="https://audiobook-mp3.comhttps://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg">
        </head><body>
        <p>Автор: <a href="/uk-avtor-794-valerjan-pidmogilnij">Валер’ян Підмогильний</a>.</p>
        <img class="abook_image" title="Слухати онлайн аудіокнигу Валер’ян Підмогильний — Місто" src="https://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg" alt="Аудіокнига Валер’ян Підмогильний — Місто">
        <script>var player = new Playerjs({file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt"});</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `book page splits author and title from og-title and parses the real cover`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-794-valerjan-pidmogilnij-misto" to mistoPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-794-valerjan-pidmogilnij-misto")

        // The adapter strips the author + the site-URL tail; the generic
        // «слухати онлайн аудіокнигу безкоштовно» suffix is scrubbed later
        // by MetadataAssertions.normalizeTitle (tested separately).
        assertEquals("Місто слухати онлайн аудіокнигу безкоштовно", detail.title)
        assertEquals("Валер’ян Підмогильний", detail.author)
        // og:image is malformed (two URLs); the real cover is the abook_image.
        assertEquals(
            "https://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg",
            detail.coverImageUrl
        )
    }

    private val emDashFeed = """
        <html><body>
        <a class="image-abook" href="/uk-audio-794-valerjan-pidmogilnij-misto"><img class="b-showshort__cover_image" src="https://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg"></a>
        <a href="/uk-audio-794-valerjan-pidmogilnij-misto">"Валер’ян Підмогильний — Місто"</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `feed splits an em-dash quoted anchor into author and title`() = runBlocking {
        val adapter = AudiobookMp3Adapter(FakeFetcher(mapOf("https://audiobook-mp3.com/uk" to emDashFeed)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(1, books.size)
        assertEquals("Місто", books[0].title)
        assertEquals("Валер’ян Підмогильний", books[0].author)
        assertEquals(
            "https://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg",
            books[0].coverImageUrl
        )
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
        // Negative findings on the listing: no narrator/duration/genre markup
        // in these cards → the fields stay empty, never fabricated.
        assertEquals("", books[0].narrator)
        assertEquals(0L, books[0].totalDurationSeconds)
        assertEquals("", books[0].genre)
        assertNull(books[0].seriesTitle)
    }

    // Spec-35 T4 — the listing card fields, per field.
    @Test
    fun `feed cards carry narrator duration and genre from the listing`() = runBlocking {
        val adapter = AudiobookMp3Adapter(FakeFetcher(mapOf("https://audiobook-mp3.com/uk" to richHomepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(1, books.size)
        assertEquals("Соломон Кейн", books[0].title)
        assertEquals("Роберт Говард", books[0].author)
        assertEquals("Костянтин Шарков", books[0].narrator)
        assertEquals(9 * 3600L + 53 * 60L + 26L, books[0].totalDurationSeconds)
        assertEquals("Світова література, Пригоди", books[0].genre)
        assertEquals(
            "https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/9/2/robert-govard-solomon-kejn.webp",
            books[0].coverImageUrl
        )
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
