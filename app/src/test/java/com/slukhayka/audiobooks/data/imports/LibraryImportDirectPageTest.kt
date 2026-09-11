package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.personbookmarks.PersonIdentity
import com.slukhayka.audiobooks.data.personbookmarks.PersonNewArrivals
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
