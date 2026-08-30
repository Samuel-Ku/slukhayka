package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.search.SearchCache
import com.slukhayka.audiobooks.data.search.SearchFreshness
import com.slukhayka.audiobooks.data.search.SearchResultCodec
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-10 T4): the aggregated global search and the
 * import-from-result path, driven by injected fake adapters — no network.
 * Tests external behaviour: all sources are queried, results dedup into one
 * card per Work, feed-only sources are discovered by the query, and tapping a
 * result imports the Work with its source row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalSearchRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeAdapter(
        override val sourceId: String,
        private val searchBooks: List<SourceBook> = emptyList(),
        private val feedBooks: List<SourceBook> = emptyList(),
        private val detail: SourceBookDetail? = null
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> =
            searchBooks.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            detail ?: SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = feedBooks
    }

    // ADR-0002 (#138): the catalog tests construct the Source Catalog module
    // directly — no god module, no auto-sync on construction. The import door
    // tests below construct the Library Import module beside it (DAG edge).
    private fun repo(vararg adapters: SourceAdapter, searchCache: SearchCache? = null) =
        SourceCatalog(dao, adapters.toList(), LibraryImport(dao, context, adapters.toList()), searchCache = searchCache)

    private fun imports(vararg adapters: SourceAdapter) =
        LibraryImport(dao, context, adapters.toList())

    @Test
    fun `sluhayua urls map to the sluhayua source`() {
        assertEquals("sluhayua", sourceIdForUrl("https://sluhay.com.ua/4508492:taras-shevchenko-Єretik"))
        assertEquals("sluhayua", sourceIdForUrl("https://mp3.sluhay.com.ua/Serdeshna/01.mp3"))
    }

    @Test
    fun `sluhay and sluhayknigi urls map to their own sources - never the cdn`() {
        // sluhay.com.ua is checked before sluhay.com (it contains it); the
        // shared redirectto.cc CDN and the knigi domain must resolve to the
        // right source so the Referer seam picks the owning site.
        assertEquals("sluhay", sourceIdForUrl("https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html"))
        assertEquals("sluhayknigi", sourceIdForUrl("https://sluhayknigi.com/svitova-literatura/6066-klark-eshton-smit-metamorfoza-zemli.html"))
        // The book's sourceUrl is the PAGE url (never the mp3), but a stray
        // CDN url must still not map to sluhayua or unknown.
        assertEquals("sluhay", sourceIdForUrl("https://sluhay.com/uploads/books/6150/cover.webp"))
    }

    @Test
    fun `default adapter registry constructs with sluhayua and sluhay registered`() {
        // The production default list (no injection) must build and know the
        // sluhayua + sluhay sources; adapter construction is inert (no network).
        val defaultCatalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
        assertEquals("sluhayua", sourceIdForUrl("https://sluhay.com.ua/1965454:olga-kobilyanska-priroda"))
        assertEquals("sluhay", sourceIdForUrl("https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html"))
        // A WebView source has no server-fetch search — the registry still
        // holds the adapter (import path), but the feed stays empty (no row).
        val feed = defaultCatalog.sourceFeeds.value
        assertTrue(feed.none { it.sourceId == "sluhay" })
    }

    private fun book(title: String, author: String, sourceId: String) =
        SourceBook(title = title, author = author, url = "https://$sourceId.example/$title", sourceId = sourceId)

    @Test
    fun `global search queries all sources and merges deduped into one Work card`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read"))),
            FakeAdapter("soundbooks", feedBooks = listOf(book("КОБЗАР", "Тарас Шевченко", "soundbooks")))
        )

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        val card = results.single()
        assertEquals("Кобзар", card.title)
        assertEquals(listOf("soundbooks", "4read"), card.sources.map { it.sourceId })
    }

    @Test
    fun `feed-only sources are discovered by the query`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read"), // no search hits
            FakeAdapter("lihtar", feedBooks = listOf(book("Лісова пісня", "", "lihtar")))
        )

        assertEquals(1, repository.searchAllSources("лісова").size)
        assertEquals("lihtar", repository.searchAllSources("лісова").single().sources.single().sourceId)
        // A query nothing matches still returns no junk.
        assertEquals(0, repository.searchAllSources("неіснуюча книга").size)
    }

    @Test
    fun `feed matches with blank authors are enriched from the book page so the merge forms`() = runBlocking {
        // audiobookmp3-style page: a real author but no narrator markup, so the
        // enriched entry's merge key (title + author) matches the 4read card.
        // (Narrator-sensitivity is covered at the pure seam: GlobalSearchMergeTest.)
        val detail = SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            url = "https://audiobook-mp3.com/uk/kobzar.html",
            chapters = listOf(SourceChapter("Розділ 1", "https://cdn.example.com/kobzar/01.mp3"))
        )
        val repository = repo(
            FakeAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read"))),
            FakeAdapter("audiobookmp3", feedBooks = listOf(book("Кобзар", "", "audiobookmp3")), detail = detail)
        )

        val results = repository.searchAllSources("кобзар")

        // The blank-author feed entry was enriched from its own book page and
        // now merges with the 4read result: one card, two source badges.
        assertEquals(1, results.size)
        assertEquals("Кобзар", results.single().title)
        assertEquals("Тарас Шевченко", results.single().author)
        assertEquals(listOf("audiobookmp3", "4read"), results.single().sources.map { it.sourceId })
    }

    @Test
    fun `global search is ephemeral - nothing is imported into Room`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read")))
        )

        repository.searchAllSources("кобзар")

        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `importFromSourceUrl fetches the page and imports the Work with its source row`() = runBlocking {
        val detail = SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            url = "https://sound-books.net/kobzar.html",
            chapters = listOf(SourceChapter("Розділ 1", "https://arch.sound-books.net/100/01.mp3"))
        )
        val book = imports(FakeAdapter("soundbooks", detail = detail)).importFromSourceUrl("soundbooks", detail.url)

        assertNotNull(book)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        val sources = dao.getSourcesForBookSync(book!!.id)
        assertEquals(1, sources.size)
        assertEquals("soundbooks", sources.single().type)
        assertEquals(1, dao.getChaptersListForBook(book.id).size)
    }

    @Test
    fun `importFromSourceUrl returns null for unknown source or unplayable page`() = runBlocking {
        val importModule = imports()
        assertNull(importModule.importFromSourceUrl("nope", "https://unknown.example/x.html"))

        val unplayable = imports(
            FakeAdapter("soundbooks", detail = SourceBookDetail("К", "А", url = "https://u", chapters = emptyList()))
        )
        assertNull(unplayable.importFromSourceUrl("soundbooks", "https://u"))
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    // ---------------------------------------------------------------------
    // spec-14 T2: 4read search runs entirely through the adapter + shared
    // HTTP client — the repository owns no 4read search transport/parser.
    // ---------------------------------------------------------------------

    // Same poster-block markup shape as FourReadAdapterTest.searchPage: real
    // Cyrillic title in poster__title, author in the first poster__subtitle.
    private fun fourReadSearchPage(): String = """
        <html><body>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/5359-taras-shevchenko-kobzar.html" class="poster__link"><div class="poster__title line-clamp">Кобзар</div></a>
                <div class="poster__subtitle ws-nowrap">Тарас Шевченко</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2025-05/medium/shevchenko-taras-kobzar.webp" alt="Шевченко Тарас - Кобзар">
            </div>
        </div>
        </body></html>
    """.trimIndent()

    // ---------------------------------------------------------------------
    // spec-33 T2 (#227): the shared search cache in the search flow — a
    // fresh hit suppresses the source adapters, a miss resolves and writes
    // back, a stale entry re-fetches and refreshes, and the cached path
    // returns the same result shape as the live path.
    // ---------------------------------------------------------------------

    /** In-memory [SearchCache] with a controllable clock — the flow's fake store. */
    private class FakeSearchCache : SearchCache {
        val documents = mutableMapOf<String, Map<String, Any>>()
        var nowMillis: Long = 1_000_000L

        override suspend fun readDocument(queryKey: String): Map<String, Any>? = documents[queryKey]

        override suspend fun writeDocument(queryKey: String, document: Map<String, Any>) {
            documents[queryKey] = document
        }

        override fun nowMillis(): Long = nowMillis
    }

    /** A fake adapter that counts search invocations — to prove suppression. */
    private class CountingAdapter(
        override val sourceId: String,
        private val searchBooks: List<SourceBook> = emptyList()
    ) : SourceAdapter {
        var searchCalls = 0

        override suspend fun search(query: String): List<SourceBook> {
            searchCalls++
            return searchBooks
        }

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private fun cachedCard() = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        mergeKey = "кобзар|тарас шевченко",
        sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/5359-taras-shevchenko-kobzar.html"))
    )

    @Test
    fun `a fresh cached search hit suppresses the source adapters`() = runBlocking {
        val cache = FakeSearchCache()
        val adapter = CountingAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read")))
        val repository = repo(adapter, searchCache = cache)
        // The cache holds the merged card for the query — as if another
        // listener resolved it earlier today.
        cache.putResults("кобзар", listOf(cachedCard()))

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        assertEquals("Кобзар", results.single().title)
        // The adapter was never asked — the fresh cached result served the query.
        assertEquals(0, adapter.searchCalls)
    }

    @Test
    fun `a cache miss resolves from the sources and writes the result back`() = runBlocking {
        val cache = FakeSearchCache()
        val repository = repo(
            CountingAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read"))),
            searchCache = cache
        )

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        // The merged result was written back under the normalized key, so the
        // NEXT listener reads it instead of re-resolving.
        val document = cache.documents["кобзар"]
        assertNotNull(document)
        assertEquals(results, SearchResultCodec.fromMap(document!!)?.results)
    }

    @Test
    fun `a cache miss with an empty result writes nothing back`() = runBlocking {
        val cache = FakeSearchCache()
        val repository = repo(CountingAdapter("4read"), searchCache = cache)

        val results = repository.searchAllSources("нічого немає")

        assertTrue(results.isEmpty())
        // Negatives are never cached — the empty survivor is a no-op write.
        assertTrue(cache.documents.isEmpty())
    }

    @Test
    fun `a stale cached entry re-fetches from the sources and refreshes the cache`() = runBlocking {
        val cache = FakeSearchCache()
        val adapter = CountingAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read")))
        val repository = repo(adapter, searchCache = cache)
        // A yesterday-old entry — past the ~24h freshness window.
        cache.putResults("кобзар", listOf(cachedCard()))
        cache.nowMillis += SearchFreshness.FRESHNESS_MILLIS + 60_000

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        // The stale entry did not serve the query — the source was asked again.
        assertEquals(1, adapter.searchCalls)
        // ... and the entry was refreshed with the new fetch time.
        val refreshed = SearchResultCodec.fromMap(cache.documents["кобзар"]!!)!!
        assertEquals(cache.nowMillis, refreshed.fetchedAt)
    }

    @Test
    fun `the cached path returns the same result shape as the live path`() = runBlocking {
        val cache = FakeSearchCache()
        val adapter = CountingAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read")))
        val repository = repo(adapter, searchCache = cache)

        val live = repository.searchAllSources("кобзар")
        val cached = repository.searchAllSources("кобзар")

        // Identical cards — the cached path serves exactly what the live path
        // would have produced (spec-33 US-12).
        assertEquals(live, cached)
        // The second search never reached the source — it was a cache hit.
        assertEquals(1, adapter.searchCalls)
    }

    @Test
    fun `4read search runs through the real adapter and the shared HTTP client`() = runBlocking {
        // The FakeFetcher serves the search-page markup — the adapter is the
        // real FourReadAdapter, so this proves the door rides the seam: no
        // repository-owned transport or parser is involved.
        val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(responses = emptyMap(), fallback = fourReadSearchPage())
        val repository = repo(com.slukhayka.audiobooks.data.source.FourReadAdapter(fetcher))

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        val card = results.single()
        // Enriched profile fields (real author + cover) flow from the adapter.
        assertEquals("Кобзар", card.title)
        assertEquals("Тарас Шевченко", card.author)
        assertEquals("4read", card.sources.single().sourceId)
        assertEquals("https://4read.org/uploads/posts/2025-05/medium/shevchenko-taras-kobzar.webp", card.coverImageUrl)
        // Search stays ephemeral — nothing is imported into Room.
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }
}
