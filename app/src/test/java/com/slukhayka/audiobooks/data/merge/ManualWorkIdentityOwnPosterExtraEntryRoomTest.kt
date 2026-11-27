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
class ManualWorkIdentityOwnPosterExtraEntryRoomTest {
    @Test(timeout = 180_000)
    fun `own matching poster with Access enabled refuses an extra legacy dangling Entry reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "own-poster-extra-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            // Q is a legacy dangling extra row, not an ordinary Book-only self-anchor.
            dao.insertAudiobooks(listOf(
                AudiobookEntity(P, "Collision Title", "Collision Author", "", "Own legacy metadata", 0,
                    genre = "", sourceUrl = OWN_URL, isDownloaded = true,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f),
                AudiobookEntity(Q, "Other Legacy Book", "", "", "Dangling legacy metadata", 0,
                    genre = "", sourceUrl = EXTRA_URL, isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.25f),
            ))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            dao.upsertLibraryEntry(id = Q, workId = P, isFavorite = false, createdAt = 124L,
                downloadProgress = 0.625f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertTrue(P < Q)
            val ownBefore = checkNotNull(dao.getAudiobookById(P))
            val extraBefore = checkNotNull(dao.getAudiobookById(Q))
            assertEquals("Collision Title", ownBefore.title)
            assertEquals("Collision Author", ownBefore.author)
            assertNull(ownBefore.workId)
            assertNull(extraBefore.workId)
            assertNull(dao.getWorkById(P))
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(P, Q), initial.library.books.map { it.id }.sorted())
            assertEquals(listOf(P to P, Q to P), initial.library.entries.map { it.id to it.workId }.sortedBy { it.first })
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val before = typedRows(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val callbacks = mutableListOf<String>()
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { callbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            var refusal: WorkIdentityWriteRefused? = null
            var outcome: AudiobookEntity? = null
            try {
                outcome = catalog.upsertCatalogBook(CatalogBook(P, "Collision Title", "Collision Author", OWN_URL,
                    coverImageUrl = null))
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
                refusal = failure
            }
            val extraAfter = dao.getAudiobookById(Q)
            assertTrue("968_OWN_POSTER_MUST_NOT_CAPTURE_EXTRA_ENTRY_ACCESS_ENABLED", extraAfter != null && extraAfter.workId == null)
            assertEquals(IdentityWriteRefusal.ConflictingSource, refusal?.refusal)
            assertNull(outcome)
            val after = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(after))
            assertEquals(initial.library.books.sortedBy { it.id }, after.library.books.sortedBy { it.id })
            assertEquals(initial.library.entries.sortedBy { it.id }, after.library.entries.sortedBy { it.id })
            assertEquals(ownBefore, dao.getAudiobookById(P))
            assertEquals(extraBefore, dao.getAudiobookById(Q))
            assertNull(dao.getWorkById(P))
            assertTrue(after.library.works.isEmpty())
            assertTrue(after.catalog.workSources.isEmpty())
            assertNoAudio(after)
            assertEquals(auditBefore, audit(identity))
            assertTrue(callbacks.isEmpty())
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val reopened = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(reopened))
            assertEquals(ownBefore, dao.getAudiobookById(P))
            assertEquals(extraBefore, dao.getAudiobookById(Q))
            assertNull(checkNotNull(dao.getAudiobookById(Q)).workId)
            assertNull(dao.getWorkById(P))
            assertNoAudio(reopened)
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `own matching poster with Access omitted refuses an extra legacy dangling Entry reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "own-poster-extra-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            // Q is a legacy dangling extra row, not an ordinary Book-only self-anchor.
            dao.insertAudiobooks(listOf(
                AudiobookEntity(P, "Collision Title", "Collision Author", "", "Own legacy metadata", 0,
                    genre = "", sourceUrl = OWN_URL, isDownloaded = true,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f),
                AudiobookEntity(Q, "Other Legacy Book", "", "", "Dangling legacy metadata", 0,
                    genre = "", sourceUrl = EXTRA_URL, isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.25f),
            ))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            dao.upsertLibraryEntry(id = Q, workId = P, isFavorite = false, createdAt = 124L,
                downloadProgress = 0.625f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertTrue(P < Q)
            val ownBefore = checkNotNull(dao.getAudiobookById(P))
            val extraBefore = checkNotNull(dao.getAudiobookById(Q))
            assertEquals("Collision Title", ownBefore.title)
            assertEquals("Collision Author", ownBefore.author)
            assertNull(ownBefore.workId)
            assertNull(extraBefore.workId)
            assertNull(dao.getWorkById(P))
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(P, Q), initial.library.books.map { it.id }.sorted())
            assertEquals(listOf(P to P, Q to P), initial.library.entries.map { it.id to it.workId }.sortedBy { it.first })
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val before = typedRows(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val callbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { callbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } })
            var refusal: WorkIdentityWriteRefused? = null
            var outcome: AudiobookEntity? = null
            try {
                outcome = catalog.upsertCatalogBook(CatalogBook(P, "Collision Title", "Collision Author", OWN_URL,
                    coverImageUrl = null))
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
                refusal = failure
            }
            val extraAfter = dao.getAudiobookById(Q)
            assertTrue("968_OWN_POSTER_MUST_NOT_CAPTURE_EXTRA_ENTRY_ACCESS_OMITTED", extraAfter != null && extraAfter.workId == null)
            assertEquals(IdentityWriteRefusal.ConflictingSource, refusal?.refusal)
            assertNull(outcome)
            val after = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(after))
            assertEquals(initial.library.books.sortedBy { it.id }, after.library.books.sortedBy { it.id })
            assertEquals(initial.library.entries.sortedBy { it.id }, after.library.entries.sortedBy { it.id })
            assertEquals(ownBefore, dao.getAudiobookById(P))
            assertEquals(extraBefore, dao.getAudiobookById(Q))
            assertNull(dao.getWorkById(P))
            assertTrue(after.library.works.isEmpty())
            assertTrue(after.catalog.workSources.isEmpty())
            assertNoAudio(after)
            assertEquals(auditBefore, audit(identity))
            assertTrue(callbacks.isEmpty())
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val reopened = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(reopened))
            assertEquals(ownBefore, dao.getAudiobookById(P))
            assertEquals(extraBefore, dao.getAudiobookById(Q))
            assertNull(checkNotNull(dao.getAudiobookById(Q)).workId)
            assertNull(dao.getWorkById(P))
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
        const val P = "collision title|collision author"
        const val Q = "z-extra-legacy-book"
        const val SOURCE = "sluhayua"
        const val OWN_URL = "https://sluhay.com.ua/600:legacy"
        const val EXTRA_URL = "https://sluhay.com.ua/800:extra"
        const val OPERATION = "own-poster-extra-no-manual-operation"
        val ROOTS = listOf(P, Q)
    }
}
