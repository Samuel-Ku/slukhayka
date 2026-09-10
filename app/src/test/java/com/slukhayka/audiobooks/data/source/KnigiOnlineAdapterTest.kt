package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-50 T2 KnigiOnlineAdapter. Markup mirrors real
 * knigi-online.com.ua pages captured live in the T1 spike (committed under
 * `docs/wayfinder/research/fixtures/knigionline/`): WordPress post-cards,
 * the AudioIgniter `data-tracks-url` block and the playlist JSON. No network.
 */
class KnigiOnlineAdapterTest {

    private val bookUrl = "https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/"
    private val searchUrl =
        "https://knigi-online.com.ua/?s=" + java.net.URLEncoder.encode("нестайко", "UTF-8")
    private val newUrl = "https://knigi-online.com.ua/audioknyhy/"
    private val sitemapUrl = "https://knigi-online.com.ua/post-sitemap.xml"
    private val playlistUrl = "https://knigi-online.com.ua/?audioigniter_playlist_id=531"

    // /audioknyhy/ section (trimmed): two audiobook cards in the site's own
    // order + one ebook card (no /audioknyha-/ prefix) that never enters.
    private val newPage = """
        <!-- SPIKE FIXTURE (spec-50 T1): live capture, trimmed. Original: /audioknyhy/ -->
        <div class="post-card post-card--vertical post-card--thumbnail-meta">
        <div class="post-card__thumbnail"><a href="https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/"><img src="https://knigi-online.com.ua/wp-content/uploads/2024/09/Tom-Soier-329x230.jpg" alt="Пригоди Тома Соєра" /></a></div>
        <div class="post-card__title"><span><a href="https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/">«Пригоди Тома Соєра» Марк Твен</a></span></div></div>
        <div class="post-card post-card--vertical post-card--thumbnail-meta">
        <div class="post-card__thumbnail"><a href="https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/"><img src="https://knigi-online.com.ua/wp-content/uploads/2024/09/Toreadory-329x230.jpg" alt="Тореадори з Васюківки" /></a></div>
        <div class="post-card__title"><span><a href="https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/">«Тореадори з Васюківки» Всеволод Нестайко</a></span></div></div>
        <div class="post-card post-card--vertical post-card--thumbnail-meta">
        <div class="post-card__thumbnail"><a href="https://knigi-online.com.ua/neymovirni-detektyvy-knyha-1/"><img src="https://knigi-online.com.ua/wp-content/uploads/2024/09/Detektyvy-329x230.jpg" alt="Детективи" /></a></div>
        <div class="post-card__title"><span><a href="https://knigi-online.com.ua/neymovirni-detektyvy-knyha-1/">«Неймовірні детективи» Всеволод Нестайко</a></span></div></div>
    """.trimIndent()

    // Book page (trimmed): og:* metadata + the AudioIgniter tracks-url block.
    private val bookPage = """
        <!-- SPIKE FIXTURE (spec-50 T1): live capture, trimmed. Original: /audioknyha-toreadory-.../ -->
        <title>Аудіокнига «Тореадори з Васюківки» Всеволод Нестайко</title>
        <meta property="og:title" content="Аудіокнига «Тореадори з Васюківки» Всеволод Нестайко" />
        <meta property="og:description" content="Слухати онлайн повністью або скачати скорочену аудіокнигу" />
        <meta property="og:image" content="https://knigi-online.com.ua/wp-content/uploads/2024/09/Toreadory-og.jpg" />
        <div id="audioigniter-531" class="audioigniter-root " data-player-type="full" data-tracks-url="https://knigi-online.com.ua/?audioigniter_playlist_id=531" data-display-track-no="true"></div>
    """.trimIndent()

    // Playlist JSON (trimmed): the live ?audioigniter_playlist_id=531 shape,
    // two tracks of N (keys: title, subtitle, audio, cover).
    private val playlistJson = """
        [{"title":"1","subtitle":"Всеволод Нестайко","audio":"https://knigi-online.com.ua/wp-content/uploads/2024/09/01.-Toreadory-z-Vasiukivky.mp3","cover":"https://knigi-online.com.ua/wp-content/uploads/2024/09/06-Toreadory-mp3-image.jpg"},
        {"title":"2","subtitle":"Всеволод Нестайко","audio":"https://knigi-online.com.ua/wp-content/uploads/2024/09/02.-Toreadory-z-Vasiukivky.mp3","cover":"https://knigi-online.com.ua/wp-content/uploads/2024/09/06-Toreadory-mp3-image.jpg"}]
    """.trimIndent()

