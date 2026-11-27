package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityCatalogOnlyKnownImportRoomTest {
    @Test(timeout = 60_000)
    fun `catalog only exact source claim reserves its Work during import and cancellation releases the same plan`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-only-known-import-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.upsertWork(WorkEntity(A, KEY_A, "Old A", "Shared Author", addedAt = 10L))
            dao.upsertWork(WorkEntity(B, KEY_B, "Old B", "Shared Author", addedAt = 20L))
            dao.upsertWorkSource(WorkSourceEntity("opaque-catalog-claim-a", A, SOURCE, URL_A, addedAt = 10L))
            dao.upsertWorkSource(WorkSourceEntity("opaque-catalog-claim-b", B, SOURCE, URL_B, addedAt = 20L))
            dao.refreshWorkSearchIndex(A)
            dao.refreshWorkSearchIndex(B)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val gate = ManualWorkMergeGate(database)
            val commands = ManualWorkMergeCommands(database, gate)
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: catalog-only participants are unambiguous", prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(A, B), prepared.preview.participants.map { it.work.id })
            assertTrue(prepared.preview.snapshot.library.books.isEmpty())
            assertTrue(prepared.preview.snapshot.library.entries.isEmpty())
            assertTrue(prepared.preview.snapshot.library.editions.isEmpty())
            assertTrue(prepared.preview.snapshot.library.sources.isEmpty())
            assertEquals(listOf("opaque-catalog-claim-a", "opaque-catalog-claim-b"),
                prepared.preview.snapshot.catalog.workSources.map { it.id }.sorted())
            val plan = ManualWorkMergeApprovedPlan(prepared, OPERATION, "Same Work", "Shared Author")
            val beforeRows = typedRows(dao.readManualWorkMergeRows(listOf(A, B)))
            val identity = database.workIdentityDao()
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = HeldPage()
            val publications = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter),
                onWorkImported = { publications += "work:$it" },
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, gate))
            val cancellation = ImportCancellation("catalog-only held import cancelled")
            val observed = CompletableDeferred<CancellationException>()
            val job = launch {
                try {
                    importer.importFromSourceUrl(SOURCE, URL_A,
                        onNewBookImported = { publications += "book:${it.id}" })
                    fail("held import must not return")
                } catch (failure: CancellationException) {
                    observed.complete(failure)
                    throw failure
                }
            }
            try {
                withTimeout(10_000) { adapter.entered.await() }
                assertTrue("SETUP: public import is held in the actual adapter", job.isActive)
                assertEquals(listOf(URL_A), adapter.requests)
                assertEquals(emptyList<String>(), publications)
                assertEquals("968_CATALOG_ONLY_KNOWN_SOURCE_FETCH_MUST_BLOCK_APPLY",
                    ManualWorkMergeApplyResult.NeedsDecision("A participant has active work"),
                    commands.applyManualWorkMerge(plan))
                assertEquals(beforeRows, typedRows(dao.readManualWorkMergeRows(listOf(A, B))))
                assertEquals(0, identity.operationCount())
                assertEquals(0, identity.redirectCount())
                job.cancel(cancellation)
                job.join()
                withTimeout(10_000) { adapter.exited.await() }
                assertSame("exact cancellation reaches public import caller", cancellation,
                    withTimeout(10_000) { observed.await() })
                assertEquals(beforeRows, typedRows(dao.readManualWorkMergeRows(listOf(A, B))))
                assertEquals(emptyList<String>(), publications)
                assertEquals(0, identity.operationCount())
                assertEquals(0, identity.redirectCount())
                assertEquals("same prepared public plan succeeds only after cancellation cleanup",
                    ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(plan))
                val merged = dao.readManualWorkMergeRows(listOf(C))
                assertEquals(listOf(C), merged.library.works.map { it.id })
                assertNull(dao.getWorkById(A))
                assertNull(dao.getWorkById(B))
                assertTrue(merged.library.books.isEmpty())
                assertTrue(merged.library.entries.isEmpty())
                assertTrue(merged.library.editions.isEmpty())
                assertTrue(merged.library.sources.isEmpty())
                assertEquals(listOf(SOURCE to URL_A, SOURCE to URL_B),
                    merged.catalog.workSources.map { it.sourceId to it.sourceUrl }.sortedBy { it.second })
                assertEquals(WorkMergeOperationState.APPLIED, identity.operationStateById(OPERATION))
                assertTrue(identity.preservedBooks(OPERATION).isEmpty())
                assertTrue(identity.preservedParticipants(OPERATION).isEmpty())
                assertEquals(emptyList<String>(), publications)
                assertEquals(listOf(URL_A), adapter.requests)
            } finally {
                job.cancelAndJoin()
            }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private class ImportCancellation(message: String) : CancellationException(message),
        CopyableThrowable<ImportCancellation> {
        override fun createCopy(): ImportCancellation? = null
    }

    private class HeldPage : SourceAdapter {
        override val sourceId = SOURCE
        val requests = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            check(url == URL_A)
            requests += url
            entered.complete(Unit)
            try { awaitCancellation() } finally { exited.complete(Unit) }
        }
        override suspend fun search(query: String): List<SourceBook> = error("no search permitted")
        override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed permitted")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no crawl permitted")
    }

    private companion object {
        const val A = "catalog-only-work-a"
        const val B = "catalog-only-work-b"
        const val KEY_A = "old a|shared author"
        const val KEY_B = "old b|shared author"
        const val C = "same work|shared author"
        const val OPERATION = "catalog-only-known-import-operation"
        const val SOURCE = "sluhayua"
        const val URL_A = "https://sluhay.com.ua/100:alias-a"
        const val URL_B = "https://sluhay.com.ua/200:alias-b"
    }
}
