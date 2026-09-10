package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-47 T3 ChytayloAdapter. Markup mirrors real
 * chytaylo.com.ua pages captured live in the T1 spike (committed under
 * `docs/wayfinder/research/fixtures/chytaylo/`) and re-verified on the live
 * site for T3 (2026-09-09: 30 cards per listing page; the player payload is
 * singly escaped — `\"` pairs). No network.
 */
class ChytayloAdapterTest {

    // Builds the escaped server-component payload the way the live page
    // renders it: every quote becomes \" (one escape layer), exactly the
    // byte shape of `research/fixtures/chytaylo/book-dzheyn-eyr.html`.
    private fun escapeLayer(json: String): String = json.replace("\"", "\\\"")

    // The player props of book-dzheyn-eyr.html (38 live tracks, «Частина N»;
    // two kept here). Root-relative /api/audio-local/…mp3 URLs, as rendered.
    private val playerProps = escapeLayer(
        """{"bookKey":"dzheyn-eyr","bookTitle":"Джейн Ейр","tracks":[""" +
            """{"title":"Частина 1","url":"/api/audio-local/book-dzheyn-eyr-part-001-76af7d7b3a3b.mp3"},""" +
            """{"title":"Частина 2","url":"/api/audio-local/book-dzheyn-eyr-part-002-f9444e385fd9.mp3"}],""" +
            """"coverUrl":"/api/uploads/book-cover-1788808046290-897490821aa755e6.webp"}"""
    )

    // The `$` of the server-component line, escaped so the raw string below
    // stays literal text (only $playerProps interpolates).
    private val d = "$"

    // book-dzheyn-eyr.html (trimmed): the schema.org Book JSON-LD (behind the
    // site's Organization/WebSite blocks, as on the live page) + the escaped
    // player payload + the «Про що книга» annotation container.
    private val bookPage = """
        <!-- SPIKE FIXTURE (spec-47 T1): live capture, trimmed to parser-relevant parts. -->
        <title>Джейн Ейр — аудіокнига українською онлайн • Читайло</title>
        <meta property="og:title" content="Джейн Ейр — аудіокнига українською онлайн • Читайло"
        <meta property="og:image" content="https://chytaylo.com.ua/api/uploads/book-cover-1788808046290-897490821aa755e6.webp"
        <script type="application/ld+json">[{"@context":"https://schema.org","@type":"Organization","name":"Читайло","logo":"https://chytaylo.com.ua/logo-warm.png"},{"@context":"https://schema.org","@type":"WebSite","name":"Читайло"},{"@context":"https://schema.org","@type":"Book","name":"Джейн Ейр","author":{"@type":"Person","name":"Шарлотта Бронте"},"image":"/api/uploads/book-cover-1788808046290-897490821aa755e6.webp","inLanguage":"uk-UA","bookFormat":["AudiobookFormat"],"url":"https://chytaylo.com.ua/books/dzheyn-eyr","description":"Джейн Ейр рано лишається без батьків."}]</script>
        <!-- [TRIMMED: react server components] embedded tracks payload window -->
        l]}]\n31:["${d}","div",null,{"id":"player","children":[["${d}","h2",null,{"children":["Джейн Ейр"," - слухати аудіокнигу українською"]}],"${d}","${d}L37",null,{$playerProps}]]}]
        <!-- [TRIMMED: page body] -->
        <h2 class="text-[15px] font-black">Про що книга</h2><div class="mt-2 max-w-none prose prose-slate text-[15px]"><p>Джейн Ейр рано лишається без батьків і з дитинства зазнає принижень у домі родичів.</p>
<p>Едвард Рочестер приваблює Джейн гострим розумом і відвертістю.</p></div>
        <h2>Схожі книги</h2>
    """.trimIndent()

