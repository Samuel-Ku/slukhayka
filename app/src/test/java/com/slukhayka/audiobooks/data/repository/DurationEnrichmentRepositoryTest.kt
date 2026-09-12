package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.duration.DurationBuckets
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.metadata.DurationProvenance
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-18 T2 (#113) — the background duration enrichment pass, through the
 * existing source adapter seam (prior art: HydrationRepositoryTest). A fake
 * adapter reports real durations; the pass writes them into book rows for
 * unknown-duration books only, respects its batch limit, survives failing
 * fetches, and throttles itself.
 *
 * #740 — the pass routes by each book's OWN `sourceId`: a direct source
 * enriches, a browser/scam source and a source without an adapter degrade
 * honestly (no request, no crash).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DurationEnrichmentRepositoryTest {

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

    /**
     * Records which book URLs were fetched, returns the configured duration
     * (null = page without a duration), and throws [IOException] for urls not
     * configured — the failing-fetch case.
     */
    private class FakeBookAdapter(
        override val sourceId: String,
        private val durationFor: Map<String, Long?>
    ) : SourceAdapter {
        val fetchedUrls = mutableListOf<String>()
        override val sessionBound: Boolean get() = false

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchedUrls += url
            val duration = durationFor[url] ?: throw IOException("network down")
            return SourceBookDetail(
                title = "",
                author = "",
                url = url,
                chapters = emptyList(),
                totalDurationSeconds = duration
            )
        }
    }

    private fun book(
        id: String,
        durationSeconds: Long,
        url: String = "https://sound-books.net/$id.html"
    ) = AudiobookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        sourceUrl = url,
        genre = "",
        totalDurationSeconds = durationSeconds
    )

    private suspend fun insertLibraryBooks(books: List<AudiobookEntity>) {
        dao.insertAudiobooks(books)
        books.forEach { book ->
            val workId = MergeKey.keyFor(book.title, book.author)
            dao.upsertWork(
                WorkEntity(
                    id = workId,
                    mergeKey = workId,
                    title = book.title,
                    author = book.author,
                    addedAt = START_EPOCH
                )
            )
            dao.upsertLibraryEntry(
                id = book.id,
                workId = workId,
                isFavorite = false,
                createdAt = START_EPOCH,
                downloadProgress = 0f
            )
        }
    }

    private fun repo(adapter: FakeBookAdapter, store: FakeSharedBookMetaStore? = null) =
        repo(listOf(adapter), store)

    /** #740 — the pass resolves each book's own adapter from this list. */
    private fun repo(adapters: List<FakeBookAdapter>, store: FakeSharedBookMetaStore? = null) =
        DurationEnrichment(
            dao,
            adapterFor = { sourceId -> adapters.firstOrNull { it.sourceId == sourceId } },
            sharedStore = store
        )

    private fun runEnrich(repo: DurationEnrichment, batchLimit: Int = 5, now: Long = 1_700_000_000_000L): Int =
        runBlocking { repo.enrichUnknownDurations(batchLimit = batchLimit, now = { now }) }

    private fun durationOf(id: String): Long? = runBlocking { dao.getAudiobookById(id) }?.totalDurationSeconds

    // ---------------------------------------------------------------------
    // Writes real durations, only for unknown-duration books
    // ---------------------------------------------------------------------

    @Test
    fun `unknown-duration books receive their real durations from the seam`() {
        runBlocking {
            insertLibraryBooks(
                listOf(
                    book("a", 0L),
                    book("b", 0L),
                    book("known", 1234L)
                )
            )
        }
        val adapter = FakeBookAdapter(
            DIRECT_SOURCE,
            mapOf(
                "https://sound-books.net/a.html" to 7200L,
                "https://sound-books.net/b.html" to 43_200L
            )
        )
        val repo = repo(adapter)

        assertEquals(2, runEnrich(repo))
        assertEquals(7200L, durationOf("a"))
        assertEquals(43_200L, durationOf("b"))
        // The known-duration book was never fetched — the pass only looks at
        // books whose duration is missing.
        assertEquals(setOf("a", "b"), adapter.fetchedUrls.map { it.substringAfterLast("/").removeSuffix(".html") }.toSet())
    }

    @Test
    fun `the fabricated legacy 4-hour placeholder counts as unknown and is enriched`() {
        runBlocking { insertLibraryBooks(listOf(book("legacy", DurationBuckets.FABRICATED_LEGACY_SECONDS))) }
        val adapter = FakeBookAdapter(DIRECT_SOURCE, mapOf("https://sound-books.net/legacy.html" to 9000L))

        assertEquals(1, runEnrich(repo(adapter)))
        assertEquals(9000L, durationOf("legacy"))
    }

    // ---------------------------------------------------------------------
    // #740 — routing by the book's own sourceId and honest degradation
    // ---------------------------------------------------------------------

    @Test
    fun `each book is fetched through its own source adapter`() {
        runBlocking {
            insertLibraryBooks(
                listOf(
                    book("sb", 0L, url = "https://sound-books.net/sb.html"),
                    book("sl", 0L, url = "https://sluhay.com.ua/sl")
                )
            )
        }
        val soundBooks = FakeBookAdapter("soundbooks", mapOf("https://sound-books.net/sb.html" to 3_600L))
        val sluhayUa = FakeBookAdapter("sluhayua", mapOf("https://sluhay.com.ua/sl" to 5_400L))

        assertEquals(2, runEnrich(repo(listOf(soundBooks, sluhayUa))))

        assertEquals(listOf("https://sound-books.net/sb.html"), soundBooks.fetchedUrls)
        assertEquals(listOf("https://sluhay.com.ua/sl"), sluhayUa.fetchedUrls)
        assertEquals(3_600L, durationOf("sb"))
        assertEquals(5_400L, durationOf("sl"))
    }

    @Test
    fun `a browser-gated scam source is never fetched and does not break the pass`() {
        runBlocking {
            insertLibraryBooks(
                listOf(
                    book("bad", 0L, url = "https://4read.org/bad.html"),
                    book("ok", 0L, url = "https://sound-books.net/ok.html")
                )
            )
        }
        // Even with an adapter registered for the source, the pass must not
        // touch a browser/scam source (ADR-0039, #741).
        val fourRead = FakeBookAdapter("4read", mapOf("https://4read.org/bad.html" to 1_000L))
        val soundBooks = FakeBookAdapter("soundbooks", mapOf("https://sound-books.net/ok.html" to 2_000L))

        assertEquals(1, runEnrich(repo(listOf(fourRead, soundBooks))))

        assertTrue(fourRead.fetchedUrls.isEmpty())
        assertEquals(listOf("https://sound-books.net/ok.html"), soundBooks.fetchedUrls)
        assertEquals(0L, durationOf("bad"))
        assertEquals(2_000L, durationOf("ok"))
    }

    @Test
    fun `a source without an adapter degrades honestly, without a request`() {
        runBlocking {
            insertLibraryBooks(listOf(book("orphan", 0L, url = "https://unknown.example/orphan.html")))
        }
        val soundBooks = FakeBookAdapter("soundbooks", emptyMap())

        assertEquals(0, runEnrich(repo(soundBooks)))
        assertTrue(soundBooks.fetchedUrls.isEmpty())
        assertEquals(0L, durationOf("orphan"))
    }

    @Test
    fun `the registry decides which sources are implicitly unfetchable`() {
        // 4read is browser-gated and a scam: never an implicit background fetch.
        assertTrue(DurationEnrichment.isImplicitlyUnfetchable("4read"))
        // Direct sources are enrichable; an unknown id is unknown, not banned.
        assertFalse(DurationEnrichment.isImplicitlyUnfetchable("soundbooks"))
        assertFalse(DurationEnrichment.isImplicitlyUnfetchable("sluhayua"))
        assertFalse(DurationEnrichment.isImplicitlyUnfetchable("unknown"))
    }

    // ---------------------------------------------------------------------
    // Failure isolation and honest writes
    // ---------------------------------------------------------------------

    @Test
    fun `a failing fetch leaves the row untouched and does not abort the batch`() {
        runBlocking {
            insertLibraryBooks(listOf(book("fail", 0L), book("ok", 0L)))
        }
        val adapter = FakeBookAdapter(DIRECT_SOURCE, mapOf("https://sound-books.net/ok.html" to 5400L))
        val repo = repo(adapter)

        assertEquals(1, runEnrich(repo))
        assertEquals(0L, durationOf("fail"))
        assertEquals(5400L, durationOf("ok"))
    }

    @Test
    fun `a page without a duration never writes zero over the row`() {
        runBlocking { insertLibraryBooks(listOf(book("noduration", 0L))) }
        val adapter = FakeBookAdapter(DIRECT_SOURCE, mapOf("https://sound-books.net/noduration.html" to null))
        val repo = repo(adapter)

        assertEquals(0, runEnrich(repo))
        assertEquals(0L, durationOf("noduration"))
    }

    @Test
    fun `local imports with a blank source url are skipped, never fetched`() {
        runBlocking { insertLibraryBooks(listOf(book("local", 0L, url = ""))) }
        val adapter = FakeBookAdapter(DIRECT_SOURCE, emptyMap())
        val repo = repo(adapter)

        assertEquals(0, runEnrich(repo))
        assertTrue(adapter.fetchedUrls.isEmpty())
        assertEquals(0L, durationOf("local"))
    }

    // ---------------------------------------------------------------------
    // Bounded batch and throttle
    // ---------------------------------------------------------------------

    @Test
    fun `the pass respects its batch limit`() {
        runBlocking {
            insertLibraryBooks((1..5).map { book("b$it", 0L) })
        }
        val adapter = FakeBookAdapter(
            DIRECT_SOURCE,
            (1..5).associate { "https://sound-books.net/b$it.html" to 3600L }
        )
        val repo = repo(adapter)

        assertEquals(2, runEnrich(repo, batchLimit = 2))
        assertEquals(2, adapter.fetchedUrls.size)
        assertEquals(
            3600L,
            durationOf(adapter.fetchedUrls.first().substringAfterLast("/").removeSuffix(".html"))
        )
    }

    @Test
    fun `the pass throttles itself between runs`() {
        runBlocking {
            insertLibraryBooks((1..4).map { book("c$it", 0L) })
        }
        val adapter = FakeBookAdapter(
            DIRECT_SOURCE,
            (1..4).associate { "https://sound-books.net/c$it.html" to 3600L }
        )
        val repo = repo(adapter)
        val interval = DurationEnrichment.MIN_ENRICHMENT_INTERVAL_MS

        // First run: enriches the first two (batch limit 2).
        assertEquals(2, runEnrich(repo, batchLimit = 2, now = START_EPOCH))
        // Second run within the interval: throttled to a no-op.
        assertEquals(0, runEnrich(repo, batchLimit = 2, now = START_EPOCH + interval - 1L))
        // After the interval elapses, the pass runs again and fills the rest.
        assertEquals(2, runEnrich(repo, batchLimit = 2, now = START_EPOCH + interval + 1L))
        assertEquals(4, adapter.fetchedUrls.size)
    }

    companion object {
        /** A direct (enrichable) registry source — never 4read. */
        private const val DIRECT_SOURCE = "soundbooks"

        /** Realistic epoch so the very first pass is never throttled. */
        private const val START_EPOCH = 1_700_000_000_000L
    }

    @Test
    fun `no unknown-duration books means nothing is fetched`() {
        runBlocking { insertLibraryBooks(listOf(book("known", 3600L), book("also", 86_400L))) }
        val adapter = FakeBookAdapter(DIRECT_SOURCE, emptyMap())
        val repo = repo(adapter)

        assertEquals(0, runEnrich(repo))
        assertTrue(adapter.fetchedUrls.isEmpty())
        assertEquals("existing durations are never touched", 3600L, durationOf("known"))
    }

    // --- T4 (#219): derived duration writes back to the shared base --------

    @Test
    fun `a derived duration is written back to the shared store`() = runBlocking {
        val store = FakeSharedBookMetaStore()
        val adapter = FakeBookAdapter(DIRECT_SOURCE, mapOf("https://sound-books.net/b1.html" to 3_600L))
        insertLibraryBooks(listOf(book("b1", durationSeconds = 0L)))

        runEnrich(repo(adapter, store))

        assertEquals(1, store.durationPuts.size)
        val (editionId, duration, provenance) = store.durationPuts.single()
        assertEquals(3_600L, duration)
        assertEquals(DIRECT_SOURCE, provenance.source)
        assertEquals(DurationProvenance.METHOD_SOURCE_METADATA, provenance.method)
        // The Edition id matches the book's rendition identity.
        assertEquals(EditionId.forBook(MergeKey.keyFor("Книга b1", "Автор"), "b1", ""), editionId)
    }

    @Test
    fun `a failing shared write never breaks the enrichment pass`() = runBlocking {
        val store = FakeSharedBookMetaStore(throwOnPut = true)
        val adapter = FakeBookAdapter(DIRECT_SOURCE, mapOf("https://sound-books.net/b1.html" to 3_600L))
        insertLibraryBooks(listOf(book("b1", durationSeconds = 0L)))

        val enriched = runEnrich(repo(adapter, store))

        assertEquals(1, enriched)
        assertEquals(3_600L, durationOf("b1"))
    }
}
