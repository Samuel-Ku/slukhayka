package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
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
class ManualWorkIdentityUnknownImportWorkOwnerRoomTest {
    @Test(timeout = 180_000)
    fun `unknown source import refuses foreign occupied Work creation ID without replacing its different identity`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "unknown-import-occupied-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val foreign = WorkEntity(M, F, "Foreign Title", "Foreign Author", addedAt = 10L)
            val foreignClaim = WorkSourceEntity("opaque-foreign-work-claim", M, "foreign-provider", "https://foreign.example/item", addedAt = 11L)
            dao.upsertWork(foreign)
            dao.upsertWorkSource(foreignClaim)
            dao.refreshWorkSearchIndex(M)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(foreign, dao.getWorkById(M))
            assertNull(dao.findWorkByMergeKey(M))
            assertEquals(foreign, dao.findWorkByMergeKey(F))
            assertTrue(dao.getAllAudiobooksOnce().isEmpty())
            val before = typedRows(dao.readManualWorkMergeRows(listOf(M)))
            val auditBefore = listOf(database.workIdentityDao().operationCount(), database.workIdentityDao().redirectCount())
            val adapter = NoFetch()
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, ManualWorkMergeGate(database)))
            var refusal: WorkIdentityWriteRefused? = null
            try {
                importer.importBookFromSource(SOURCE, detail(), writeBackProfile = false,
                    origin = LibraryEntryOrigin.EXPLICIT_IMPORT, onNewBookImported = { callbacks += it.id })
            } catch (caught: WorkIdentityWriteRefused) {
                refusal = caught
            }
            assertEquals("968_UNKNOWN_IMPORT_CANNOT_REPLACE_FOREIGN_WORK_ID", IdentityWriteRefusal.ConflictingSource, refusal?.refusal)
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(M))))
            assertEquals(foreign, dao.getWorkById(M))
            assertEquals(listOf(foreignClaim), dao.getWorkSourcesForWorkSync(M))
            assertNull(dao.getAudiobookById(P))
            assertTrue(dao.getAllAudiobooksOnce().isEmpty())
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(0, adapter.calls)
            assertEquals(auditBefore, listOf(database.workIdentityDao().operationCount(), database.workIdentityDao().redirectCount()))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(listOf(M))))
            assertEquals(foreign, dao.getWorkById(M))
            assertEquals(listOf(foreignClaim), dao.getWorkSourcesForWorkSync(M))
            assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `unknown source import creates a genuinely missing Work with its own physical Book and durable graph`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "unknown-import-fresh-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            assertNull(dao.getWorkById(M))
            assertNull(dao.findWorkByMergeKey(M))
            val adapter = NoFetch()
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, ManualWorkMergeGate(database)))
            val result = importer.importBookFromSource(SOURCE, detail(), writeBackProfile = false,
                origin = LibraryEntryOrigin.EXPLICIT_IMPORT, onNewBookImported = { callbacks += it.id })
            assertEquals("968_UNKNOWN_IMPORT_CREATES_LITERAL_WORK_TARGET", M, dao.libraryEntryById(P)?.workId)
            assertEquals(P, result.id)
            val work = checkNotNull(dao.getWorkById(M))
            assertEquals(M, work.mergeKey)
            assertEquals("Incoming Title", work.title)
            assertEquals("Incoming Author", work.author)
            assertEquals(P, dao.libraryEntryById(P)?.id)
            assertEquals("EXPLICIT_IMPORT", dao.libraryEntryById(P)?.origin)
            assertEquals(listOf(P), dao.getAllAudiobooksOnce().map { it.id })
            val snapshot = dao.readManualWorkMergeRows(listOf(M))
            assertEquals(listOf(M), snapshot.library.works.map { it.id })
            val edition = snapshot.library.editions.single()
            assertEquals(P, edition.workId)
            assertEquals("Reader Incoming", edition.narrator)
            assertEquals("uk", edition.language)
            assertEquals(1, edition.totalChapters)
            assertEquals(120L, edition.totalDurationSeconds)
            val source = dao.getSourcesForBookSync(P).single()
            assertEquals(P, source.bookId)
            assertEquals(edition.id, source.editionId)
            assertEquals(SOURCE, source.type)
            assertEquals(URL, source.url)
            val chapter = dao.getChaptersListForBook(P).single()
            assertEquals(P, chapter.bookId)
            assertEquals(edition.id, chapter.editionId)
            assertEquals(0, chapter.chapterIndex)
            assertEquals("Only Chapter", chapter.title)
            assertEquals(120L, chapter.durationSeconds)
            val track = dao.getTracksForSourceSync(source.id).single()
            assertEquals(source.id, track.sourceId)
            assertEquals(0, track.trackIndex)
            assertEquals(STREAM, track.url)
            assertEquals(listOf(P), callbacks)
            assertTrue("No series in detail means no series-work callback", workCallbacks.isEmpty())
            assertEquals(0, adapter.calls)
            val committed = typedRows(snapshot)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(work, dao.getWorkById(M))
            assertEquals(M, dao.libraryEntryById(P)?.workId)
            assertEquals(committed, typedRows(dao.readManualWorkMergeRows(listOf(M))))
            assertEquals(listOf(source), dao.getSourcesForBookSync(P))
            assertEquals(listOf(chapter), dao.getChaptersListForBook(P))
            assertEquals(listOf(track), dao.getTracksForSourceSync(source.id))
            assertEquals(edition, dao.getEditionById(edition.id))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun detail() = SourceBookDetail(title = "Incoming Title", author = "Incoming Author", narrator = "Reader Incoming",
        url = URL, language = "uk", totalDurationSeconds = 120L,
        chapters = listOf(SourceChapter("Only Chapter", STREAM, 120L)))

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
        override suspend fun fetchBookPage(url: String): SourceBookDetail { calls++; error("detail already supplied") }
        override suspend fun search(query: String): List<SourceBook> { calls++; error("no search") }
        override suspend fun fetchNew(limit: Int): List<SourceBook> { calls++; error("no feed") }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> { calls++; error("no catalog") }
    }

    private companion object {
        const val M = "incoming title|incoming author"
        const val F = "foreign title|foreign author"
        const val SOURCE = "sluhayua"
        const val URL = "https://sluhay.com.ua/100:incoming"
        // Existing public SourceAdapter.bookId(url) contract: sourceId + final URL slug.
        const val P = "sluhayua-100:incoming"
        const val STREAM = "https://cdn.example.test/incoming.mp3"
    }
}
