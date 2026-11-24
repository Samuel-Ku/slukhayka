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
class ManualWorkIdentityCatalogTombstoneFeedPublicationRoomTest {
    @Test(timeout = 180_000)
    fun `source feed omits an old source card whose canonical Work was tombstoned after merge and reopen`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-tombstone-feed-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.upsertWork(WorkEntity(A, KEY_A, "Old A", "Shared Author", addedAt = 10L))
            dao.upsertWork(WorkEntity(B, KEY_B, "Old B", "Shared Author", addedAt = 20L))
            dao.upsertWorkSource(WorkSourceEntity("opaque-catalog-a", A, SOURCE, URL_A, addedAt = 10L))
            dao.upsertWorkSource(WorkSourceEntity("opaque-catalog-b", B, SOURCE, URL_B, addedAt = 20L))
            dao.refreshWorkSearchIndex(A)
            dao.refreshWorkSearchIndex(B)
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: valid catalog-only roots", prepared.preview.conflicts.isEmpty())
            assertTrue(prepared.preview.snapshot.library.books.isEmpty())
            assertTrue(prepared.preview.snapshot.library.entries.isEmpty())
            assertTrue(prepared.preview.snapshot.library.editions.isEmpty())
            assertEquals(ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(
                ManualWorkMergeApprovedPlan(prepared, OPERATION, "Same Work", "Shared Author")))
            val deleted = TombstoneEntity(C, 500L)
            dao.insertTombstone(deleted)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val gate = ManualWorkMergeGate(database)
            val access = WorkIdentityAccess(database, gate)
            val canonical = checkNotNull(dao.getWorkById(C))
            assertEquals(WorkResolution.Resolved(canonical), access.resolve(WorkIdentityInput.WorkId(A)))
            assertEquals(WorkResolution.Resolved(canonical), access.resolve(WorkIdentityInput.MergeKey(KEY_A)))
            val snapshot = dao.readManualWorkMergeRows(listOf(C))
            assertEquals(listOf(deleted), snapshot.personal.tombstones)
            assertTrue(snapshot.library.books.isEmpty())
            assertTrue(snapshot.library.entries.isEmpty())
            assertTrue(snapshot.library.editions.isEmpty())
            assertTrue(snapshot.library.sources.isEmpty())
            assertEquals(listOf(SOURCE to URL_A, SOURCE to URL_B),
                snapshot.catalog.workSources.map { it.sourceId to it.sourceUrl }.sortedBy { it.second })
            val beforeRows = typedRows(snapshot)
            val identity = database.workIdentityDao()
            val beforeAudit = audit(identity)
            assertEquals(1, identity.operationCount())
            assertEquals(WorkMergeOperationState.APPLIED, identity.operationStateById(OPERATION))
            assertEquals(4, identity.redirectCount())
            assertTrue(identity.preservedParticipants(OPERATION).isEmpty())
            assertTrue(identity.preservedBooks(OPERATION).isEmpty())
            val adapter = OneNewCard()
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = access)
            assertFalse("SETUP: this source never needs a browser CTA", adapter.sessionBound)
            assertTrue(catalog.sourceFeeds.value.isEmpty())
            assertTrue(catalog.newArrivals.value.isEmpty())
            val feeds = catalog.refreshSourceFeeds(forceRefresh = true)
            assertEquals("968_TOMBSTONED_CATALOG_ITEM_MUST_NOT_BE_PUBLISHED_IN_SOURCE_FEED", 0,
                catalog.sourceFeeds.value.sumOf { it.books.size })
            assertEquals(emptyList<SourceCatalog.SourceNewFeed>(), feeds)
            assertEquals(emptyList<SourceCatalog.SourceNewFeed>(), catalog.sourceFeeds.value)
            assertTrue("new-arrivals rail must not publish the refused card", catalog.newArrivals.value.isEmpty())
            assertEquals(beforeRows, typedRows(dao.readManualWorkMergeRows(listOf(C))))
            assertEquals(beforeAudit, audit(identity))
            assertEquals(listOf(deleted), dao.readManualWorkMergeRows(listOf(C)).personal.tombstones)
            assertEquals(listOf(20), adapter.newLimits)
            assertEquals(emptyList<String>(), adapter.pageRequests)
            assertEquals(emptyList<String>(), adapter.searchQueries)
            assertEquals(emptyList<Int>(), adapter.catalogLimits)
            assertNull(dao.getWorkById(A))
            assertNull(dao.getWorkById(B))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun audit(identity: WorkIdentityDao): List<Any?> = listOf(
        identity.operationCount(), identity.operationStateById(OPERATION), identity.redirectCount(),
        identity.preservedParticipants(OPERATION), identity.preservedBooks(OPERATION),
        listOf("WORK_ID" to A, "WORK_ID" to B, "MERGE_KEY" to KEY_A, "MERGE_KEY" to KEY_B,
            "WORK_ID" to C, "MERGE_KEY" to C).map { (namespace, key) -> identity.redirectByAlias(namespace, key) },
    )

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

    private class OneNewCard : SourceAdapter {
        override val sourceId = SOURCE
        override val sessionBound = false
        override val contentLanguage = "uk"
        val newLimits = mutableListOf<Int>()
        val pageRequests = mutableListOf<String>()
        val searchQueries = mutableListOf<String>()
        val catalogLimits = mutableListOf<Int>()
        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            newLimits += limit
            return listOf(SourceBook(title = "Old A", author = "Shared Author", narrator = "",
                url = URL_A, coverImageUrl = "https://example.test/catalog-feed-cover.jpg",
                sourceId = SOURCE, language = "uk"))
        }
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            pageRequests += url
            error("the explicit cover and source card need no page fetch")
        }
        override suspend fun search(query: String): List<SourceBook> {
            searchQueries += query
            error("no search permitted")
        }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
            catalogLimits += limit
            error("no catalog fetch permitted")
        }
    }

    private companion object {
        const val A = "catalog-tomb-work-a"
        const val B = "catalog-tomb-work-b"
        const val KEY_A = "old a|shared author"
        const val KEY_B = "old b|shared author"
        const val C = "same work|shared author"
        const val OPERATION = "catalog-canonical-tombstone-operation"
        const val SOURCE = "sluhayua"
        const val URL_A = "https://sluhay.com.ua/100:alias-a"
        const val URL_B = "https://sluhay.com.ua/200:alias-b"
    }
}
