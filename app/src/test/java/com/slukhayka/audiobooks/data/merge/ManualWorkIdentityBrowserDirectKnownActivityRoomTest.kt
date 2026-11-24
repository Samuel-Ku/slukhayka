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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityBrowserDirectKnownActivityRoomTest {
    @Test(timeout = 60_000)
    fun `browser direct page fetch reserves its known catalog Work until cancellation releases the prepared merge`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "browser-direct-known-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.upsertWork(WorkEntity(C, KEY_C, "Direct Title", "Direct Author", addedAt = 10L))
            dao.upsertWork(WorkEntity(D, KEY_D, "Control Title", "Direct Author", addedAt = 20L))
            dao.upsertWorkSource(WorkSourceEntity("opaque-direct-claim-c", C, SOURCE, URL_C, addedAt = 10L))
            dao.upsertWorkSource(WorkSourceEntity("opaque-direct-claim-d", D, SOURCE, URL_D, addedAt = 20L))
            dao.refreshWorkSearchIndex(C)
            dao.refreshWorkSearchIndex(D)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val gate = ManualWorkMergeGate(database)
            val commands = ManualWorkMergeCommands(database, gate)
            val prepared = commands.prepareManualWorkMerge(C, D)
            assertTrue("SETUP: valid catalog-only participants", prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(C, D), prepared.preview.participants.map { it.work.id })
            assertTrue(prepared.preview.snapshot.library.books.isEmpty())
            assertTrue(prepared.preview.snapshot.library.entries.isEmpty())
            assertTrue(prepared.preview.snapshot.library.editions.isEmpty())
            assertTrue(prepared.preview.snapshot.library.sources.isEmpty())
            val plan = ManualWorkMergeApprovedPlan(prepared, OPERATION, "Merged Direct", "Direct Author")
            val before = typedRows(dao.readManualWorkMergeRows(listOf(C, D)))
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = HeldDirectPage()
            val publications = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { publications += "work:$it" },
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, gate))
            val job = launch {
                importer.importBrowserSourceDirectPage(SOURCE, URL_C,
                    onNewBookImported = { publications += "book:${it.id}" })
            }
            try {
                withTimeout(10_000) { adapter.entered.await() }
                assertTrue("SETUP: public browser-direct import is held inside adapter fetch", job.isActive)
                assertEquals(listOf(URL_C), adapter.requests)
                assertTrue(publications.isEmpty())
                assertEquals("968_BROWSER_DIRECT_KNOWN_FETCH_MUST_BLOCK_APPLY",
                    ManualWorkMergeApplyResult.NeedsDecision("A participant has active work"),
                    commands.applyManualWorkMerge(plan))
                assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(C, D))))
                assertEquals(auditBefore, audit(identity))
                job.cancelAndJoin()
                withTimeout(10_000) { adapter.exited.await() }
                assertTrue(job.isCompleted)
                assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(C, D))))
                assertEquals(auditBefore, audit(identity))
                assertTrue(publications.isEmpty())
                assertEquals("same prepared plan succeeds after direct fetch cancellation cleanup",
                    ManualWorkMergeApplyResult.Applied(OPERATION, MERGED), commands.applyManualWorkMerge(plan))
                val merged = dao.readManualWorkMergeRows(listOf(MERGED))
                assertEquals(listOf(MERGED), merged.library.works.map { it.id })
                assertNull(dao.getWorkById(C))
                assertNull(dao.getWorkById(D))
                assertTrue(merged.library.books.isEmpty())
                assertTrue(merged.library.entries.isEmpty())
                assertTrue(merged.library.editions.isEmpty())
                assertTrue(merged.library.sources.isEmpty())
                assertEquals(listOf(
                    WorkSourceEntity("merged direct|direct author|sluhayua|2b8dbd8d", MERGED,
                        SOURCE, URL_C, streamOnly = false, coverImageUrl = null,
                        durationSeconds = null, addedAt = 10L),
                    WorkSourceEntity("merged direct|direct author|sluhayua|b0342afa", MERGED,
                        SOURCE, URL_D, streamOnly = false, coverImageUrl = null,
                        durationSeconds = null, addedAt = 20L),
                ), merged.catalog.workSources.sortedBy { it.sourceUrl })
                assertEquals(WorkMergeOperationState.APPLIED, identity.operationStateById(OPERATION))
                assertTrue(identity.preservedBooks(OPERATION).isEmpty())
                assertTrue(identity.preservedParticipants(OPERATION).isEmpty())
                assertTrue(publications.isEmpty())
                assertEquals(listOf(URL_C), adapter.requests)
                assertEquals(0, adapter.otherCalls)
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 60_000)
    fun `browser direct explicit import preserves a tombstone committed during its fetch`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "browser-direct-newer-tombstone-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            seedKnown(dao)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertTrue(dao.manualMergeTombstones(listOf(C)).isEmpty())
            val adapter = HeldDirectPage()
            val callbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { callbacks += "work:$it" },
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, ManualWorkMergeGate(database)))
            val job = async {
                try {
                    importer.importBrowserSourceDirectPage(SOURCE, URL_C,
                        origin = com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin.EXPLICIT_IMPORT,
                        onNewBookImported = { callbacks += "book:${it.id}" })
                } catch (failure: WorkIdentityWriteRefused) {
                    if (failure.refusal != IdentityWriteRefusal.Tombstoned) throw failure
                    null
                }
            }
            try {
                withTimeout(10_000) { adapter.entered.await() }
                assertTrue(job.isActive)
                assertEquals(listOf(URL_C), adapter.requests)
                assertTrue(callbacks.isEmpty())
                dao.insertTombstone(TombstoneEntity(C, 900L))
                val before = typedRows(dao.readManualWorkMergeRows(listOf(C)))
                val beforeAudit = audit(database.workIdentityDao())
                adapter.release.complete(Unit)
                val imported = withTimeout(10_000) { job.await() }
                assertTrue("968_BROWSER_DIRECT_START_TOKEN_MUST_PRESERVE_NEWER_TOMBSTONE", imported == null)
                assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(C))))
                assertEquals(listOf(TombstoneEntity(C, 900L)), dao.manualMergeTombstones(listOf(C)))
                assertTrue(dao.getAllAudiobooksOnce().isEmpty())
                assertEquals(beforeAudit, audit(database.workIdentityDao()))
                assertTrue(callbacks.isEmpty())
                assertEquals(listOf(URL_C), adapter.requests)
                assertEquals(0, adapter.otherCalls)
                database.close()
                database = open()
                dao = database.audiobookDao()
                assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(C))))
                assertEquals(beforeAudit, audit(database.workIdentityDao()))
                assertTrue(dao.getAllAudiobooksOnce().isEmpty())
                assertEquals(listOf(TombstoneEntity(C, 900L)), dao.manualMergeTombstones(listOf(C)))
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 60_000)
    fun `browser direct successful page delegates its known Work to a durable explicit import`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "browser-direct-success-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            seedKnown(dao)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val work = checkNotNull(dao.getWorkById(C))
            val claims = dao.getWorkSourcesForWorkSync(C)
            assertEquals(listOf("opaque-direct-claim-c"), claims.map { it.id })
            assertTrue(dao.manualMergeTombstones(listOf(C)).isEmpty())
            val adapter = HeldDirectPage()
            adapter.release.complete(Unit)
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, ManualWorkMergeGate(database)))
            val imported = importer.importBrowserSourceDirectPage(SOURCE, URL_C,
                origin = com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin.EXPLICIT_IMPORT,
                onNewBookImported = { callbacks += it.id })
            assertNotNull("968_BROWSER_DIRECT_VALID_PAGE_MUST_DELEGATE_SUCCESSFULLY", imported)
            val bookId = "sluhayua-100:direct"
            assertEquals(bookId, checkNotNull(imported).id)
            assertEquals(C, dao.libraryEntryById(bookId)?.workId)
            assertEquals("EXPLICIT_IMPORT", dao.libraryEntryById(bookId)?.origin)
            assertEquals(listOf(bookId), dao.getAllAudiobooksOnce().map { it.id })
            assertEquals(work, dao.getWorkById(C))
            assertEquals(claims, dao.getWorkSourcesForWorkSync(C))
            val snapshot = dao.readManualWorkMergeRows(listOf(C))
            assertEquals(listOf(C), snapshot.library.works.map { it.id })
            val edition = snapshot.library.editions.single()
            assertEquals(bookId, edition.workId)
            assertEquals("Direct Reader", edition.narrator)
            assertEquals("uk", edition.language)
            assertEquals(1, edition.totalChapters)
            assertEquals(120L, edition.totalDurationSeconds)
            val source = dao.getSourcesForBookSync(bookId).single()
            assertEquals(bookId, source.bookId)
            assertEquals(edition.id, source.editionId)
            assertEquals(SOURCE, source.type)
            assertEquals(URL_C, source.url)
            val chapter = dao.getChaptersListForBook(bookId).single()
            assertEquals(bookId, chapter.bookId)
            assertEquals(edition.id, chapter.editionId)
            assertEquals(0, chapter.chapterIndex)
            assertEquals("Direct Chapter", chapter.title)
            assertEquals(120L, chapter.durationSeconds)
            val track = dao.getTracksForSourceSync(source.id).single()
            assertEquals(source.id, track.sourceId)
            assertEquals(0, track.trackIndex)
            assertEquals("https://cdn.example.test/direct.mp3", track.url)
            assertEquals(listOf(bookId), callbacks)
            assertTrue("No series in page means no series-work callback", workCallbacks.isEmpty())
            assertEquals(listOf(URL_C), adapter.requests)
            assertEquals(0, adapter.otherCalls)
            assertTrue(dao.manualMergeTombstones(listOf(C)).isEmpty())
            val committed = typedRows(snapshot)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committed, typedRows(dao.readManualWorkMergeRows(listOf(C))))
            assertEquals(work, dao.getWorkById(C))
            assertEquals(claims, dao.getWorkSourcesForWorkSync(C))
            assertEquals(C, dao.libraryEntryById(bookId)?.workId)
            assertEquals(edition, dao.getEditionById(edition.id))
            assertEquals(listOf(source), dao.getSourcesForBookSync(bookId))
            assertEquals(listOf(chapter), dao.getChaptersListForBook(bookId))
            assertEquals(listOf(track), dao.getTracksForSourceSync(source.id))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seedKnown(dao: AudiobookDao) {
        dao.upsertWork(WorkEntity(C, KEY_C, "Direct Title", "Direct Author", addedAt = 10L))
        dao.upsertWorkSource(WorkSourceEntity("opaque-direct-claim-c", C, SOURCE, URL_C, addedAt = 10L))
        dao.refreshWorkSearchIndex(C)
    }

    private suspend fun audit(identity: WorkIdentityDao): List<Any?> = listOf(
        identity.operationCount(), identity.operationStateById(OPERATION), identity.redirectCount(),
        identity.preservedParticipants(OPERATION), identity.preservedBooks(OPERATION),
        listOf("WORK_ID" to C, "WORK_ID" to D, "MERGE_KEY" to KEY_C, "MERGE_KEY" to KEY_D,
            "WORK_ID" to MERGED, "MERGE_KEY" to MERGED).map { (namespace, key) -> identity.redirectByAlias(namespace, key) },
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

    private class HeldDirectPage : SourceAdapter {
        override val sourceId = SOURCE
        val requests = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            check(url == URL_C)
            requests += url
            entered.complete(Unit)
            try {
                release.await()
                return SourceBookDetail(title = "Direct Title", author = "Direct Author", narrator = "Direct Reader",
                    url = URL_C, language = "uk", totalDurationSeconds = 120L,
                    chapters = listOf(SourceChapter("Direct Chapter", "https://cdn.example.test/direct.mp3", 120L)))
            } finally {
                exited.complete(Unit)
            }
        }
        override suspend fun search(query: String): List<SourceBook> { otherCalls++; error("no search") }
        override suspend fun fetchNew(limit: Int): List<SourceBook> { otherCalls++; error("no feed") }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> { otherCalls++; error("no catalog") }
        var otherCalls = 0
    }

    private companion object {
        const val C = "opaque-direct-work-c"
        const val D = "opaque-direct-work-d"
        const val KEY_C = "direct title|direct author"
        const val KEY_D = "control title|direct author"
        const val MERGED = "merged direct|direct author"
        const val OPERATION = "browser-direct-known-activity-operation"
        const val SOURCE = "sluhayua"
        const val URL_C = "https://sluhay.com.ua/100:direct"
        const val URL_D = "https://sluhay.com.ua/200:control"
    }
}
