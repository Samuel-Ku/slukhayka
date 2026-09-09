package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-47 T2 AudiobookCoUaAdapter. Markup mirrors real
 * audiobook.co.ua pages/JSON captured live during the T1 spike (committed
 * under `docs/wayfinder/research/fixtures/audiobookcoua/`). No network.
 */
class AudiobookCoUaAdapterTest {

    // The Playerjs init carries the playlist title \u-escaped (live shape);
    // built as a normal string so the bytes are exactly the page's.
    private val pidKupolomEscapedTitle =
        "\\u041f\\u0456\\u0434 \\u043a\\u0443\\u043f\\u043e\\u043b\\u043e\\u043c" +
            " - \\u0421\\u0442\\u0456\\u0432\\u0435\\u043d \\u041a\\u0456\\u043d\\u0433"

    // book-pid-kupolom-stiven-king.html (trimmed): og:* + the Playerjs init
    // with the JSON-escaped playlist URL.
    private val bookPage = """
        <!-- SPIKE FIXTURE (spec-47 T1): live capture, trimmed to parser-relevant parts. -->
        <title>Аудиокнига Під куполом - Стівен Кінг скачать, слушать онлайн - Аудиокниги на украинском языке</title>
        <meta property="og:locale" content="ru_RU"
        <meta property="og:type" content="article"
        <meta property="og:title" content="Аудиокнига Під куполом - Стівен Кінг скачать, слушать онлайн - Аудиокниги на украинском языке"
        <meta property="og:description" content="Скачать, слушать онлайн аудиокнигу Під куполом - Стівен Кінг на украинском языке бесплатно и без регистрации"
        <meta property="og:url" content="https://audiobook.co.ua/pid-kupolom-stiven-king/"
        <meta property="og:site_name" content="Аудиокниги на украинском языке"
        <meta property="og:image" content="https://audiobook.co.ua/wp-content/uploads/2023/12/pid-kupolom-stiven-king.jpg"
        <meta property="og:image:width" content="300"
        <meta property="og:image:height" content="300"

        <!-- [TRIMMED: css/js/body] -->
        <script>new Playerjs({"file":"https:\/\/audiobook.co.ua\/playlist\/pid-kupolom-stiven-king.txt","title":"$pidKupolomEscapedTitle","id":"playerjs10689"});} if(window["Playerjs"]){PlayerjsAsync();}</script>
    """.trimIndent()

    // book-nuzhnye-veshhi-stiven-king.html (trimmed): second book, same shape.
    private val bookPageNuzhnye = """
        <!-- SPIKE FIXTURE (spec-47 T1): live capture, trimmed. -->
        <title>Аудиокнига Нужные вещи - Стивен Кинг скачать, слушать онлайн - Аудиокниги на украинском языке</title>
        <meta property="og:title" content="Аудиокнига Нужные вещи - Стивен Кинг скачать, слушать онлайн - Аудиокниги на украинском языке"
        <meta property="og:url" content="https://audiobook.co.ua/nuzhnye-veshhi-stiven-king/"
        <meta property="og:image" content="https://audiobook.co.ua/wp-content/uploads/2023/08/nuzhnye-veshhi-stiven-king.jpg"
        <!-- [TRIMMED: css/js/body] -->
        <script>new Playerjs({"file":"https:\/\/audiobook.co.ua\/playlist\/nuzhnye-veshhi-stiven-king.txt","id":"playerjs18331"});} if(window["Playerjs"]){PlayerjsAsync();}</script>
    """.trimIndent()

    // playlist-pid-kupolom-stiven-king.txt: {title, file} JSON, first 10 of
    // 106 real tracks. Titles carry leading spaces and repeat the book name.
    private val playlistJson = """
        [{"title": " 1   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/001.mp3"}, {"title": " 2   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/002.mp3"}, {"title": " 3   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/003.mp3"}, {"title": " 4   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/004.mp3"}, {"title": " 5   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/005.mp3"}, {"title": " 6   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/006.mp3"}, {"title": " 7   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/007.mp3"}, {"title": " 8   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/008.mp3"}, {"title": " 9   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/009.mp3"}, {"title": " 10   Під куполом - Стівен Кінг", "file": "https://archive.org/download/008_20231203_202312/010.mp3"}]
    """.trimIndent()

