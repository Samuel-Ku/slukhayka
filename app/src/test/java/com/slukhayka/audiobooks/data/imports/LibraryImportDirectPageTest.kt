package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.entries.matchingLibraryQuery
import com.slukhayka.audiobooks.data.personbookmarks.PersonIdentity
import com.slukhayka.audiobooks.data.personbookmarks.PersonNewArrivals
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #477 — the import layer of the best-effort direct browser-source page door,
 * exercised through a non-scam browser source (sluhay):
 *
 * - a directly fetchable page imports silently (no browser) and a REPEATED
 *   tap — which legitimately fetches the page again — merges into the SAME
 *   Work/Edition/Source rows (the MergeKey contract of #470, never a fork);
 * - a challenged/failed fetch and an empty playlist import nothing — the
 *   honest browser door stays, the library stays untouched;
 * - the scam source (4read) never reaches the door: no fetch, no rows.
 *
 * One fetch per call is pinned too: the door never caches or fabricates a
 * result, the transport discipline stays at the caller (coordinator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryImportDirectPageTest {

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

    private class FakeBrowserAdapter(
        override val sourceId: String,
        private val page: () -> SourceBookDetail
    ) : SourceAdapter {
        var fetchCalls = 0
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchCalls += 1
            return page()
        }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private fun detail() = SourceBookDetail(
        title = "Пані Боварі",
        author = "Гюстав Флобер",
        narrator = "Іван Франко",
        url = "https://sluhay.com/bovari",
        chapters = listOf(SourceChapter("Розділ 1", "https://redirectto.cc/bovari/1.mp3"))
    )

    private fun imports(adapter: SourceAdapter) = LibraryImport(dao, context, listOf(adapter))

    @Test
    fun `a directly fetchable page imports silently and a repeated tap never forks`() = runBlocking {
        val adapter = FakeBrowserAdapter("sluhay") { detail() }
        val imports = imports(adapter)

        // Tap 1: the direct page resolves and imports without a browser.
        val first = imports.importBrowserSourceDirectPage("sluhay", detail().url)
        assertTrue(first != null)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        val workId = requireNotNull(first!!.workId)
        val carriers = dao.getWorkSourcesForWorkSync(workId)
        assertEquals(1, carriers.size)
        val admitted = carriers.single()
        assertEquals("sluhay", admitted.sourceId)
        assertEquals(detail().url, admitted.sourceUrl)
        assertEquals("$workId|sluhay|${Integer.toHexString(detail().url.hashCode())}", admitted.id)
        assertEquals(1, dao.countWorkSources())

        // Tap 2 (another day, challenge passed): the page is fetched again —
        // one request, no fabricated cache — and merges into the SAME rows.
        val second = imports.importBrowserSourceDirectPage("sluhay", detail().url)
        assertEquals(2, adapter.fetchCalls)
        assertEquals(first!!.id, second!!.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)

        val bookId = first.id
        val sources = dao.getSourcesForBookSync(bookId).filter { it.type == "sluhay" }
        assertEquals(1, sources.size)
        assertTrue(dao.getEditionForWork(bookId) != null)
        assertEquals(1, dao.countWorkSources())
        assertEquals(admitted.id, dao.getWorkSourcesForWorkSync(workId).single().id)
        assertEquals(detail().url, dao.getWorkSourcesForWorkSync(workId).single().sourceUrl)
    }

    @Test
    fun `direct page preserves admitted catalogue metadata until it has a legitimate new claim`() = runBlocking {
        var page = detail()
        val adapter = FakeBrowserAdapter("sluhay") { page }
        val imports = imports(adapter)
        val catalog = SourceCatalog(dao, emptyList(), imports)
        val catalogWork = catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = page.title,
            author = page.author,
            narrator = page.narrator,
            sourceUrl = page.url,
            streamOnly = streamOnlyFor("sluhay"),
            coverImageUrl = "https://sluhay.com/covers/bovari.jpg",
            durationSeconds = 7_200L
        ).work
        val admitted = dao.getWorkSourcesForWorkSync(catalogWork.id).single()
        assertEquals(1, dao.countWorkSources())

        // The ordinary own-page door has a playable chapter, but no cover/duration claim.
        val first = requireNotNull(imports.importBrowserSourceDirectPage("sluhay", page.url))
        assertEquals(catalogWork.id, first.workId)
        assertEquals(admitted, dao.getWorkSourcesForWorkSync(catalogWork.id).single())
        val repeated = requireNotNull(imports.importBrowserSourceDirectPage("sluhay", page.url))
        assertEquals(first.id, repeated.id)
        assertEquals(admitted, dao.getWorkSourcesForWorkSync(catalogWork.id).single())
        assertEquals(1, dao.countWorkSources())

        // Blank cover and the legacy duration sentinel are absent claims too.
        page = page.copy(coverImageUrl = "  ", totalDurationSeconds = 14_400L)
        val absentClaim = requireNotNull(imports.importBrowserSourceDirectPage("sluhay", page.url))
        assertEquals(first.id, absentClaim.id)
        assertEquals(admitted, dao.getWorkSourcesForWorkSync(catalogWork.id).single())

        // A real new own-page claim may update only the metadata, after normalization.
        page = page.copy(
            coverImageUrl = "  https://sluhay.com/covers/bovari-new.jpg  ",
            totalDurationSeconds = 8_100L
        )
        val freshClaim = requireNotNull(imports.importBrowserSourceDirectPage("sluhay", page.url))
        assertEquals(first.id, freshClaim.id)
        assertEquals(
            admitted.copy(coverImageUrl = "https://sluhay.com/covers/bovari-new.jpg", durationSeconds = 8_100L),
            dao.getWorkSourcesForWorkSync(catalogWork.id).single()
        )
        assertEquals(4, adapter.fetchCalls)
        assertEquals(1, dao.countWorkSources())
        assertEquals(catalogWork, dao.getWorkById(catalogWork.id))
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `own claim reads the latest catalog carrier inside its merge transaction`() = runBlocking {
        val databaseName = "work-source-overlap-${UUID.randomUUID()}"
        val armed = AtomicBoolean(false)
        val gateTimedOut = AtomicBoolean(false)
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        val mergeDb = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.startsWith("BEGIN") && armed.compareAndSet(true, false)) {
                        entered.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) gateTimedOut.set(true)
                    }
                }
            }, Executor { it.run() })
            .build()
        val catalogDb = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            // Independent Room queues over ONE real SQLite file let the catalog commit
            // while the other connection is paused BEFORE acquiring its write transaction.
            val mergeDao = mergeDb.audiobookDao()
            val catalogDao = catalogDb.audiobookDao()
            val catalog = SourceCatalog(catalogDao, emptyList(), LibraryImport(catalogDao, context, emptyList()))
            suspend fun admit(cover: String, duration: Long): WorkSourceEntity {
                val work = catalog.writeWorkEdition(
                    sourceId = "sluhay", title = detail().title, author = detail().author,
                    narrator = detail().narrator, sourceUrl = detail().url,
                    streamOnly = streamOnlyFor("sluhay"), coverImageUrl = cover, durationSeconds = duration
                ).work
                return catalogDao.getWorkSourcesForWorkSync(work.id).single()
            }
            val oldCover = "https://sluhay.com/covers/bovari.jpg"
            val newCover = "https://sluhay.com/covers/bovari-new.jpg"
            val original = admit(oldCover, 7_200L)
            // Open the merge connection before arming; schema/open callbacks are not the barrier.
            assertEquals(original, mergeDao.getWorkSourceById(original.id))

            // Execute the previous public read→guarded REPLACE seam as a causal control.
            // The snapshot is genuinely read BEFORE the later catalog admission.
            val stale = requireNotNull(mergeDao.getWorkSourceById(original.id))
            armed.set(true)
            val oldWrite = async(Dispatchers.IO) { mergeDao.safeUpsertWorkSource(stale) }
            try {
                assertTrue("old merge must reach its pre-lock transaction barrier", entered.await(15, TimeUnit.SECONDS))
                assertFalse("the old write is still held before later admission", oldWrite.isCompleted)
                val later = withTimeout(15_000) { admit(newCover, 8_100L) }
                release.countDown()
                assertTrue(withTimeout(15_000) { oldWrite.await() })
                assertEquals(stale, mergeDao.getWorkSourceById(stale.id))
                assertFalse("control must demonstrate erased newer metadata", later == mergeDao.getWorkSourceById(stale.id))
            } finally {
                release.countDown()
                withTimeout(15_000) { oldWrite.await() }
            }
            assertFalse("control barrier must never time out", gateTimedOut.get())

            // SAME pre-lock schedule, now with an absent own claim and atomic preservation.
            admit(oldCover, 7_200L)
            entered = CountDownLatch(1)
            release = CountDownLatch(1)
            val ownClaim = original.copy(coverImageUrl = null, durationSeconds = null)
            armed.set(true)
            val atomicWrite = async(Dispatchers.IO) { mergeDao.mergeAdmittedWorkSource(ownClaim) }
            try {
                assertTrue("atomic merge must reach its pre-lock transaction barrier", entered.await(15, TimeUnit.SECONDS))
                assertFalse("the atomic write is still held before later admission", atomicWrite.isCompleted)
                val later = withTimeout(15_000) { admit(newCover, 8_100L) }
                release.countDown()
                assertTrue(withTimeout(15_000) { atomicWrite.await() })
                assertEquals(later, mergeDao.getWorkSourceById(later.id))
                assertEquals(1, mergeDao.countWorkSources())
                assertEquals(1, mergeDao.workSearchRowCount())
                assertEquals(listOf(later.workId), mergeDao.matchWorkSearch("боварі*", 10))
            } finally {
                release.countDown()
                withTimeout(15_000) { atomicWrite.await() }
            }
            assertFalse("atomic barrier must never time out", gateTimedOut.get())
        } finally {
            release.countDown()
            mergeDb.close()
            catalogDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `carrier merge rolls back when the real search index refresh fails`() = runBlocking {
        val catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
        val work = catalog.writeWorkEdition(
            sourceId = "sluhay", title = detail().title, author = detail().author,
            narrator = detail().narrator, sourceUrl = detail().url,
            streamOnly = streamOnlyFor("sluhay"),
            coverImageUrl = "https://sluhay.com/covers/bovari.jpg", durationSeconds = 7_200L
        ).work
        val admitted = dao.getWorkSourcesForWorkSync(work.id).single()
        var page = detail()
        val imports = imports(FakeBrowserAdapter("sluhay") { page })
        val first = requireNotNull(imports.importBrowserSourceDirectPage("sluhay", page.url))
        assertEquals(admitted, dao.getWorkSourceById(admitted.id))
        assertEquals(1, dao.workSearchRowCount())
        // A real SQLite storage error AFTER carrier upsert, at the index-refresh SQL.
        // This private in-memory fixture owns the removed table; no DAO is replaced.
        db.openHelper.writableDatabase.execSQL("DROP TABLE works_fts")
        val result = runCatching {
            dao.mergeAdmittedWorkSource(admitted.copy(
                coverImageUrl = "https://sluhay.com/covers/bovari-new.jpg", durationSeconds = 8_100L
            ))
        }
        assertTrue("storage error must escape the transaction", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("works_fts"))
        assertEquals(admitted, dao.getWorkSourceById(admitted.id))
        assertEquals(1, dao.countWorkSources())
        assertEquals(work, dao.getWorkById(work.id))

        // The public import contains the metadata failure only AFTER the rollback.
        page = page.copy(coverImageUrl = "https://sluhay.com/covers/bovari-new.jpg", totalDurationSeconds = 8_100L)
        val repeated = requireNotNull(imports.importBrowserSourceDirectPage("sluhay", page.url))
        assertEquals(first.id, repeated.id)
        assertEquals(admitted, dao.getWorkSourceById(admitted.id))
        assertEquals(1, dao.countWorkSources())
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `imported narrator edition projects through library entry to its Work`() = runBlocking {
        val page = detail()
        imports(FakeBrowserAdapter("sluhay") { page }).importBrowserSourceDirectPage("sluhay", page.url)
        val works = dao.observeWorks().first()
        val editions = dao.observeEditions().first()
        val entries = dao.observeLibraryEntries().first()
        assertTrue(works.single().id != editions.single().workId)
        val person = PersonIdentity.from(PersonRole.NARRATOR, page.narrator)
        val projection = PersonNewArrivals.projectCatalog(
            bookmarks = listOf(PersonBookmarkEntity(
                person.role.storageValue, person.id, person.displayName, person.normalizedName
            )),
            works = works, editions = editions, libraryEntries = entries,
            unifiedCatalog = listOf(GlobalSearchResult(
                title = page.title, author = page.author, narrator = page.narrator,
                mergeKey = works.single().mergeKey,
                sources = listOf(GlobalSearchSource("sluhay", "Sluhay", page.url))
            ))
        )
        assertEquals(1, projection.count)
        assertEquals(setOf(person.id), projection.bookmarkKeys.map { it.id }.toSet())
    }

    @Test
    fun `a challenged page imports nothing - the honest browser door stays`() = runBlocking {
        val adapter = FakeBrowserAdapter("sluhay") { error("403 / Cloudflare challenge") }
        val imports = imports(adapter)

        val result = imports.importBrowserSourceDirectPage("sluhay", "https://sluhay.com/bovari")

        assertNull(result)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `an empty playlist imports nothing`() = runBlocking {
        val adapter = FakeBrowserAdapter("sluhay") {
            SourceBookDetail(
                title = "Пані Боварі",
                author = "Гюстав Флобер",
                url = "https://sluhay.com/bovari",
                chapters = emptyList()
            )
        }
        val imports = imports(adapter)

        val result = imports.importBrowserSourceDirectPage("sluhay", "https://sluhay.com/bovari")

        assertNull(result)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `the scam source never reaches the direct-page door`() = runBlocking {
        val adapter = FakeBrowserAdapter("4read") { detail() }
        val imports = imports(adapter)

        val result = imports.importBrowserSourceDirectPage("4read", "https://4read.org/bovari")

        assertNull(result)
        assertEquals("no fetch for a scam source", 0, adapter.fetchCalls)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    /**
     * #737 — a live source hit imported through the ordinary door becomes a
     * LOCAL search hit: the same row the library-first section matches
     * offline, so the second search never needs the network again.
     */
    @Test
    fun `an imported source hit is found by the library-first search`() = runBlocking {
        val adapter = FakeBrowserAdapter("sluhay") { detail() }
        val imports = imports(adapter)

        val booksBefore = dao.getAllAudiobooks().first().map { it.toAudiobookEntity() }
        assertTrue(booksBefore.matchingLibraryQuery("Боварі").isEmpty())

        imports.importBrowserSourceDirectPage("sluhay", detail().url)

        val booksAfter = dao.getAllAudiobooks().first().map { it.toAudiobookEntity() }
        assertEquals(1, booksAfter.matchingLibraryQuery("Боварі").size)
        assertEquals(1, booksAfter.matchingLibraryQuery("Флобер").size)
        assertTrue(booksAfter.matchingLibraryQuery("Місто").isEmpty())
    }
}
