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

    // --- #1486: search through the ALLOWED tag taxonomy ----------------------

    private val tagSitemapUrl = "https://audiobook.co.ua/post_tag-sitemap.xml"
    private val kingTag = "https://audiobook.co.ua/tag/king-stiven/"
    private val kingDejliTag = "https://audiobook.co.ua/tag/king-dejli/"
    private val shevchenkoTag = "https://audiobook.co.ua/tag/shevchenko-taras/"

    private fun tagsSitemap(vararg urls: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
        urls.forEach { append("<url><loc>").append(it).append("</loc></url>") }
        // A non-tag loc must never enter the tag index.
        append("""<url><loc>https://audiobook.co.ua/post-sitemap.xml</loc></url></urlset>""")
    }

    /**
     * One tag listing as the list theme renders it: one `<article id="post-N">`
     * per book, its title anchor and the WordPress cover. Titles keep the raw
     * `&#8212;` entity on purpose — the live pages carry it in place of «—».
     */
    private fun tagPage(
        tagUrl: String,
        vararg books: Pair<String, String>,
        nextPages: List<Int> = emptyList()
    ): String = buildString {
        books.forEach { (slug, rawTitle) ->
            append("""<article id="post-${slug.length}" class="bam-entry clearfix list-entry">""")
            append("""<div class="post-thumbnail"><a href="https://audiobook.co.ua/$slug/">""")
            append("""<img src="https://audiobook.co.ua/wp-content/uploads/2025/12/$slug.jpg" alt="$rawTitle"></a></div>""")
            append("""<header class="entry-header"><h2 class="entry-title">""")
            append("""<a href="https://audiobook.co.ua/$slug/" rel="bookmark">$rawTitle</a></h2></header>""")
            append("</article>")
        }
        nextPages.forEach { page ->
            append("""<a href="${tagUrl}page/$page/">$page</a>""")
        }
    }

    @Test
    fun `search finds an author through the allowed tag sitemap`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                tagSitemapUrl to tagsSitemap(kingTag, kingDejliTag, shevchenkoTag),
                kingTag to tagPage(
                    kingTag,
                    "doktor-son-stiven-king" to "Доктор Сон &#8212; Стівен Кінг",
                    "pid-kupolom-stiven-king" to "Під куполом &#8212; Стівен Кінг"
                ),
                kingDejliTag to tagPage(kingDejliTag, "deiaki-knyhy-king-oven" to "Деякі книги &#8212; Кінг Овен"),
                shevchenkoTag to tagPage(shevchenkoTag, "kobzar" to "Кобзар &#8212; Тарас Шевченко")
            )
        )
        val adapter = AudiobookCoUaAdapter(fetcher = fetcher, pauseMillis = {})

        val books = adapter.search("кінг")

        assertEquals(
            setOf("Доктор Сон", "Під куполом", "Деякі книги"),
            books.map { it.title }.toSet()
        )
        assertEquals("Стівен Кінг", books.first { it.title == "Доктор Сон" }.author)
        assertEquals("Кінг Овен", books.first { it.title == "Деякі книги" }.author)
        assertEquals(
            "https://audiobook.co.ua/wp-content/uploads/2025/12/doktor-son-stiven-king.jpg",
            books.first { it.title == "Доктор Сон" }.coverImageUrl
        )
        assertTrue("тег автора відкрито", fetcher.requestedUrls.contains(kingTag))
        assertTrue("другий тег автора відкрито", fetcher.requestedUrls.contains(kingDejliTag))
        assertTrue("сторонній тег не чіпали", !fetcher.requestedUrls.contains(shevchenkoTag))
    }

    @Test
    fun `a Cyrillic query meets a transliterated slug through the shared variant set`() = runBlocking {
        // «шевченко» ↔ `shevchenko-taras`: no new transliteration table, the
        // repository's SlugMatch.variants does the work.
        val fetcher = FakeFetcher(
            mapOf(
                tagSitemapUrl to tagsSitemap(shevchenkoTag),
                shevchenkoTag to tagPage(shevchenkoTag, "kobzar" to "Кобзар &#8212; Тарас Шевченко")
            )
        )
        val adapter = AudiobookCoUaAdapter(fetcher = fetcher, pauseMillis = {})

        assertEquals(listOf("Кобзар"), adapter.search("шевченко").map { it.title })
    }

    @Test
    fun `a query no tag matches opens no page`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(tagSitemapUrl to tagsSitemap(kingTag)))
        val adapter = AudiobookCoUaAdapter(fetcher = fetcher, pauseMillis = {})

        assertTrue(adapter.search("коваль").isEmpty())
        assertEquals(listOf(tagSitemapUrl), fetcher.requestedUrls.toList())
    }

    @Test
    fun `the robots-disallowed keyword search is never used and an empty sitemap is an honest miss`() = runBlocking {
        // robots.txt disallows `*?s=`, `/search/` and `/?`; discovery goes
        // through the allowed tag sitemap instead. An EMPTY fetcher map means
        // any attempt at the old keyword door would fail loudly.
        val adapter = AudiobookCoUaAdapter(FakeFetcher(emptyMap()))

        assertTrue(adapter.search("Стівен Кінг").isEmpty())
        assertTrue(adapter.search("").isEmpty())
    }

    @Test
    fun `one query spends at most the configured tag-page requests`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                tagSitemapUrl to tagsSitemap(kingTag, kingDejliTag),
                kingTag to tagPage(
                    kingTag,
                    "doktor-son-stiven-king" to "Доктор Сон &#8212; Стівен Кінг"
                ),
                kingDejliTag to tagPage(kingDejliTag, "deiaki-knyhy-king-oven" to "Деякі книги &#8212; Кінг Овен")
            )
        )
        val adapter = AudiobookCoUaAdapter(fetcher = fetcher, pauseMillis = {}, searchPageLimit = 1)

        val books = adapter.search("кінг")

        assertEquals(1, books.size)
        assertEquals("sitemap + одна сторінка", 2, fetcher.requestedUrls.size)
    }

    @Test
    fun `search follows a tag's next page with a paced continuation`() = runBlocking {
        val page2 = kingTag + "page/2/"
        val fetcher = FakeFetcher(
            mapOf(
                tagSitemapUrl to tagsSitemap(kingTag),
                kingTag to tagPage(
                    kingTag,
                    "doktor-son-stiven-king" to "Доктор Сон &#8212; Стівен Кінг",
                    nextPages = listOf(2)
                ),
                page2 to tagPage(page2, "pid-kupolom-stiven-king" to "Під куполом &#8212; Стівен Кінг")
            )
        )
        val pauses = mutableListOf<Long>()
        val adapter = AudiobookCoUaAdapter(
            fetcher = fetcher,
            pauseMillis = { pauses += it },
            searchPageLimit = 2
        )

        val books = adapter.search("кінг")

        assertEquals(setOf("Доктор Сон", "Під куполом"), books.map { it.title }.toSet())
        assertTrue("другу сторінку тега відкрито", fetcher.requestedUrls.contains(page2))
        assertTrue("продовження йде з ритмічною паузою", pauses.isNotEmpty())
    }

    @Test
    fun `the tag index is read once while the catalogue TTL holds`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                tagSitemapUrl to tagsSitemap(kingTag),
                kingTag to tagPage(kingTag, "doktor-son-stiven-king" to "Доктор Сон &#8212; Стівен Кінг")
            )
        )
        var clock = 1_000_000L
        val adapter = AudiobookCoUaAdapter(fetcher = fetcher, pauseMillis = {}, now = { clock })

        adapter.search("кінг")
        clock += 60_000L
        adapter.search("шевченко")

        assertEquals(1, fetcher.requestedUrls.count { it == tagSitemapUrl })
    }

    @Test
    fun `a future stamp never freezes the tag index`() = runBlocking {
        // #622 convention: an inverted stamp is stale, never trusted until the
        // real clock catches up.
        val fetcher = FakeFetcher(
            mapOf(
                tagSitemapUrl to tagsSitemap(kingTag),
                kingTag to tagPage(kingTag, "doktor-son-stiven-king" to "Доктор Сон &#8212; Стівен Кінг")
            )
        )
        var clock = 9_000_000L
        val adapter = AudiobookCoUaAdapter(fetcher = fetcher, pauseMillis = {}, now = { clock })

        adapter.search("кінг")
        clock = 1_000L
        adapter.search("кінг")

        assertEquals(2, fetcher.requestedUrls.count { it == tagSitemapUrl })
    }

}