    // audiobooks-listing.html (trimmed): two real cards + one card without
    // the author div (the live shape has it, the parser must not invent one)
    // + a «Схожі книги»-style non-listing article that never becomes a card.
    private val listingPage = """
        <!-- SPIKE FIXTURE (spec-47 T1): live capture, trimmed. Original: /audiobooks -->
        <title>Аудіокниги • Читайло</title>
        <article class="group min-w-0"><div class="relative overflow-hidden rounded-[24px]"><a class="block" href="/books/dzhakomo-dzhoys"><img alt="Джакомо Джойс" loading="lazy" width="240" height="360" class="aspect-[2/3]" src="/api/uploads/book-cover-1788939378809-04d9f36346755a4c.webp"/></a></div><div class="px-1 pt-3"><a class="block" href="/books/dzhakomo-dzhoys"><div class="line-clamp-2 text-[1rem] font-extrabold leading-[1.2] text-[#2F2F2F]">Джакомо Джойс</div><div class="mt-1 line-clamp-2 text-sm font-medium leading-[1.35] text-[#7B766D]">Джеймс Джойс</div></a></div></article><article class="group min-w-0"><div class="relative overflow-hidden rounded-[24px]"><a class="block" href="/books/dary-volkhviv"><img alt="Дари волхвів" loading="lazy" width="240" height="360" class="aspect-[2/3]" src="/api/uploads/book-cover-0000-dary.webp"/></a></div><div class="px-1 pt-3"><a class="block" href="/books/dary-volkhviv"><div class="line-clamp-2 text-[1rem] font-extrabold leading-[1.2] text-[#2F2F2F]">Дари волхвів</div><div class="mt-1 line-clamp-2 text-sm font-medium leading-[1.35] text-[#7B766D]">О. Генрі</div></a></div></article><article class="group min-w-0"><div class="relative overflow-hidden rounded-[24px]"><a class="block" href="/books/zbirka-bez-avtora"><img alt="Збірка без автора" loading="lazy" width="240" height="360" class="aspect-[2/3]" src="/api/uploads/book-cover-0000-zbirka.webp"/></a></div><div class="px-1 pt-3"><a class="block" href="/books/zbirka-bez-avtora"><div class="line-clamp-2 text-[1rem] font-extrabold leading-[1.2] text-[#2F2F2F]">Збірка без автора</div></a></div></article><article class="rounded-2xl border border-[#E8E6E3] bg-[#FFFCF8] p-2.5"><a href="/books/skhozha-knyga"><div class="line-clamp-2 text-sm">Схожа книга</div></a></article>
    """.trimIndent()

    // The same listing served for page 2 — distinct slugs so the walk is
    // observable (real pagination: /audiobooks?page=2 works, T1/T3).
    private val listingPage2 = listingPage
        .replace("/books/dzhakomo-dzhoys", "/books/page2-a")
        .replace("/books/dary-volkhviv", "/books/page2-b")
        .replace("/books/zbirka-bez-avtora", "/books/page2-c")
        .replace("Джакомо Джойс", "Книга сторінки два")
        .replace("Джеймс Джойс", "Інший Автор")
        .replace("Дари волхвів", "Друга книга")
        .replace("О. Генрі", "Ще Один")
        .replace("Збірка без автора", "Третя книга")

    private val bookUrl = "https://chytaylo.com.ua/books/dzheyn-eyr"
    private val listingUrl = "https://chytaylo.com.ua/audiobooks"
    private val listingUrl2 = "https://chytaylo.com.ua/audiobooks?page=2"

    @Test
    fun `search is an honest empty list`() = runBlocking {
        // T1 spike found no server-side search endpoint; the T3 probes
        // (?s=, /api/search, /search) filter nothing or 404 — the seam
        // contract's honest refusal, never a fake results page.
        val adapter = ChytayloAdapter(FakeFetcher(emptyMap()))

        assertTrue(adapter.search("Джейн Ейр").isEmpty())
        assertTrue(adapter.search("").isEmpty())
    }

    @Test
    fun `ukrainian content language and direct access mode`() {
        val adapter = ChytayloAdapter(FakeFetcher(emptyMap()))

        assertEquals("uk", adapter.contentLanguage)
        assertEquals(SourceAccessMode.DIRECT, SourceAccessPolicy.modeFor("chytaylo"))
    }

    @Test
    fun `listing parses title author cards with covers and no invented authors`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(listingUrl to listingPage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(3, books.size)
        assertEquals("Джакомо Джойс", books[0].title)
        assertEquals("Джеймс Джойс", books[0].author)
        assertEquals("https://chytaylo.com.ua/books/dzhakomo-dzhoys", books[0].url)
        assertEquals(
            "https://chytaylo.com.ua/api/uploads/book-cover-1788939378809-04d9f36346755a4c.webp",
            books[0].coverImageUrl
        )
        assertEquals("chytaylo", books[0].sourceId)
        assertEquals("Дари волхвів", books[1].title)
        assertEquals("О. Генрі", books[1].author)
    }

