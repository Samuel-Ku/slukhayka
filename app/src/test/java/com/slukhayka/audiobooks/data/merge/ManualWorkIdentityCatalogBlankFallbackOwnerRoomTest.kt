package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
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
class ManualWorkIdentityCatalogBlankFallbackOwnerRoomTest {
    @Test(timeout = 180_000)
    fun `blank catalog creation refuses an occupied foreign Work ID without an exact source claim`() = runBlocking {
        withFixture(hasExactClaim = false) { database, dao, catalog, adapter ->
            val before = typedRows(dao.readManualWorkMergeRows(listOf(K, C)))
            val auditBefore = listOf(database.workIdentityDao().operationCount(), database.workIdentityDao().redirectCount())
            var refusal: WorkIdentityWriteRefused? = null
            try {
                catalog.writeWorkEdition(sourceId = SOURCE, title = "", author = "", narrator = "", sourceUrl = URL)
            } catch (caught: WorkIdentityWriteRefused) {
                refusal = caught
            }
            assertEquals("968_BLANK_CREATION_CANNOT_ADOPT_FOREIGN_WORK", IdentityWriteRefusal.ConflictingSource, refusal?.refusal)
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(K, C))))
            assertEquals(auditBefore, listOf(database.workIdentityDao().operationCount(), database.workIdentityDao().redirectCount()))
            assertEquals(listOf(foreignClaim()), dao.getWorkSourcesForWorkSync(K))
            assertEquals(0, adapter.calls)
        }
    }

    @Test(timeout = 180_000)
    fun `an exact opaque source claim wins over an occupied unused blank creation ID`() = runBlocking {
        withFixture(hasExactClaim = true) { database, dao, catalog, adapter ->
            val before = typedRows(dao.readManualWorkMergeRows(listOf(K, C)))
            val auditBefore = listOf(database.workIdentityDao().operationCount(), database.workIdentityDao().redirectCount())
            val result = catalog.writeWorkEdition(sourceId = SOURCE, title = "", author = "", narrator = "", sourceUrl = URL)
            assertEquals("968_EXACT_CLAIM_OWNER_MUST_WIN_OVER_UNUSED_CREATION_ID", true, result.work.id == C)
            assertEquals(canonicalWork(), result.work)
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(K, C))))
            assertEquals(auditBefore, listOf(database.workIdentityDao().operationCount(), database.workIdentityDao().redirectCount()))
            assertEquals(listOf(exactClaim()), dao.getWorkSourcesForWorkSync(C))
            assertEquals(listOf(foreignClaim()), dao.getWorkSourcesForWorkSync(K))
            assertEquals(0, adapter.calls)
        }
    }

    private suspend fun withFixture(
        hasExactClaim: Boolean,
        block: suspend (AudiobookDatabase, AudiobookDao, SourceCatalog, NoFetch) -> Unit,
    ) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-blank-owner-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.upsertWork(WorkEntity(K, "foreign title|foreign author", "Foreign Title", "Foreign Author", addedAt = 11L))
            dao.upsertWorkSource(foreignClaim())
            if (hasExactClaim) {
                dao.upsertWork(canonicalWork())
                dao.upsertWorkSource(exactClaim())
            }
            dao.refreshWorkSearchIndex(K)
            if (hasExactClaim) dao.refreshWorkSearchIndex(C)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("foreign title|foreign author", dao.getWorkById(K)?.mergeKey)
            assertEquals(listOf(foreignClaim()), dao.getWorkSourcesForWorkSync(K))
            assertEquals(if (hasExactClaim) listOf(exactClaim()) else emptyList<WorkSourceEntity>(),
                dao.readManualWorkMergeRows(listOf(K, C)).catalog.workSources.filter { it.sourceId == SOURCE && it.sourceUrl == URL })
            val rows = dao.readManualWorkMergeRows(listOf(K, C))
            assertTrue(rows.library.books.isEmpty())
            assertTrue(rows.library.entries.isEmpty())
            assertTrue(rows.library.editions.isEmpty())
            assertTrue(rows.library.sources.isEmpty())
            val adapter = NoFetch()
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            if (hasExactClaim) {
                // Public idempotent write seeds the existing author's projection before the before-image.
                val projected = catalog.writeWorkEdition(sourceId = SOURCE,
                    title = "Canonical Title", author = "Canonical Author", narrator = "", sourceUrl = URL)
                assertEquals("SETUP: nonblank raw merge key resolves exact C", C, projected.work.id)
                assertEquals(canonicalWork(), projected.work)
                assertEquals(listOf(exactClaim()), dao.getWorkSourcesForWorkSync(C))
                assertEquals(listOf(foreignClaim()), dao.getWorkSourcesForWorkSync(K))
                assertEquals(0, adapter.calls)
            }
            block(database, dao, catalog, adapter)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun canonicalWork() = WorkEntity(C, "canonical title|canonical author", "Canonical Title", "Canonical Author", addedAt = 12L)
    private fun foreignClaim() = WorkSourceEntity("opaque-foreign-claim", K, "other-provider", "https://other.example/foreign", addedAt = 13L)
    private fun exactClaim() = WorkSourceEntity("opaque-exact-claim", C, SOURCE, URL, addedAt = 14L)

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

    private class NoFetch : SourceAdapter {
        override val sourceId = SOURCE
        var calls = 0
        override suspend fun fetchBookPage(url: String): SourceBookDetail { calls++; error("no page fetch") }
        override suspend fun search(query: String): List<SourceBook> { calls++; error("no search") }
        override suspend fun fetchNew(limit: Int): List<SourceBook> { calls++; error("no feed") }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> { calls++; error("no catalog") }
    }

    private companion object {
        const val SOURCE = "sluhayua"
        const val URL = "https://sluhay.com.ua/100:blank-owner"
        // Public writeWorkEdition blank-key stable Work-ID contract; fixed fixture, never an owner oracle.
        const val K = "w-sluhayua-2eeb8dd6"
        const val C = "opaque-canonical-owner"
    }
}
