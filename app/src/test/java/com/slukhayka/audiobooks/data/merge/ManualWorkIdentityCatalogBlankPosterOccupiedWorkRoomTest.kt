package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.CatalogBook
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
class ManualWorkIdentityCatalogBlankPosterOccupiedWorkRoomTest {
    @Test(timeout = 180_000)
    fun `blank catalog poster with Access enabled cannot adopt a foreign Work occupying its physical ID`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "blank-poster-occupied-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val foreign = WorkEntity(P, FOREIGN_KEY, "Foreign Work", "Foreign Author",
                coverImageUrl = "https://covers.example.test/foreign.jpg", addedAt = 10L)
            dao.upsertWork(foreign)
            dao.refreshWorkSearchIndex(P)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(foreign, dao.getWorkById(P))
            assertNull(dao.getWorkById(FOREIGN_KEY))
            assertNull(dao.getAudiobookById(P))
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(foreign), initial.library.works)
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val before = typedRows(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val gate = ManualWorkMergeGate(database)
            val access = WorkIdentityAccess(database, gate)
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val outcome = try {
                catalog.upsertCatalogBook(CatalogBook(P, "Untitled Poster", "", URL, coverImageUrl = null))
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
                null
            }
            assertNull("968_BLANK_POSTER_MUST_NOT_ADOPT_OCCUPIED_WORK_ID", dao.getAudiobookById(P))
            assertNull(outcome)
            val after = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(after))
            assertEquals(foreign, dao.getWorkById(P))
            assertNull(dao.getWorkById(FOREIGN_KEY))
            assertTrue(after.library.books.isEmpty())
            assertTrue(after.library.entries.isEmpty())
            assertTrue(after.catalog.workSources.isEmpty())
            assertNoAudio(after)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val reopened = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(reopened))
            assertEquals(foreign, dao.getWorkById(P))
            assertNull(dao.getAudiobookById(P))
            assertTrue(reopened.library.entries.isEmpty())
            assertTrue(reopened.catalog.workSources.isEmpty())
            assertNoAudio(reopened)
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `blank catalog poster with Access omitted cannot adopt a foreign Work occupying its physical ID`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "blank-poster-occupied-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val foreign = WorkEntity(P, FOREIGN_KEY, "Foreign Work", "Foreign Author",
                coverImageUrl = "https://covers.example.test/foreign.jpg", addedAt = 10L)
            dao.upsertWork(foreign)
            dao.refreshWorkSearchIndex(P)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(foreign, dao.getWorkById(P))
            assertNull(dao.getWorkById(FOREIGN_KEY))
            assertNull(dao.getAudiobookById(P))
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(foreign), initial.library.works)
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val before = typedRows(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val importer = LibraryImport(dao, context, listOf(adapter))
            val catalog = SourceCatalog(dao, listOf(adapter), importer)
            val outcome = try {
                catalog.upsertCatalogBook(CatalogBook(P, "Untitled Poster", "", URL, coverImageUrl = null))
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
                null
            }
            assertNull("968_BLANK_POSTER_MUST_NOT_ADOPT_OCCUPIED_WORK_ID", dao.getAudiobookById(P))
            assertNull(outcome)
            val after = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(after))
            assertEquals(foreign, dao.getWorkById(P))
            assertNull(dao.getWorkById(FOREIGN_KEY))
            assertTrue(after.library.books.isEmpty())
            assertTrue(after.library.entries.isEmpty())
            assertTrue(after.catalog.workSources.isEmpty())
            assertNoAudio(after)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val reopened = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(reopened))
            assertEquals(foreign, dao.getWorkById(P))
            assertNull(dao.getAudiobookById(P))
            assertTrue(reopened.library.entries.isEmpty())
            assertTrue(reopened.catalog.workSources.isEmpty())
            assertNoAudio(reopened)
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun assertNoAudio(snapshot: ManualWorkMergeSnapshot) {
        assertTrue(snapshot.library.editions.isEmpty())
        assertTrue(snapshot.library.sources.isEmpty())
        assertTrue(snapshot.library.chapters.isEmpty())
        assertTrue(snapshot.library.tracks.isEmpty())
        assertTrue(snapshot.library.progress.isEmpty())
        assertTrue(snapshot.library.bookmarks.isEmpty())
        assertTrue(snapshot.library.settings.isEmpty())
        assertTrue(snapshot.library.readthroughs.isEmpty())
    }

    private suspend fun audit(identity: WorkIdentityDao): List<Any?> = listOf(
        identity.operationCount(), identity.operationStateById(OPERATION), identity.redirectCount(),
        identity.preservedParticipants(OPERATION), identity.preservedBooks(OPERATION),
        ROOTS.flatMap { key -> listOf("WORK_ID", "MERGE_KEY").map { namespace -> identity.redirectByAlias(namespace, key) } },
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

    private class NoFetch : SourceAdapter {
        override val sourceId = SOURCE
        var calls = 0
        override suspend fun fetchBookPage(url: String): SourceBookDetail { calls++; error("poster must not fetch audio") }
        override suspend fun search(query: String): List<SourceBook> { calls++; error("no search") }
        override suspend fun fetchNew(limit: Int): List<SourceBook> { calls++; error("no feed") }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> { calls++; error("no catalog") }
    }

    private companion object {
        const val P = "blank-poster-occupied-work-id"
        const val FOREIGN_KEY = "foreign work|foreign author"
        const val SOURCE = "sluhayua"
        const val URL = "https://sluhay.com.ua/400:blank-poster"
        const val OPERATION = "blank-poster-no-manual-operation"
        val ROOTS = listOf(P, FOREIGN_KEY)
    }
}
