package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityCatalogDefaultTransactionRoomTest {
    @Test(timeout = 180_000)
    fun `default legacy catalog write leaves the complete graph unchanged when author indexing rejects source length81`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-default-rejected-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val before = typedRows(dao.readManualWorkMergeRows(listOf(KEY)))
            assertEquals(81, INVALID_SOURCE.length)
            val adapter = NoFetch(INVALID_SOURCE)
            // Intentionally omit Access AND runner: exercise the real public default Room boundary.
            val catalog = SourceCatalog(dao, listOf(adapter), LibraryImport(dao, context, listOf(adapter)))
            var failure: IllegalArgumentException? = null
            try {
                catalog.writeWorkEdition(sourceId = INVALID_SOURCE, title = "Atomic Catalog", author = "Atomic Author",
                    narrator = "", sourceUrl = URL)
            } catch (caught: IllegalArgumentException) {
                failure = caught
            }
            assertNotNull("968_DEFAULT_CATALOG_REJECTS_INVALID_SOURCE_LENGTH", failure)
            assertEquals("968_DEFAULT_CATALOG_FAILURE_MUST_ROLL_BACK_ALL_ROWS", before,
                typedRows(dao.readManualWorkMergeRows(listOf(KEY))))
            assertNull(dao.getWorkById(KEY))
            assertTrue(dao.getWorkSourcesForWorkSync(KEY).isEmpty())
            assertTrue(dao.readManualWorkMergeRows(listOf(KEY)).catalog.ftsRows.isEmpty())
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(KEY))))
            assertNull(dao.getWorkById(KEY))
            assertTrue(dao.getWorkSourcesForWorkSync(KEY).isEmpty())
            assertTrue(dao.readManualWorkMergeRows(listOf(KEY)).catalog.ftsRows.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `default legacy catalog write accepts source length80 and durably commits its Work claim and index`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-default-accepted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            assertEquals(80, VALID_SOURCE.length)
            val adapter = NoFetch(VALID_SOURCE)
            val catalog = SourceCatalog(dao, listOf(adapter), LibraryImport(dao, context, listOf(adapter)))
            val result = catalog.writeWorkEdition(sourceId = VALID_SOURCE, title = "Atomic Catalog", author = "Atomic Author",
                narrator = "", sourceUrl = URL)
            assertEquals("968_DEFAULT_CATALOG_VALID_SOURCE_COMMITS_EXPECTED_WORK", KEY, result.work.id)
            assertEquals("atomic catalog|atomic author", result.work.mergeKey)
            assertEquals("Atomic Catalog", result.work.title)
            assertEquals("Atomic Author", result.work.author)
            assertEquals(result.work, dao.getWorkById(KEY))
            val claim = dao.getWorkSourcesForWorkSync(KEY).single()
            assertTrue(claim.id.isNotBlank())
            assertEquals(KEY, claim.workId)
            assertEquals(VALID_SOURCE, claim.sourceId)
            assertEquals(URL, claim.sourceUrl)
            val snapshot = dao.readManualWorkMergeRows(listOf(KEY))
            assertEquals(listOf(KEY), snapshot.catalog.ftsRows.map { it.workId })
            assertNotNull(dao.authorForWork(KEY))
            assertEquals("Atomic Author", dao.authorForWork(KEY)?.displayName)
            assertEquals(1, dao.authorForWork(KEY)?.workCount)
            assertTrue(snapshot.library.books.isEmpty())
            assertTrue(snapshot.library.entries.isEmpty())
            assertTrue(snapshot.library.editions.isEmpty())
            val committed = typedRows(snapshot)
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(result.work, dao.getWorkById(KEY))
            assertEquals(listOf(claim), dao.getWorkSourcesForWorkSync(KEY))
            assertEquals(committed, typedRows(dao.readManualWorkMergeRows(listOf(KEY))))
            assertEquals("Atomic Author", dao.authorForWork(KEY)?.displayName)
            assertEquals(1, dao.authorForWork(KEY)?.workCount)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    /** Order-independent typed row multisets retain duplicate counts and full entity values. */
    private fun typedRows(snapshot: ManualWorkMergeSnapshot): Map<String, Map<Any?, Int>> {
        val l = snapshot.library
        val c = snapshot.catalog
        val p = snapshot.personal
        val r = snapshot.retained
        val rows: Map<String, List<*>> = linkedMapOf(
            "works" to l.works, "entries" to l.entries, "books" to l.books, "editions" to l.editions,
            "sources" to l.sources, "tracks" to l.tracks, "chapters" to l.chapters, "progress" to l.progress,
            "bookmarks" to l.bookmarks, "settings" to l.settings, "readthroughs" to l.readthroughs,
            "events" to l.events, "failures" to l.failures, "submissions" to l.submissions,
            "workSources" to c.workSources, "series" to c.series, "seriesMembers" to c.seriesMembers,
            "universes" to c.universes, "workFacets" to c.workFacets, "facetSeries" to c.facetSeries,
            "genres" to c.genres, "genreStates" to c.genreStates, "genreAssertions" to c.genreAssertions,
            "workGenres" to c.workGenres, "editionFacets" to c.editionFacets, "authors" to c.authors,
            "authorAliases" to c.authorAliases, "ftsRows" to c.ftsRows, "popularity" to c.popularity,
            "vectors" to c.vectors, "corrections" to p.corrections, "tombstones" to p.tombstones,
            "preferences" to p.preferences, "collections" to p.collections, "collectionItems" to p.collectionItems,
            "feedSnapshots" to p.feedSnapshots, "stats" to r.stats, "sessions" to r.sessions,
            "achievements" to r.achievements, "achievementFacts" to r.achievementFacts,
            "achievementCounters" to r.achievementCounters, "personBookmarks" to r.personBookmarks,
            "hiddenReviewers" to r.hiddenReviewers, "friendships" to r.friendships, "socialBlocks" to r.socialBlocks,
        )
        return rows.mapValues { (_, values) -> values.groupingBy { it }.eachCount() }
    }

    private class NoFetch(override val sourceId: String) : SourceAdapter {
        var calls = 0
        override suspend fun fetchBookPage(url: String): SourceBookDetail { calls++; error("no page") }
        override suspend fun search(query: String): List<SourceBook> { calls++; error("no search") }
        override suspend fun fetchNew(limit: Int): List<SourceBook> { calls++; error("no feed") }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> { calls++; error("no catalog") }
    }

    private companion object {
        const val KEY = "atomic catalog|atomic author"
        const val URL = "https://example.test/atomic-catalog"
        const val VALID_SOURCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val INVALID_SOURCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