    // novinki-ozvuchivaniya.html (trimmed): the post-grid search form (proof
    // it never becomes a card) + two real cards «Назва - Автор» + one card
    // without the separator (a collection — author stays empty, not invented).
    private val novinkiHtml = """
        <!-- SPIKE FIXTURE (spec-47 T1): live capture, trimmed. -->
        <title>Новинки озвучивания - Аудиокниги на украинском языке</title>
        <div id="post-grid-lazy-2212" class="post-grid-lazy"><img decoding="async" alt="" src="" /></div>
        <div data-options='{&quot;id&quot;:&quot;2212&quot;}' id="post-grid-2212" class="post-grid grid">
          <div class="post-grid-search ajax">
            <form grid_id="2212" action="https://audiobook.co.ua/novinki-ozvuchivaniya/" method="get">
              <input grid_id="2212" class="search" type="text" name="keyword" placeholder="Поиск по сайту" value="">
              <input type="hidden" id="_wpnonce" name="_wpnonce" value="6e3bfeb892" />
            </form>
          </div>
          <div class="grid-items">
            <div class="item item-16262 skin flat even 0 ">
              <div class="layer-wrapper layout-861">
                <div class="layer-media element_1587187627902">
                  <a target="_self" href="https://audiobook.co.ua/golem-gustav-majrink/"><img decoding="async" width="300" height="300" src="https://audiobook.co.ua/wp-content/uploads/2026/09/golem-gustav-majrink.jpg" class="attachment-large size-large wp-post-image" alt="Ґолем - Ґустав Майрінк" /></a>
                </div>
                <div class="layer-content element_1587187714568">
                  <div class="element element_1587187729822  excerpt ">
                    Роман Ґустава Майрінка «Ґолем»…
                    <a target="_parent" href="https://audiobook.co.ua/golem-gustav-majrink/"><button class="more-link">Перейти...</button></a>
                  </div>
                </div>
                <div class="element element_1587187895341  title ">
                  <a target="_parent" href="https://audiobook.co.ua/golem-gustav-majrink/">Ґолем - Ґустав Майрінк</a>
                </div>
              </div>
            </div><div class="item item-16079 skin flat odd 1 ">
              <div class="layer-wrapper layout-861">
                <div class="layer-media element_1587187627902">
                  <a target="_self" href="https://audiobook.co.ua/krov-i-pisok-visente-blasko-ibanyes/"><img decoding="async" width="300" height="300" src="https://audiobook.co.ua/wp-content/uploads/2026/08/krov-i-pisok-visente-blasko-ibanyes.jpg" class="attachment-large size-large wp-post-image" alt="Кров і пісок - Вісенте Бласко Ібаньєс" /></a>
                </div>
                <div class="layer-content element_1587187714568">
                  <div class="element element_1587187729822  excerpt ">
                    "Кров і пісок" Вісенте Бласко Ібаньєса - трогательная история…
                  </div>
                </div>
                <div class="element element_1587187895341  title ">
                  <a target="_parent" href="https://audiobook.co.ua/krov-i-pisok-visente-blasko-ibanyes/">Кров і пісок - Вісенте Бласко Ібаньєс</a>
                </div>
              </div>
            </div><div class="item item-99999 skin flat even 2 ">
              <div class="layer-wrapper layout-861">
                <div class="element element_1587187895341  title ">
                  <a target="_parent" href="https://audiobook.co.ua/zbirka-kazok/">Збірка казок без автора</a>
                </div>
              </div>
            </div>
          </div>
        </div>
    """.trimIndent()

