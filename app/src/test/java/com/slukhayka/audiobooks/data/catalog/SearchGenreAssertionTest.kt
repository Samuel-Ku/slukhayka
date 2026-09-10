package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.facets.GenreFacetAssertion
import com.slukhayka.audiobooks.data.facets.GenreSourceFacetReplacement
import com.slukhayka.audiobooks.data.facets.LocalFacetDelta
import com.slukhayka.audiobooks.data.facets.WorkFacetDelta
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
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
 * ADR-0040 — search cards carrying a claimed genre land a SEARCH-rank genre
 * document through the one facet door: the Work is anchored through the
 * merge-on-write door, an enumeration document always supersedes the search
 * document (rank, not time), tombstones block the write, and a blank genre
 * writes nothing. Best-effort: a facet failure never breaks search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchGenreAssertionTest {

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

    private fun catalog(adapter: SourceAdapter) = SourceCatalog(
        dao,
        listOf(adapter),
        LibraryImport(dao, context, emptyList())
    )

    private class FakeSearchAdapter(private val results: List<SourceBook>) : SourceAdapter {
        override val sourceId: String = "sluhay"
        override val contentLanguage: String = "uk"
        override suspend fun search(query: String): List<SourceBook> = results
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private fun kobzarBook(genre: String, url: String = "https://sluhay.com/kobzar") = SourceBook(
        title = "Кобзар",
        author = "Тарас Шевченко",
        url = url,
        genre = genre,
        sourceId = "sluhay"
    )

    @Test
    fun `a search hit with a claimed genre materializes the work and lands a search-rank assertion`() = runBlocking {
        val catalog = catalog(FakeSearchAdapter(listOf(kobzarBook("Детектив"))))

        catalog.searchAllSources("кобзар")

        val workId = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        val work = dao.findWorkByMergeKey(workId)
        assertEquals(workId, work?.id)
        val assertions = dao.genreAssertionsForWork(workId)
        assertEquals(listOf("Детектив"), assertions.map { it.rawText })
        assertTrue(assertions.all { it.sourceId == "sluhay" })
        assertEquals(
            listOf("detective"),
            dao.observeGenreFacetOptions().first().map { it.id }
        )
        // The document's rank is stored with the cursor.
        db.openHelper.writableDatabase.query(
            "SELECT provenance FROM genre_assertion_states WHERE workId=? AND sourceId='sluhay'",
            arrayOf(workId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("search", cursor.getString(0))
        }
    }

    @Test
    fun `a search hit never overwrites an existing enumeration genre set`() = runBlocking {
        val catalog = catalog(FakeSearchAdapter(listOf(kobzarBook("Фантастика"))))
        // The enumeration document lands first (catalogue hydration writes
        // the fuller set).
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            sourceUrl = "https://sluhay.com/kobzar",
            genreTexts = listOf("Фентезі", "Детективи")
        )

        catalog.searchAllSources("кобзар")

        assertEquals(
            setOf("detective", "fantasy"),
            dao.observeGenreFacetOptions().first().map { it.id }.toSet()
        )
    }

    @Test
    fun `an enumeration document landing later supersedes the search assertion`() = runBlocking {
        val catalog = catalog(FakeSearchAdapter(listOf(kobzarBook("Детектив"))))
        catalog.searchAllSources("кобзар")
        assertEquals(
            listOf("detective"),
            dao.observeGenreFacetOptions().first().map { it.id }
        )

        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            sourceUrl = "https://sluhay.com/kobzar",
            genreTexts = listOf("Фентезі")
        )

        assertEquals(
            listOf("fantasy"),
            dao.observeGenreFacetOptions().first().map { it.id }
        )
    }

    @Test
    fun `a tombstoned work is never resurrected by a search hit`() = runBlocking {
        val catalog = catalog(FakeSearchAdapter(emptyList()))
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            sourceUrl = "https://sluhay.com/kobzar"
        )
        val workId = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        dao.insertTombstone(TombstoneEntity(bookId = workId))
        val sourcesBefore = dao.getWorkSourcesForWorkSync(workId).size

        // The same Work surfaced by a NEW source search hit: the guard must
        // skip it — no source attachment, no genre assertion.
        val otherSource = FakeSearchAdapter(
            listOf(kobzarBook("Детектив", url = "https://sound-books.net/kobzar").copy(sourceId = "soundbooks"))
        )
        catalog(otherSource).searchAllSources("кобзар")

        assertEquals(sourcesBefore, dao.getWorkSourcesForWorkSync(workId).size)
        assertTrue(dao.genreAssertionsForWork(workId).isEmpty())
    }

    @Test
    fun `a blank genre writes nothing`() = runBlocking {
        val catalog = catalog(FakeSearchAdapter(listOf(kobzarBook("  "))))

        catalog.searchAllSources("кобзар")

        assertNull(dao.findWorkByMergeKey(MergeKey.keyFor("Кобзар", "Тарас Шевченко")))
        assertTrue(dao.observeGenreFacetOptions().first().isEmpty())
    }
}