    @Test
    fun `listing card without the author div keeps the author empty`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(listingUrl to listingPage)))

        val books = adapter.fetchNew(limit = 10)

        // A card with no author div never gets a fake author — blank, so the
        // Work-level merge key cannot form (the no-fabricated-author rule).
        assertEquals("Збірка без автора", books[2].title)
        assertEquals("", books[2].author)
    }

    @Test
    fun `non-listing articles never become cards`() = runBlocking {
        // The «Схожі книги»-shaped article carries a /books/ link but not the
        // listing card signature — it must stay invisible to the catalogue.
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(listingUrl to listingPage)))

        val books = adapter.fetchNew(limit = 10)

        assertTrue(books.none { it.title == "Схожа книга" })
        assertTrue(books.none { it.url.endsWith("skhozha-knyga") })
    }

    @Test
    fun `fetchNew respects the limit`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(listingUrl to listingPage)))

        assertEquals(1, adapter.fetchNew(limit = 1).size)
        assertEquals(0, adapter.fetchNew(limit = 0).size)
    }

    @Test
    fun `book page parses JSON-LD metadata and the escaped tracks payload`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(bookUrl to bookPage)))

        val detail = adapter.fetchBookPage(bookUrl)

        assertEquals("Джейн Ейр", detail.title)
        assertEquals("Шарлотта Бронте", detail.author)
        assertEquals(
            "https://chytaylo.com.ua/api/uploads/book-cover-1788808046290-897490821aa755e6.webp",
            detail.coverImageUrl
        )
        // The page's own BCP-47 claim («uk-UA»), normalized — never guessed.
        assertEquals("uk", detail.language)
        // The site names no narrator — the field stays empty, never invented.
        assertEquals("", detail.narrator)
        assertEquals(2, detail.chapters.size)
        assertEquals("Частина 1", detail.chapters[0].title)
        assertEquals(
            "https://chytaylo.com.ua/api/audio-local/book-dzheyn-eyr-part-001-76af7d7b3a3b.mp3",
            detail.chapters[0].streamUrl
        )
        assertEquals(
            "https://chytaylo.com.ua/api/audio-local/book-dzheyn-eyr-part-002-f9444e385fd9.mp3",
            detail.chapters[1].streamUrl
        )
    }

    @Test
    fun `book page carries the real annotation as description`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(bookUrl to bookPage)))

        val detail = adapter.fetchBookPage(bookUrl)

        // The «Про що книга» container's paragraphs, in order, HTML stripped.
        assertTrue(detail.description.startsWith("Джейн Ейр рано лишається без батьків"))
        assertTrue(detail.description.contains("Едвард Рочестер приваблює Джейн"))
        // The sections after the container never leak in.
        assertTrue(!detail.description.contains("Схожі книги"))
    }

    @Test
    fun `page without the tracks payload is honestly empty - the audio-only boundary`() = runBlocking {
        // A text-book / online-reading page of the same site (spec-47's
        // content boundary, fixed in CONTEXT.md): no escaped tracks payload —
        // nothing playable, chapters honestly empty.
        val textBookPage = """
            <title>Текстова книга • Читайло</title>
            <script type="application/ld+json">[{"@context":"https://schema.org","@type":"Book","name":"Текстова книга","author":{"@type":"Person","name":"Хтось"},"inLanguage":"uk-UA"}]</script>
            <div class="reader-content">Читати онлайн…</div>
        """.trimIndent()
        val adapter = ChytayloAdapter(FakeFetcher(mapOf("https://chytaylo.com.ua/books/tekstova" to textBookPage)))

        val detail = adapter.fetchBookPage("https://chytaylo.com.ua/books/tekstova")

        // The boundary per-page: metadata may parse, but there are no tracks —
        // the import doors treat empty chapters as nothing playable.
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `unreachable page yields the empty honest detail`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(emptyMap()))

        val detail = adapter.fetchBookPage("https://chytaylo.com.ua/books/missing")

        assertEquals("", detail.title)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `catalog walks listing pages and stops at the limit`() = runBlocking {
        val adapter = ChytayloAdapter(
            FakeFetcher(mapOf(listingUrl to listingPage, listingUrl2 to listingPage2))
        )

        val books = adapter.fetchCatalog(limit = 4)

        assertEquals(4, books.size)
        // Page 1 first, then page 2 — the walk is in the site's own order.
        assertEquals("Джакомо Джойс", books[0].title)
        assertEquals("https://chytaylo.com.ua/books/dzhakomo-dzhoys", books[0].url)
        assertEquals("Книга сторінки два", books[3].title)
        assertEquals(5, adapter.fetchCatalog(limit = 5).size)
    }

    @Test
    fun `catalog with an empty listing stops without fabricating`() = runBlocking {
        val adapter = ChytayloAdapter(FakeFetcher(mapOf(listingUrl to "<html><body></body></html>")))

        assertTrue(adapter.fetchCatalog(limit = 10).isEmpty())
    }

    @Test
    fun `bookId keeps the catalog slug`() {
        val adapter = ChytayloAdapter(FakeFetcher(emptyMap()))

        assertEquals(
            "chytaylo-dzheyn-eyr",
            adapter.bookId("https://chytaylo.com.ua/books/dzheyn-eyr")
        )
        assertEquals(
            "chytaylo-dzheyn-eyr",
            adapter.bookId("https://chytaylo.com.ua/books/dzheyn-eyr?ref=x")
        )
    }
}
