package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        <audio src="https://web.lihtar.in.ua/audio/name/click.mp3" autoplay></audio>
        <audio id="player" class="player" src="https://web.lihtar.in.ua/audio/library/854/-dlja-ditey-slukhaty-onlayn-bojahuzdytjacha-literatura-0nmcgoa6zik-converted.mp3" autoplay onended="nextsound()"></audio>
        <audio src="https://web.lihtar.in.ua/audio/name/end.mp3" id="theend"></audio>
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

    // Spec-35 T3 — real page shape (live sample «Чарівні історії нашого
    // лісу»): the author rides in the <h4> right after the <h1> title, the
    // cover in og:image, and — when a page ever carries one — the duration in
    // a «Тривалість:» / itemprop="duration" marker (no live page does today,
    // #237 negative finding; the parser must still preserve it).
    private val richBookPage = """
        <html><head>
        <meta property="og:title" content="Чарівні історії нашого лісу">
        <meta property="og:description" content="Ольга Гура">
        <meta property="og:image" content="https://lihtar.in.ua/images/biblioteka/79/header_charivni-istorii-nashoho-lisu.png">
        </head><body>
        <h1>Чарівні історії нашого лісу</h1>
        <h4>Ольга Гура</h4>
        <p>Поетеса та письменниця Ольга Гура своїм мелодійним голосом занурює нас у таємничу атмосферу екологічної казки.</p>
        <p>Тривалість: 01:23:45</p>
        <a href="https://web.lihtar.in.ua/library/dytjacha-literatura/olha-hura-charivni-istorii-nashoho-lisu" target="_blank" class="lbutton detbtn">Слухати 'Чарівні історії нашого лісу'</a>
        </body></html>
    """.trimIndent()

    // The og:description on lihtar is the AUTHOR, never a blurb — a long name
    // must not be truncated (the old take(80) cut real names) and must never
    // be substituted as the description (ADR-0014). The <h4> is authoritative.
    private val longAuthorPage = """
        <html><head>
        <meta property="og:title" content="Стежка до серця">
        <meta property="og:description" content="Пані Олександра Петрівна Коваленко-Шевченко, заслужена діячка мистецтв України, лауреатка премії імені Лесі Українки, кавалерка ордена княгині Ольги III ступеня та володарка численних відзнак">
        <meta property="og:image" content="https://lihtar.in.ua/images/biblioteka/1/header_stezhka-do-sertsia.png">
        </head><body>
        <h1>Стежка до серця</h1>
        <h4>Олександра Коваленко</h4>
        <p>Тепла історія про добро і взаємодопомогу.</p>
        <a href="https://web.lihtar.in.ua/library/dytjacha-literatura/oleksandra-kovalenko-stezhka-do-sertsia/stezhka-do-sertsia" target="_blank" class="lbutton detbtn">Слухати 'Стежка до серця'</a>
        </body></html>
    """.trimIndent()

    private val playerPageWithAudio = """
        <html><body>
        <audio id="player" class="player" src="https://web.lihtar.in.ua/audio/library/79/charivni-istorii-nashoho-lisu-converted.mp3" autoplay onended="nextsound()"></audio>
        </body></html>
    """.trimIndent()

    private val loveBookUrl = "https://lihtar.in.ua/biblioteka/khudozhnja-literatura/i-znovu-pro-lubov"
    private val lovePlayerUrl = "https://web.lihtar.in.ua/library/khudozhnja-literatura/olena-i-tymur-lytovchenky-i-znovu-pro-lubov"
    private val loveChapters = listOf(
        "zmist" to "Зміст", "vid-avtoriv" to "Від авторів", "ponchyk" to "Пончик",
        "strazh-kit" to "Страж кіт", "sobacha-dusha" to "Собача душа",
        "ryzhyk-i-nichka" to "Рижик і Нічка", "akuratyst" to "Акуратист"
    )

    /** Real index shape from «І знову про любов», including the UI sound. */
    private fun lovePages(): Map<String, String> = buildMap {
        put(loveBookUrl, """<meta property="og:title" content="І знову про любов"><a href="$lovePlayerUrl">Слухати</a>""")
        put(lovePlayerUrl, """<audio src="https://web.lihtar.in.ua/audio/name/click.mp3" autoplay></audio>""" +
            loveChapters.mapIndexed { index, (slug, title) ->
                """<div class="button v${index + 1}" data-src="https://web.lihtar.in.ua/audio/name/${index + 1}.mp3" onclick="location.href = '$lovePlayerUrl/$slug'">$title</div>"""
            }.joinToString("\n"))
        loveChapters.forEachIndexed { index, (slug, _) ->
            put("$lovePlayerUrl/$slug", """
                <audio src="https://web.lihtar.in.ua/audio/name/click.mp3" autoplay></audio>
                <audio src="https://web.lihtar.in.ua/audio/library/${1238 + index}/track-${index + 1}.mp3" class="player" id="player"></audio>
                <audio src="https://web.lihtar.in.ua/audio/name/end.mp3" id="theend"></audio>
            """.trimIndent())
        }
    }

    @Test
    fun `I znovu pro lubov resolves all seven chapters instead of the menu click`() = runBlocking {
        val fetcher = FakeFetcher(lovePages())
        val detail = LihtarAdapter(fetcher).fetchBookPage(loveBookUrl)
        assertEquals(loveChapters.map { it.second }, detail.chapters.map { it.title })
        assertTrue(detail.chapters.all { it.streamUrl.startsWith("https://web.lihtar.in.ua/audio/library/") })
        assertEquals(listOf(loveBookUrl, lovePlayerUrl) + loveChapters.map { "$lovePlayerUrl/${it.first}" }, fetcher.requestedUrls)
    }

    @Test
    fun `a missing middle chapter never shifts later chapters forward`() = runBlocking {
        val pages = lovePages().toMutableMap().also { it["$lovePlayerUrl/ponchyk"] = "" }
        assertTrue(LihtarAdapter(FakeFetcher(pages)).fetchBookPage(loveBookUrl).chapters.isEmpty())
    }

    @Test
    fun `a menu without book audio is never a playable book`() = runBlocking {
        val pages = lovePages().toMutableMap().also {
            it[lovePlayerUrl] = """<audio src="https://web.lihtar.in.ua/audio/name/click.mp3"></audio>"""
        }
        assertTrue(LihtarAdapter(FakeFetcher(pages)).fetchBookPage(loveBookUrl).chapters.isEmpty())
    }

    @Test
    fun `navigation outside the book is ignored and duplicate chapter links resolve once`() = runBlocking {
        val pages = lovePages().toMutableMap()
        pages[lovePlayerUrl] = pages.getValue(lovePlayerUrl) + """
            <div onclick="location.href = 'https://web.lihtar.in.ua/library/khudozhnja-literatura/'">назад</div>
            <a href="$lovePlayerUrl/zmist">Зміст ще раз</a>
            <a href="https://foreign.example/library/book/chapter">Чужий сайт</a>
        """
        val fetcher = FakeFetcher(pages)
        assertEquals(7, LihtarAdapter(fetcher).fetchBookPage(loveBookUrl).chapters.size)
        assertEquals(9, fetcher.requestedUrls.size)
    }

    @Test
    fun `player attributes may be reordered single quoted and relative`() = runBlocking {
        val pages = lovePages().toMutableMap()
        pages[lovePlayerUrl] = """<audio src='/audio/library/1/book.mp3' class='player' id='player'></audio>"""
        val chapters = LihtarAdapter(FakeFetcher(pages)).fetchBookPage(loveBookUrl).chapters
        assertEquals("https://web.lihtar.in.ua/audio/library/1/book.mp3", chapters.single().streamUrl)
    }

    @Test
    fun `a foreign audio host is never accepted as a Lihtar recording`() = runBlocking {
        val pages = lovePages().toMutableMap()
        pages[lovePlayerUrl] = """<audio id="player" src="https://foreign.example/audio/library/1/book.mp3"></audio>"""
        assertTrue(LihtarAdapter(FakeFetcher(pages)).fetchBookPage(loveBookUrl).chapters.isEmpty())
    }

    @Test
    fun `an oversized menu is rejected before fetching any chapter`() = runBlocking {
        val pages = lovePages().toMutableMap()
        pages[lovePlayerUrl] = (1..101).joinToString("\n") { """<a href="$lovePlayerUrl/chapter-$it">$it</a>""" }
        val fetcher = FakeFetcher(pages)
        assertTrue(LihtarAdapter(fetcher).fetchBookPage(loveBookUrl).chapters.isEmpty())
        assertEquals(listOf(loveBookUrl, lovePlayerUrl), fetcher.requestedUrls)
    }

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
        // Spec-35 T3: the book page's og:image is the cover.
        assertEquals("https://lihtar.in.ua/images/biblioteka/85/w_bojahuz-101.jpg", detail.coverImageUrl)
        // No live lihtar page carries a duration (#237) — absent stays null.
        assertNull(detail.totalDurationSeconds)
        // og:description is the author, never substituted as a description.
        assertEquals("", detail.description)
        assertEquals(1, detail.chapters.size)
        assertEquals(
            "https://web.lihtar.in.ua/audio/library/854/-dlja-ditey-slukhaty-onlayn-bojahuzdytjacha-literatura-0nmcgoa6zik-converted.mp3",
            detail.chapters.single().streamUrl
        )
    }

    @Test
    fun `book page keeps the cover and the duration when the page carries them`() = runBlocking {
        val adapter = LihtarAdapter(
            FakeFetcher(
                mapOf(
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura/charivni-istorii-nashoho-lisu" to richBookPage,
                    "https://web.lihtar.in.ua/library/dytjacha-literatura/olha-hura-charivni-istorii-nashoho-lisu" to playerPageWithAudio
                )
            )
        )

        val detail = adapter.fetchBookPage("https://lihtar.in.ua/biblioteka/dytjacha-literatura/charivni-istorii-nashoho-lisu")

        assertEquals("Чарівні історії нашого лісу", detail.title)
        assertEquals("Ольга Гура", detail.author)
        assertEquals("https://lihtar.in.ua/images/biblioteka/79/header_charivni-istorii-nashoho-lisu.png", detail.coverImageUrl)
        assertEquals(1 * 3600L + 23 * 60L + 45L, detail.totalDurationSeconds)
        // The visible blurb exists, but lihtar's og:description is the author
        // — description stays empty by design (spec-35 T3, ADR-0014).
        assertEquals("", detail.description)
        assertEquals(1, detail.chapters.size)
    }

    @Test
    fun `author is the real h4 subtitle - a long og description is never truncated`() = runBlocking {
        val adapter = LihtarAdapter(
            FakeFetcher(
                mapOf(
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura/stezhka-do-sertsia" to longAuthorPage,
                    "https://web.lihtar.in.ua/library/dytjacha-literatura/oleksandra-kovalenko-stezhka-do-sertsia/stezhka-do-sertsia" to playerPage
                )
            )
        )

        val detail = adapter.fetchBookPage("https://lihtar.in.ua/biblioteka/dytjacha-literatura/stezhka-do-sertsia")

        // The real name from the <h4>, NOT the first 80 chars of og:description.
        assertEquals("Олександра Коваленко", detail.author)
        assertEquals("", detail.description)
        assertNull(detail.totalDurationSeconds)
    }

    @Test
    fun `duration parses from the schema dot org itemprop marker too`() = runBlocking {
        val itempropPage = richBookPage
            .replace("<p>Тривалість: 01:23:45</p>", "<meta itemprop=\"duration\" content=\"00:45:10\">")
        val adapter = LihtarAdapter(
            FakeFetcher(
                mapOf(
                    "https://lihtar.in.ua/biblioteka/dytjacha-literatura/charivni-istorii-nashoho-lisu" to itempropPage,
                    "https://web.lihtar.in.ua/library/dytjacha-literatura/olha-hura-charivni-istorii-nashoho-lisu" to playerPageWithAudio
                )
            )
        )

        val detail = adapter.fetchBookPage("https://lihtar.in.ua/biblioteka/dytjacha-literatura/charivni-istorii-nashoho-lisu")

        assertEquals(45L * 60L + 10L, detail.totalDurationSeconds)
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

    // --- #529 — one category action = one page, never a crawl --------------

    @Test
    fun `one category action is exactly one request and never follows every book link`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf("https://lihtar.in.ua/biblioteka/dytjacha-literatura" to childCategoryPage)
        )
        val adapter = LihtarAdapter(fetcher)

        val page = adapter.fetchGenrePage("/biblioteka/dytjacha-literatura")

        // ONE request: the category page itself. Enriching every card from its
        // book page would be a crawl, so the card keeps the honest slug until
        // the listener opens it.
        assertEquals("one page, one request", 1, fetcher.requestedUrls.size)
        assertEquals("https://lihtar.in.ua/biblioteka/dytjacha-literatura", fetcher.requestedUrls.single())
        assertEquals(3, page.books.size)
        assertEquals("Bojahuz", page.books[0].title)
        assertEquals("lihtar", page.books[0].sourceId)
        assertNull("a lihtar category is ONE page — no cursor exists", page.nextCursor)
    }

    @Test
    fun `a foreign path or a continuation cursor costs no request`() = runBlocking {
        val fetcher = FakeFetcher()
        val adapter = LihtarAdapter(fetcher)

        assertTrue(adapter.fetchGenrePage("/biblioteka").books.isEmpty())
        assertTrue(adapter.fetchGenrePage("https://evil.example/biblioteka/x").books.isEmpty())
        assertTrue(adapter.fetchGenrePage("/biblioteka/x/y").books.isEmpty())
        assertTrue(
            adapter.fetchGenrePage("/biblioteka/x", cursor = "/biblioteka/x/page/2").books.isEmpty()
        )
        assertEquals(0, fetcher.requestedUrls.size)
    }

    @Test
    fun `an empty category answer is an honest empty page`() = runBlocking {
        val adapter = LihtarAdapter(FakeFetcher())

        val page = adapter.fetchGenrePage("/biblioteka/khudozhnja-literatura")

        assertTrue(page.books.isEmpty())
        assertNull(page.nextCursor)
    }
}
