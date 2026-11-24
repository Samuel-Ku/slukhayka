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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One real known-import activity lifecycle through public APIs; no direct gate activity calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityActiveKnownImportRoomTest {
    @Test(timeout = 60_000)
    fun `known source fetch blocks Apply and cancellation releases admission for the same plan`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-identity-active-import-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            seed(database.audiobookDao())
            database.close()
            database = open()
            val dao = database.audiobookDao()
            val gate = ManualWorkMergeGate(database)
            val access = WorkIdentityAccess(database, gate)
            val commands = ManualWorkMergeCommands(database, gate)
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: valid participants", prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(BOOK_A, BOOK_B), prepared.preview.snapshot.library.books.map { it.id }.sorted())
            val plan = ManualWorkMergeApprovedPlan(prepared, OPERATION, "Same Work", "Shared Author")
            val beforeRows = typedRows(dao.readManualWorkMergeRows(listOf(A, B)))
            val identity = database.workIdentityDao()
            val beforeAudit = listOf(identity.operationCount(), identity.redirectCount(),
                identity.operationStateById(OPERATION), identity.preservedParticipants(OPERATION),
                listOf(identity.redirectByAlias("WORK_ID", A), identity.redirectByAlias("WORK_ID", B),
                    identity.redirectByAlias("MERGE_KEY", A), identity.redirectByAlias("MERGE_KEY", B)))
            val adapter = HeldPage()
            val callbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = access)
            val cancelled = ImportCancellation("968 held known-source import cancelled by caller")
            val observedCancellation = CompletableDeferred<CancellationException>()
            val importJob = launch {
                try {
                    importer.importFromSourceUrl(SOURCE, URL_A, onNewBookImported = { callbacks += it.id })
                    fail("held import must not return normally")
                } catch (failure: CancellationException) {
                    observedCancellation.complete(failure)
                    throw failure
                }
            }
            try {
                withTimeout(10_000) { adapter.entered.await() }
                assertTrue("SETUP: import remains active during held page fetch", importJob.isActive)
                assertEquals(listOf(URL_A), adapter.requests)
                val duringFetch = commands.applyManualWorkMerge(plan)
                assertTrue("968_APPLY_MUST_NEED_DECISION_DURING_KNOWN_IMPORT_FETCH",
                    duringFetch is ManualWorkMergeApplyResult.NeedsDecision)
                assertEquals(beforeRows, typedRows(dao.readManualWorkMergeRows(listOf(A, B))))
                assertEquals(beforeAudit, listOf(identity.operationCount(), identity.redirectCount(),
                    identity.operationStateById(OPERATION), identity.preservedParticipants(OPERATION),
                    listOf(identity.redirectByAlias("WORK_ID", A), identity.redirectByAlias("WORK_ID", B),
                        identity.redirectByAlias("MERGE_KEY", A), identity.redirectByAlias("MERGE_KEY", B))))
                importJob.cancel(cancelled)
                importJob.join()
                withTimeout(10_000) { adapter.exited.await() }
                assertSame("cancellation identity reaches the import caller", cancelled,
                    withTimeout(10_000) { observedCancellation.await() })
                assertEquals(emptyList<String>(), callbacks)
                assertEquals(beforeRows, typedRows(dao.readManualWorkMergeRows(listOf(A, B))))
                assertEquals(beforeAudit, listOf(identity.operationCount(), identity.redirectCount(),
                    identity.operationStateById(OPERATION), identity.preservedParticipants(OPERATION),
                    listOf(identity.redirectByAlias("WORK_ID", A), identity.redirectByAlias("WORK_ID", B),
                        identity.redirectByAlias("MERGE_KEY", A), identity.redirectByAlias("MERGE_KEY", B))))
                assertEquals("same prepared plan is admitted after cancellation cleanup",
                    ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(plan))
                assertPreserved(dao)
                assertEquals(listOf(URL_A), adapter.requests)
                assertEquals(emptyList<String>(), callbacks)
            } finally {
                importJob.cancelAndJoin()
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seed(dao: AudiobookDao) {
        dao.upsertWork(WorkEntity(A, A, "Old A", "Shared Author", addedAt = 10L))
        dao.upsertWork(WorkEntity(B, B, "Old B", "Shared Author", addedAt = 20L))
        dao.insertAudiobooks(listOf(
            AudiobookEntity(BOOK_A, "Old A", "Shared Author", "Reader A", "Received A", 0,
                genre = "", sourceUrl = URL_A, totalDurationSeconds = 120L, totalChapters = 1),
            AudiobookEntity(BOOK_B, "Old B", "Shared Author", "Reader B", "Received B", 0,
                genre = "", sourceUrl = URL_B, totalDurationSeconds = 180L, totalChapters = 1),
        ))
        dao.upsertLibraryEntry(BOOK_A, A, isFavorite = true, createdAt = 10L, downloadProgress = 0f, origin = "EXPLICIT_SAVE")
        dao.upsertLibraryEntry(BOOK_B, B, isFavorite = false, createdAt = 20L, downloadProgress = 0f, origin = "EXPLICIT_SAVE")
        listOf(editionA(), editionB()).forEach { dao.replaceEdition(it) }
        dao.insertSources(listOf(sourceA(), sourceB()))
        dao.insertSourceTrackRows(listOf(trackA(), trackB()))
        dao.insertChapters(listOf(chapterA(), chapterB()))
        dao.savePlaybackProgress(progressA())
        dao.savePlaybackProgress(progressB())
        dao.insertBookmark(bookmarkA())
        dao.insertBookmark(bookmarkB())
        dao.upsertWorkSource(WorkSourceEntity("catalog-import-claim-a", A, SOURCE, URL_A, addedAt = 10L))
        dao.upsertWorkSource(WorkSourceEntity("catalog-import-claim-b", B, SOURCE, URL_B, addedAt = 20L))
        dao.refreshWorkSearchIndex(A)
        dao.refreshWorkSearchIndex(B)
    }

    private suspend fun assertPreserved(dao: AudiobookDao) {
        assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
        assertEquals(listOf(BOOK_A, BOOK_B), dao.getAllAudiobooksOnce().map { it.id }.sorted())
        assertEquals(C, dao.libraryEntryById(BOOK_A)?.workId)
        assertEquals(C, dao.libraryEntryById(BOOK_B)?.workId)
        val canonicalClaims = dao.getWorkSourcesForWorkSync(C)
        assertEquals("canonical Work retains exactly two source claims", 2, canonicalClaims.size)
        assertEquals(
            listOf("sluhayua" to "https://sluhay.com.ua/100:alias-a", "sluhayua" to "https://sluhay.com.ua/200:alias-b"),
            canonicalClaims.map { it.sourceId to it.sourceUrl }
                .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }),
        )
        val snapshot = dao.readManualWorkMergeRows(listOf(C))
        assertEquals("no duplicate or regenerated Edition", listOf(editionA(), editionB()), snapshot.library.editions.sortedBy { it.id })
        assertEquals(listOf(sourceA(), sourceB()), snapshot.library.sources.sortedBy { it.id })
        assertEquals(listOf(trackA(), trackB()), snapshot.library.tracks.sortedBy { it.id })
        assertEquals(listOf(chapterA(), chapterB()), snapshot.library.chapters.sortedBy { it.id })
        assertEquals(listOf(progressA(), progressB()), snapshot.library.progress.sortedBy { it.editionId })
        assertEquals(listOf(bookmarkA(), bookmarkB()), snapshot.library.bookmarks.sortedBy { it.id })
        assertEquals(listOf(BOOK_A, BOOK_B), snapshot.library.entries.map { it.id }.sorted())
        assertNull(dao.getWorkById(A))
        assertNull(dao.getWorkById(B))
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

    /** Keep the public cancellation identity oracle stable with coroutine stacktrace recovery enabled. */
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

    private fun editionA() = EditionEntity(EDITION_A, BOOK_A, "uk", "Reader A", 1, 120L, 10L)
    private fun editionB() = EditionEntity(EDITION_B, BOOK_B, "uk", "Reader B", 1, 180L, 20L)
    private fun sourceA() = SourceEntity("catalog-import-source-a", BOOK_A, EDITION_A, SOURCE, URL_A, addedAt = 10L)
    private fun sourceB() = SourceEntity("catalog-import-source-b", BOOK_B, EDITION_B, SOURCE, URL_B, addedAt = 20L)
    private fun trackA() = SourceTrackEntity("catalog-import-track-a", "catalog-import-source-a", 0, STREAM_A)
    private fun trackB() = SourceTrackEntity("catalog-import-track-b", "catalog-import-source-b", 0, STREAM_B)
    private fun chapterA() = ChapterEntity("catalog-import-chapter-a", BOOK_A, 0, "First A", 120L, EDITION_A)
    private fun chapterB() = ChapterEntity("catalog-import-chapter-b", BOOK_B, 0, "First B", 180L, EDITION_B)
    private fun progressA() = PlaybackProgressEntity(EDITION_A, BOOK_A, currentPositionSeconds = 31L,
        lastListenedAt = 111L, lastPausedAtEpochMs = 112L, preferredSpeed = 1.25f)
    private fun progressB() = PlaybackProgressEntity(EDITION_B, BOOK_B, currentPositionSeconds = 77L,
        lastListenedAt = 221L, isCompleted = true, lastPausedAtEpochMs = 222L, preferredSpeed = 0.9f)
    private fun bookmarkA() = BookmarkEntity(101L, BOOK_A, EDITION_A, 0, "First A", 29L, "Keep A", 113L)
    private fun bookmarkB() = BookmarkEntity(202L, BOOK_B, EDITION_B, 0, "First B", 75L, "Keep B", 223L)

    private companion object {
        const val A = "old a|shared author"
        const val B = "old b|shared author"
        const val C = "same work|shared author"
        const val OPERATION = "catalog-import-identity-operation"
        const val BOOK_A = "catalog-import-book-a"
        const val BOOK_B = "catalog-import-book-b"
        const val EDITION_A = "catalog-import-edition-a"
        const val EDITION_B = "catalog-import-edition-b"
        const val SOURCE = "sluhayua"
        const val URL_A = "https://sluhay.com.ua/100:alias-a"
        const val URL_B = "https://sluhay.com.ua/200:alias-b"
        const val STREAM_A = "https://mp3.sluhay.com.ua/100/a.mp3"
        const val STREAM_B = "https://mp3.sluhay.com.ua/200/b.mp3"
    }
}