    // post-sitemap-head.xml (trimmed): locs only — the fetchCatalog entry.
    private val sitemapXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset>
        <loc>https://audiobook.co.ua/1984-dzhordzh-oruell/</loc>
        <loc>https://audiobook.co.ua/igra-dzheralda-stiven-king/</loc>
        <loc>https://audiobook.co.ua/nuzhnye-veshhi-stiven-king/</loc>
        <!-- SPIKE FIXTURE: first locs of post-sitemap.xml -->
        </urlset>
    """.trimIndent()

    private val bookUrl = "https://audiobook.co.ua/pid-kupolom-stiven-king/"
    private val playlistUrl = "https://audiobook.co.ua/playlist/pid-kupolom-stiven-king.txt"

    @Test
    fun `search is an honest empty list`() = runBlocking {
        // T1 spike: the keyword form does not filter server-side — the seam
        // contract's honest refusal, never a fake results page.
        val adapter = AudiobookCoUaAdapter(FakeFetcher(emptyMap()))

        assertTrue(adapter.search("Стівен Кінг").isEmpty())
        assertTrue(adapter.search("").isEmpty())
    }

    @Test
    fun `ukrainian content language and direct access mode`() {
        val adapter = AudiobookCoUaAdapter(FakeFetcher(emptyMap()))

        assertEquals("uk", adapter.contentLanguage)
        assertEquals(SourceAccessMode.DIRECT, SourceAccessPolicy.modeFor("audiobookcoua"))
    }

    @Test
    fun `novinki grid parses Nazva-Author cards with covers and no invented authors`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf("https://audiobook.co.ua/novinki-ozvuchivaniya/" to novinkiHtml))
        )

        val books = adapter.fetchNew(limit = 10)

        assertEquals(3, books.size)
        assertEquals("Ґолем", books[0].title)
        assertEquals("Ґустав Майрінк", books[0].author)
        assertEquals("https://audiobook.co.ua/golem-gustav-majrink/", books[0].url)
        assertEquals(
            "https://audiobook.co.ua/wp-content/uploads/2026/09/golem-gustav-majrink.jpg",
            books[0].coverImageUrl
        )
        assertEquals("audiobookcoua", books[0].sourceId)
        assertEquals("Кров і пісок", books[1].title)
        assertEquals("Вісенте Бласко Ібаньєс", books[1].author)
    }

    @Test
    fun `novinki card without the separator keeps the author empty`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf("https://audiobook.co.ua/novinki-ozvuchivaniya/" to novinkiHtml))
        )

        val books = adapter.fetchNew(limit = 10)

        // A collection card («Збірка казок без автора») never gets a fake
        // author — blank, so the Work-level merge key cannot form.
        assertEquals("Збірка казок без автора", books[2].title)
        assertEquals("", books[2].author)
    }

    @Test
    fun `fetchNew respects the limit`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf("https://audiobook.co.ua/novinki-ozvuchivaniya/" to novinkiHtml))
        )

        assertEquals(1, adapter.fetchNew(limit = 1).size)
    }

    @Test
    fun `book page follows the Playerjs init to the playlist chapters`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to playlistJson))
        )

        val detail = adapter.fetchBookPage(bookUrl)

        assertEquals("Під куполом", detail.title)
        assertEquals("Стівен Кінг", detail.author)
        assertEquals(
            "https://audiobook.co.ua/wp-content/uploads/2023/12/pid-kupolom-stiven-king.jpg",
            detail.coverImageUrl
        )
        assertEquals(10, detail.chapters.size)
        // Leading spaces of the live track titles are trimmed; the title text
        // itself is kept as the source renders it (no renames).
        assertEquals("1   Під куполом - Стівен Кінг", detail.chapters[0].title)
        assertEquals(
            "https://archive.org/download/008_20231203_202312/001.mp3",
            detail.chapters[0].streamUrl
        )
        assertEquals(
            "https://archive.org/download/008_20231203_202312/010.mp3",
            detail.chapters[9].streamUrl
        )
    }

    @Test
    fun `chapter without a title falls back to Rozdil N`() = runBlocking {
        val sparseJson = """
            [{"file": "https://archive.org/x/001.mp3"}, {"title": "Частина", "file": "https://archive.org/x/002.mp3"}]
        """.trimIndent()
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to sparseJson))
        )

        val chapters = adapter.fetchBookPage(bookUrl).chapters

        assertEquals(2, chapters.size)
        assertEquals("Розділ 1", chapters[0].title)
        assertEquals("Частина", chapters[1].title)
    }

    @Test
    fun `second book page with the same escaped-url shape parses`() = runBlocking {
        val nuzhnyePlaylistUrl = "https://audiobook.co.ua/playlist/nuzhnye-veshhi-stiven-king.txt"
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook.co.ua/nuzhnye-veshhi-stiven-king/" to bookPageNuzhnye,
                    nuzhnyePlaylistUrl to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook.co.ua/nuzhnye-veshhi-stiven-king/")

        // The live page renders this author in Russian («Стивен Кинг») — the
        // parser keeps what the page says, never rewrites.
        assertEquals("Нужные вещи", detail.title)
        assertEquals("Стивен Кинг", detail.author)
        assertEquals(10, detail.chapters.size)
    }

    @Test
    fun `page without a Playerjs init keeps metadata with no chapters`() = runBlocking {
        // A page of the same WordPress family whose player script is absent
        // (or not yet attached): metadata survives, chapters honestly empty.
        val playerlessPage = """
            <title>Аудиокнига Кобзар - Тарас Шевченко скачать, слушать онлайн - Аудиокниги на украинском языке</title>
            <meta property="og:title" content="Аудиокнига Кобзар - Тарас Шевченко скачать, слушать онлайн - Аудиокниги на украинском языке"
            <meta property="og:url" content="https://audiobook.co.ua/kobzar-taras-shevchenko/"
            <meta property="og:image" content="https://audiobook.co.ua/wp-content/uploads/2020/01/kobzar.jpg"
        """.trimIndent()
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf("https://audiobook.co.ua/kobzar-taras-shevchenko/" to playerlessPage))
        )

        val detail = adapter.fetchBookPage("https://audiobook.co.ua/kobzar-taras-shevchenko/")

        assertEquals("Кобзар", detail.title)
        assertEquals("Тарас Шевченко", detail.author)
        assertTrue(detail.chapters.isEmpty())
        // Measured absence (T1 spike): the page carries no real blurb — the
        // og:description is site boilerplate and stays unused.
        assertEquals("", detail.description)
        assertNull(detail.series)
        assertNull(detail.totalDurationSeconds)
    }

    @Test
    fun `unreachable page yields the empty honest detail`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(FakeFetcher(emptyMap()))

        val detail = adapter.fetchBookPage("https://audiobook.co.ua/missing-book/")

        assertEquals("", detail.title)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `catalog walks post-sitemaps and parses real pages up to the limit`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook.co.ua/post-sitemap.xml" to sitemapXml,
                    // The other two sitemaps return empty (fetcher fallback) —
                    // the walk must survive and keep the locs it got.
                    "https://audiobook.co.ua/1984-dzhordzh-oruell/" to bookPageNuzhnye,
                    "https://audiobook.co.ua/igra-dzheralda-stiven-king/" to bookPage,
                    "https://audiobook.co.ua/nuzhnye-veshhi-stiven-king/" to bookPageNuzhnye,
                    "https://audiobook.co.ua/playlist/nuzhnye-veshhi-stiven-king.txt" to playlistJson,
                    "https://audiobook.co.ua/playlist/pid-kupolom-stiven-king.txt" to playlistJson
                )
            )
        )

        val books = adapter.fetchCatalog(limit = 3)

        assertEquals(3, books.size)
        // The URL comes from the sitemap loc, the metadata from that page.
        assertEquals("https://audiobook.co.ua/1984-dzhordzh-oruell/", books[0].url)
        assertEquals("Нужные вещи", books[0].title)
        assertEquals("Стивен Кинг", books[0].author)
        assertEquals("audiobookcoua", books[0].sourceId)
        assertEquals(2, adapter.fetchCatalog(limit = 2).size)
    }

    @Test
    fun `catalog with zero limit returns nothing`() = runBlocking {
        val adapter = AudiobookCoUaAdapter(
            FakeFetcher(mapOf("https://audiobook.co.ua/post-sitemap.xml" to sitemapXml))
        )

        assertTrue(adapter.fetchCatalog(limit = 0).isEmpty())
    }

    @Test
    fun `bookId keeps the sitemap slug without the trailing slash`() {
        val adapter = AudiobookCoUaAdapter(FakeFetcher(emptyMap()))

        assertEquals(
            "audiobookcoua-pid-kupolom-stiven-king",
            adapter.bookId("https://audiobook.co.ua/pid-kupolom-stiven-king/")
        )
        assertEquals(
            "audiobookcoua-x",
            adapter.bookId("https://audiobook.co.ua/x/?p=1")
        )
    }
}