    private val sitemapPage = """
        <urlset>
        <url><loc>https://knigi-online.com.ua/top-knyhy-2024-roku/</loc></url>
        <url><loc>https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/</loc></url>
        <url><loc>https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/</loc></url>
        </urlset>
    """.trimIndent()

    @Test
    fun `search returns audiobook cards only - the ebook card never enters`() = runBlocking {
        val adapter = KnigiOnlineAdapter(FakeFetcher(mapOf(searchUrl to newPage)))
        val results = adapter.search("нестайко")
        assertEquals(2, results.size)
        assertEquals("«Пригоди Тома Соєра»", results[0].title)
        assertEquals("Марк Твен", results[0].author)
        assertEquals("https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/", results[0].url)
        assertTrue(results.all { it.sourceId == "knigionline" })
    }

    @Test
    fun `fetchNew parses section cards with the limit`() = runBlocking {
        val adapter = KnigiOnlineAdapter(FakeFetcher(mapOf(newUrl to newPage)))
        val results = adapter.fetchNew(limit = 1)
        assertEquals(1, results.size)
        assertEquals("«Пригоди Тома Соєра»", results[0].title)
        assertEquals("Марк Твен", results[0].author)
        assertEquals(
            "https://knigi-online.com.ua/wp-content/uploads/2024/09/Tom-Soier-329x230.jpg",
            results[0].coverImageUrl
        )
    }

    @Test
    fun `book page resolves tracks-url then playlist in order`() = runBlocking {
        val adapter = KnigiOnlineAdapter(
            FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to playlistJson))
        )
        val detail = adapter.fetchBookPage(bookUrl)
        assertEquals("«Тореадори з Васюківки»", detail.title)
        assertEquals("Всеволод Нестайко", detail.author)
        assertEquals(
            "https://knigi-online.com.ua/wp-content/uploads/2024/09/Toreadory-og.jpg",
            detail.coverImageUrl
        )
        assertEquals(2, detail.chapters.size)
        assertEquals("1", detail.chapters[0].title)
        assertEquals(
            "https://knigi-online.com.ua/wp-content/uploads/2024/09/01.-Toreadory-z-Vasiukivky.mp3",
            detail.chapters[0].streamUrl
        )
        assertEquals("2", detail.chapters[1].title)
    }

    @Test
    fun `book page without tracks-url is honestly empty`() = runBlocking {
        val adapter = KnigiOnlineAdapter(
            FakeFetcher(mapOf(bookUrl to "<title>Аудіокнига «Порожня» Хтось</title>"))
        )
        val detail = adapter.fetchBookPage(bookUrl)
        assertTrue(detail.chapters.isEmpty())
        assertEquals(bookUrl, detail.url)
    }

    @Test
    fun `broken playlist JSON is honestly empty, never a throw`() = runBlocking {
        val adapter = KnigiOnlineAdapter(
            FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to "not json {{{"))
        )
        val detail = adapter.fetchBookPage(bookUrl)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `unreachable page yields the empty honest detail`() = runBlocking {
        val adapter = KnigiOnlineAdapter(FakeFetcher(emptyMap()))
        val detail = adapter.fetchBookPage(bookUrl)
        assertTrue(detail.chapters.isEmpty())
        assertEquals("", detail.title)
    }

    @Test
    fun `catalog walks the sitemap and parses real pages - never slug rows`() = runBlocking {
        // The sitemap carries URLs only: every card is a real page parse
        // (precedent: AudiobookCoUaAdapter.fetchCatalog), bounded by limit.
        val tomPage = """
            <title>Аудіокнига «Пригоди Тома Соєра» Марк Твен</title>
            <meta property="og:title" content="Аудіокнига «Пригоди Тома Соєра» Марк Твен" />
            <meta property="og:image" content="https://knigi-online.com.ua/wp-content/uploads/2024/09/Tom-og.jpg" />
        """.trimIndent()
        val adapter = KnigiOnlineAdapter(
            FakeFetcher(
                mapOf(
                    sitemapUrl to sitemapPage,
                    "https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/" to bookPage,
                    "https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/" to tomPage,
                )
            )
        )
        val results = adapter.fetchCatalog(limit = 10)
        assertEquals(2, results.size)
        assertTrue(results.all { "/audioknyha-" in it.url })
        assertEquals("«Тореадори з Васюківки»", results[0].title)
        assertEquals("«Пригоди Тома Соєра»", results[1].title)
    }
}
