package com.slukhayka.audiobooks.data.repository

import com.slukhayka.audiobooks.data.search.GlobalSearchUpdate
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import com.slukhayka.audiobooks.data.metadata.CleanProfileProbeVerdict
import com.slukhayka.audiobooks.data.metadata.VerifiedSourceProfilePublisher
import com.slukhayka.audiobooks.data.metadata.CleanProfileProber
import com.slukhayka.audiobooks.data.metadata.VerifiedSourceProfile
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.ProfileChapter
import com.slukhayka.audiobooks.data.metadata.ProfilePublication
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.DurationProvenance
import com.slukhayka.audiobooks.data.metadata.CoverProvenance

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
import com.slukhayka.audiobooks.data.source.SluhayuaAdapter
import com.slukhayka.audiobooks.testing.FakeFetcher
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        private val detail: SourceBookDetail? = null,
        override val supportsSearch: Boolean = true
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

    // ---------------------------------------------------------------------
    // spec-47 T5 (#633): the wave's three sources registered in the seam —
    // URL → source map and display-name badges, the same pins spec-11 T3
    // added for the original sources. Started by a contributor; finished
    // here with chytaylo and ukrainianaudiobooks.
    // ---------------------------------------------------------------------

    @Test
    fun `audiobookcoua urls map to their own source - spec-47 T5 registration`() {
        // Book page, novinki section and playlist txt all live on the site's
        // own host — one source identity for every URL the adapter emits. The
        // audio itself rides archive.org (T1): its /details/ shape stays
        // librivox's mirror mapping, and /download/ streams need no mapping
        // because the header seam keys off the book's PAGE url.
        assertEquals("audiobookcoua", sourceIdForUrl("https://audiobook.co.ua/pid-kupolom-stiven-king/"))
        assertEquals("audiobookcoua", sourceIdForUrl("https://audiobook.co.ua/novinki-ozvuchivaniya/"))
        assertEquals("audiobookcoua", sourceIdForUrl("https://audiobook.co.ua/playlist/pid-kupolom-stiven-king.txt"))
        // The adapter's audio rides the LibriVox mirror transport — the URL
        // mapping must not claim it (it stays librivox, or unknown outside
        // /details/): the header seam keys off the book's PAGE url.
        assertEquals("librivox", sourceIdForUrl("https://archive.org/details/librivoxaudio-pid-kupolom"))
    }

    @Test
    fun `chytaylo urls map to the chytaylo source - spec-47 T5 registration`() {
        // Listing, category and book pages all live on the site's own host;
        // the root-relative track URLs the adapter prefixes resolve there too.
        assertEquals("chytaylo", sourceIdForUrl("https://chytaylo.com.ua/audiobooks"))
        assertEquals("chytaylo", sourceIdForUrl("https://chytaylo.com.ua/audiobooks?categoryKey=roman"))
        assertEquals("chytaylo", sourceIdForUrl("https://chytaylo.com.ua/books/dzheyn-eyr"))
        assertEquals("chytaylo", sourceIdForUrl("https://chytaylo.com.ua/api/audio-local/book-dzheyn-eyr-part-001-76af7d7b3a3b.mp3"))
    }

    @Test
    fun `ukrainianaudiobooks urls map to their own source - spec-47 T5 registration`() {
        // The captured-page import door keys on the book's PAGE url (T1
        // verdict GATED — Cloudflare; the site's own host is the whole
        // identity today).
        assertEquals("ukrainianaudiobooks", sourceIdForUrl("https://ukrainianaudiobooks.com/books/some-book"))
        assertEquals("ukrainianaudiobooks", sourceIdForUrl("https://www.ukrainianaudiobooks.com/"))
    }

    @Test
    fun `knigionline urls map to their own source - spec-50 T4 registration`() {
        // Book pages, the novinki section and the AudioIgniter playlist all
        // live on the site's own host — one identity per host.
        assertEquals("knigionline", sourceIdForUrl("https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/"))
        assertEquals("knigionline", sourceIdForUrl("https://knigi-online.com.ua/audioknyhy/"))
        assertEquals("knigionline", sourceIdForUrl("https://knigi-online.com.ua/?audioigniter_playlist_id=531"))
    }

    @Test
    fun `chitaka urls map to their own source - spec-50 T4 registration`() {
        assertEquals("chitaka", sourceIdForUrl("https://chitaka.com.ua/audioknyhy/"))
        assertEquals("chitaka", sourceIdForUrl("https://chitaka.com.ua/knigi/1984/"))
        assertEquals("chitaka", sourceIdForUrl("https://chitaka.com.ua/wp-content/uploads/2022/10/1984.mp3"))
    }

    @Test
    fun `wave badges show the display names - spec-47 T5 registration`() {
        assertEquals("Audiobook.co.ua", sourceDisplayName("audiobookcoua"))
        assertEquals("Читайло", sourceDisplayName("chytaylo"))
        assertEquals("Ukrainian Audiobooks", sourceDisplayName("ukrainianaudiobooks"))
        assertEquals("Knigi-Online", sourceDisplayName("knigionline"))
        assertEquals("Читака", sourceDisplayName("chitaka"))
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
    fun `a successful empty SluhayUA search settles without a second feed request`() = runBlocking {
        val endpoint = "https://sluhay.com.ua/find/allcards?search=%D0%BA%D0%BE%D0%B1%D0%B7%D0%B0%D1%80&page=1"
        val fetcher = FakeFetcher(mapOf(endpoint to """{"cards":[],"pageCount":1}"""))
        val updates = mutableListOf<GlobalSearchUpdate>()

        val results = repo(SluhayuaAdapter(fetcher)).searchAllSources("кобзар", updates::add)

        assertTrue(results.isEmpty())
        assertEquals(listOf(endpoint), fetcher.requestedUrls)
        assertEquals(listOf(true, false), updates.map { it.isSearchingSources })
        assertEquals(false, updates.last().hasSourceFailures)
    }

    @Test
    fun `an unavailable SluhayUA search settles as partial without hiding another source`() = runBlocking {
        val fetcher = FakeFetcher()
        val updates = mutableListOf<GlobalSearchUpdate>()
        val catalog = repo(SluhayuaAdapter(fetcher), FakeAdapter("knigionline",
            searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "knigionline"))))

        val result = catalog.searchAllSources("кобзар", updates::add)

        assertEquals(listOf("Кобзар"), result.map { it.title })
        assertEquals(1, fetcher.requestedUrls.size)
        assertEquals(listOf(true, false), updates.map { it.isSearchingSources })
        assertTrue(updates.last().hasSourceFailures)
    }

    @Test
    fun `cancelling SluhayUA search never fetches a feed or publishes a settled answer`() {
        var requests = 0
        val fetcher = object : FakeFetcher() {
            override fun getText(url: String, extraHeaders: Map<String, String>,
                requestClass: SourceRequestClass,
                cacheTtlMillis: Long): String {
                requests++
                throw CancellationException("listener cancelled")
            }
        }
        val updates = mutableListOf<GlobalSearchUpdate>()
        assertThrows(CancellationException::class.java) {
            runBlocking { repo(SluhayuaAdapter(fetcher)).searchAllSources("кобзар", updates::add) }
        }
        assertEquals(1, requests)
        assertEquals(listOf(true), updates.map { it.isSearchingSources })
    }

    @Test
    fun `SluhayUA search imports ordered chapters and verified publication requires player and clean probe`() = runBlocking {
        val searchUrl = "https://sluhay.com.ua/find/allcards?search=%D0%9A%D0%B2%D1%96%D1%82%D0%BA%D0%B0&page=1"
        val pageUrl = "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana"
        val streams = listOf(
            "https://mp3.sluhay.com.ua/Serdeshna/01.mp3",
            "https://mp3.sluhay.com.ua/Serdeshna/02.mp3",
            "https://mp3.sluhay.com.ua/Serdeshna/03.mp3",
            "https://mp3.sluhay.com.ua/Serdeshna/04.mp3",
            "https://mp3.sluhay.com.ua/Serdeshna/05.mp3",
            "https://mp3.sluhay.com.ua/Serdeshna/06.mp3",
            "https://mp3.sluhay.com.ua/Serdeshna/07.mp3"
        )
        val responses = mutableMapOf(
            searchUrl to """{"cards":[{"_id":5931576,"slug":"grigorij-kvitka-osnovjanenko-serdjeshna-oksana","bookName":"Сердешна Оксана","bookAuthor":["Григорій Квітка-Основяненко"],"audioAuthor":["Діана Гончаренко"]}],"pageCount":1}""",
            pageUrl to """<html><head>
                <meta property="og:title" content="Григорій Квітка-Основяненко - Сердешна Оксана. Слухай аудіокнигу онлайн" />
                <meta property="og:description" content="Аудіокнигу онлайн Сердешна Оксана, читає Діана Гончаренко." />
                </head><body><script>var playlist = [["0",0],["1",1],["2",2],["3",3],["4",4],["5",5],["6",6]];</script></body></html>"""
        )
        streams.forEachIndexed { index, stream ->
            responses["https://sluhay.com.ua/play?bookId=5931576&fileId=$index"] = stream
        }
        val fetcher = FakeFetcher(responses)
        val adapter = SluhayuaAdapter(fetcher)
        val importer = imports(adapter)
        val catalog = SourceCatalog(dao, listOf(adapter), importer)
        val sourceCard = catalog.searchAllSources("Квітка").single().sources.single()
        val imported = requireNotNull(importer.importFromSourceUrl(sourceCard.sourceId, sourceCard.url))
        val playable = catalog.getPlayableChapters(imported.id)

        assertEquals("Сердешна Оксана", imported.title)
        assertEquals("Григорій Квітка-Основяненко", imported.author)
        assertEquals("Діана Гончаренко", imported.narrator)
        assertEquals("uk", imported.language)
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3", "Глава 4", "Глава 5", "Глава 6", "Глава 7"),
            playable.map { it.chapter.title })
        assertEquals(streams, playable.map { it.track?.url })
        assertEquals(9, fetcher.requestedUrls.size) // one search, one page, seven chapter URLs
        assertTrue(fetcher.requestedUrls.none { it.contains("sort=time") })

        val store = VerifiedProfileStore()
        var probeVerdict = CleanProfileProbeVerdict.BLOCKED
        var probeCalls = 0
        val publisher = VerifiedSourceProfilePublisher(store,
            CleanProfileProber { url, headers ->
                probeCalls++
                assertEquals("https://mp3.sluhay.com.ua/Serdeshna/01.mp3", url)
                assertTrue(headers.keys.none { it.equals("Cookie", ignoreCase = true) })
                probeVerdict
            })
        val source = dao.getSourcesForBookSync(imported.id).single()
        val candidate = VerifiedSourceProfile(
            sourceId = "sluhayua", editionId = requireNotNull(source.editionId), playerOpened = false,
            source = SourceAccessCandidate("sluhayua", url = pageUrl),
            profile = BookProfile(
                title = imported.title, author = imported.author, narrator = imported.narrator,
                chapters = playable.map { ProfileChapter(
                    it.chapter.title, requireNotNull(it.track).url, it.chapter.durationSeconds) }))
        assertEquals(ProfilePublication.LOCAL_ONLY, publisher.publish(candidate))
        assertEquals(0, probeCalls)
        assertEquals(0, store.published.size)
        assertEquals(ProfilePublication.LOCAL_ONLY,
            publisher.publish(candidate.copy(playerOpened = true)))
        assertEquals(0, store.published.size)
        probeVerdict = CleanProfileProbeVerdict.PLAYABLE
        assertEquals(ProfilePublication.PUBLISHED,
            publisher.publish(candidate.copy(playerOpened = true)))
        assertEquals(1, store.published.size)
        assertEquals(streams, store.published.single().first.chapters.map { it.streamUrl })
        assertEquals(ProfileProvenance.SOURCE_VERIFIED,
            store.published.single().second.source)
        assertEquals(9, fetcher.requestedUrls.size)
    }

    private class VerifiedProfileStore : SharedBookMetaStore {
        val published = mutableListOf<Pair<BookProfile,
            ProfileProvenance>>()
        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long,
            provenance: DurationProvenance) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String):
            com.slukhayka.audiobooks.data.metadata.SharedProfileEntry? = null
        override suspend fun putProfile(sourceId: String, editionId: String,
            profile: BookProfile,
            provenance: ProfileProvenance) { published += profile to provenance }
        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String,
            provenance: CoverProvenance) = Unit
    }

    @Test
    fun `global search queries all sources and merges deduped into one Work card`() = runBlocking {
        val repository = repo(
            FakeAdapter("sluhayua", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua"))),
            FakeAdapter("soundbooks", feedBooks = listOf(book("КОБЗАР", "Тарас Шевченко", "soundbooks")), supportsSearch = false)
        )

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        val card = results.single()
        assertEquals("Кобзар", card.title)
        assertEquals(listOf("soundbooks", "sluhayua"), card.sources.map { it.sourceId })
    }

    @Test
    fun `feed-only sources are discovered by the query`() = runBlocking {
        val repository = repo(
            FakeAdapter("sluhayua"), // no search hits
            FakeAdapter("lihtar", feedBooks = listOf(book("Лісова пісня", "", "lihtar")), supportsSearch = false)
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
            FakeAdapter("sluhayua", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua"))),
            FakeAdapter("audiobookmp3", feedBooks = listOf(book("Кобзар", "", "audiobookmp3")), detail = detail, supportsSearch = false)
        )

        val results = repository.searchAllSources("кобзар")

        // The blank-author feed entry was enriched from its own book page and
        // now merges with the 4read result: one card, two source badges.
        assertEquals(1, results.size)
        assertEquals("Кобзар", results.single().title)
        assertEquals("Тарас Шевченко", results.single().author)
        assertEquals(listOf("sluhayua", "audiobookmp3"), results.single().sources.map { it.sourceId })
    }

    @Test
    fun `global search is ephemeral - nothing is imported into Room`() = runBlocking {
        val repository = repo(
            FakeAdapter("sluhayua", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua")))
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
    // spec-33 T2 (#227): the shared search cache in the search flow — a
    // fresh hit suppresses the source adapters, a miss resolves and writes
    // back, a stale entry re-fetches and refreshes, and repeated queries
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
        private val searchBooks: List<SourceBook> = emptyList(),
        private val feedBooks: List<SourceBook> = emptyList(),
        override val supportsSearch: Boolean = true
    ) : SourceAdapter {
        var searchCalls = 0
        var feedCalls = 0

        override suspend fun search(query: String): List<SourceBook> {
            searchCalls++
            return searchBooks
        }

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            feedCalls++
            return feedBooks
        }
    }

    /** An adapter whose search waits on a gate — proves the sweep is parallel. */
    private class GatedAdapter(
        override val sourceId: String,
        private val gate: CompletableDeferred<Unit>,
        private val signal: CompletableDeferred<Unit>? = null
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> {
            signal?.complete(Unit)
            gate.await()
            return emptyList()
        }

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private fun cachedCard() = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        mergeKey = "кобзар|тарас шевченко",
        sources = listOf(GlobalSearchSource("sluhayua", "sluhayua", "https://sluhay.com.ua/5359-taras-shevchenko-kobzar.html"))
    )

    @Test
    fun `a fresh shared cache cannot hide a new book from sources`() = runBlocking {
        val cache = FakeSearchCache()
        val adapter = CountingAdapter("sluhayua", searchBooks = listOf(book("Кобзареві думи", "Тарас Шевченко", "sluhayua")))
        val repository = repo(adapter, searchCache = cache)
        // The cache holds the merged card for the query — as if another
        // listener resolved it earlier today.
        cache.putResults("кобзар", listOf(cachedCard()))

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        assertEquals("Кобзареві думи", results.single().title)
        // The cached card does not prove that the sources have no other books.
        assertEquals(1, adapter.searchCalls)
    }

    @Test
    fun `a cache miss resolves from the sources and writes the result back`() = runBlocking {
        val cache = FakeSearchCache()
        val repository = repo(
            CountingAdapter("sluhayua", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua"))),
            searchCache = cache
        )

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        // The merged result is still available to shared-cache consumers
        // under the normalized key (including replacement mapping).
        val document = cache.documents["кобзар"]
        assertNotNull(document)
        assertEquals(results, SearchResultCodec.fromMap(document!!)?.results)
    }

    @Test
    fun `a cache miss with an empty result writes nothing back`() = runBlocking {
        val cache = FakeSearchCache()
        val repository = repo(CountingAdapter("sluhayua"), searchCache = cache)

        val results = repository.searchAllSources("нічого немає")

        assertTrue(results.isEmpty())
        // Negatives are never cached — the empty survivor is a no-op write.
        assertTrue(cache.documents.isEmpty())
    }

    @Test
    fun `a stale cached entry re-fetches from the sources and refreshes the cache`() = runBlocking {
        val cache = FakeSearchCache()
        val adapter = CountingAdapter("sluhayua", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua")))
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
    fun `repeated queries keep their result shape while consulting sources`() = runBlocking {
        val cache = FakeSearchCache()
        val adapter = CountingAdapter("sluhayua", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua")))
        val repository = repo(adapter, searchCache = cache)

        val live = repository.searchAllSources("кобзар")
        val cached = repository.searchAllSources("кобзар")

        // Repeated source answers still produce identical cards; consulting
        // sources must not introduce duplicates from the local preview.
        assertEquals(live, cached)
        // Each query consults sources, even when its result shape is unchanged.
        assertEquals(2, adapter.searchCalls)
    }

    @Test
    fun `a scam source is never queried by global search`() = runBlocking {
        // #741: 4read is a scam source (52-second artefact) — the sweep skips
        // it entirely: no request, no card, no badge.
        val adapter = CountingAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read")))
        val repository = repo(adapter)

        val results = repository.searchAllSources("кобзар")

        assertEquals(0, results.size)
        assertEquals(0, adapter.searchCalls)
        assertEquals(0, adapter.feedCalls)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `searchSource falls back to the feed when the endpoint has no search`() = runBlocking {
        // #721 — a direct source without a usable search endpoint answers
        // from its recent-arrivals feed, exactly as the aggregated search
        // always did; the replacement volley consumes the same seam.
        val adapter = CountingAdapter(
            "soundbooks",
            feedBooks = listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")),
            supportsSearch = false
        )
        val repository = repo(adapter)

        val found = repository.searchSource(adapter, "кобзар")

        assertEquals(1, found.size)
        assertEquals("soundbooks", found.single().sourceId)
        assertEquals(1, adapter.feedCalls)
        assertEquals(0, adapter.searchCalls)
    }

    @Test
    fun `searchSource never touches the feed when the endpoint answers`() = runBlocking {
        val adapter = CountingAdapter(
            "sluhayua",
            searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua")),
            feedBooks = listOf(book("Кобзар", "Тарас Шевченко", "sluhayua"))
        )
        val repository = repo(adapter)

        val found = repository.searchSource(adapter, "кобзар")

        assertEquals(1, found.size)
        assertEquals(0, adapter.feedCalls)
    }

    @Test
    fun `global search queries sources concurrently - never a sequential crawl`() = runBlocking {
        // #722 — the first adapter cannot finish until the second has
        // STARTED: only a parallel volley can ever complete this search.
        val secondStarted = CompletableDeferred<Unit>()
        val repository = repo(
            GatedAdapter("aaa", gate = secondStarted),
            GatedAdapter("zzz", gate = CompletableDeferred(Unit), signal = secondStarted)
        )

        val results = withTimeout(10_000) {
            repository.searchAllSources("кобзар")
        }
        assertTrue(results.isEmpty())
    }
}
