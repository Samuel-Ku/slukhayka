package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
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
 * #477 — the import layer of the best-effort direct 4read page door:
 *
 * - a directly fetchable page imports silently (no browser) and a REPEATED
 *   tap — which legitimately fetches the page again — merges into the SAME
 *   Work/Edition/Source rows (the MergeKey contract of #470, never a fork);
 * - a challenged/failed fetch and an empty playlist import nothing — the
 *   honest browser door stays, the library stays untouched.
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

    private class FakeFourReadAdapter(
        private val page: () -> SourceBookDetail
    ) : SourceAdapter {
        override val sourceId: String = "4read"
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
        url = "https://4read.org/bovari",
        chapters = listOf(SourceChapter("Розділ 1", "https://s1.reasd.org/bovari/1.mp3"))
    )

    private fun imports(adapter: SourceAdapter) = LibraryImport(dao, context, listOf(adapter))

    @Test
    fun `a directly fetchable page imports silently and a repeated tap never forks`() = runBlocking {
        val adapter = FakeFourReadAdapter { detail() }
        val imports = imports(adapter)

        // Tap 1: the direct page resolves and imports without a browser.
        val first = imports.importBrowserSourceDirectPage("4read", detail().url)
        assertTrue(first != null)
        assertEquals(1, dao.getAllAudiobooks().first().size)

        // Tap 2 (another day, challenge passed): the page is fetched again —
        // one request, no fabricated cache — and merges into the SAME rows.
        val second = imports.importBrowserSourceDirectPage("4read", detail().url)
        assertEquals(2, adapter.fetchCalls)
        assertEquals(first!!.id, second!!.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)

        val bookId = first.id
        val sources = dao.getSourcesForBookSync(bookId).filter { it.type == "4read" }
        assertEquals(1, sources.size)
        assertTrue(dao.getEditionForWork(bookId) != null)
    }

    @Test
    fun `a challenged page imports nothing - the honest browser door stays`() = runBlocking {
        val adapter = FakeFourReadAdapter { error("403 / Cloudflare challenge") }
        val imports = imports(adapter)

        val result = imports.importBrowserSourceDirectPage("4read", "https://4read.org/bovari")

        assertNull(result)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `an empty playlist imports nothing`() = runBlocking {
        val adapter = FakeFourReadAdapter {
            SourceBookDetail(
                title = "Пані Боварі",
                author = "Гюстав Флобер",
                url = "https://4read.org/bovari",
                chapters = emptyList()
            )
        }
        val imports = imports(adapter)

        val result = imports.importBrowserSourceDirectPage("4read", "https://4read.org/bovari")

        assertNull(result)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }
}
