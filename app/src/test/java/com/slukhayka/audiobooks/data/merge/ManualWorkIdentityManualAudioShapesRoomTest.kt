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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Literal AUDIO manual-add storage shapes through existing Commands/import APIs, never the new producer seam. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityManualAudioShapesRoomTest {
    @Test(timeout = 60_000)
    fun `manual audio entries over catalog Editions merge and undo without invented Books`() = runBlocking {
        mergeRoundTrip(false, "968_MANUAL_AUDIO_CATALOG_EDITION_IS_NOT_A_PHYSICAL_BOOK")
    }

    @Test(timeout = 60_000)
    fun `manual audio entries may reference a library Edition through a distinct personal Entry`() = runBlocking {
        mergeRoundTrip(true, "968_MANUAL_AUDIO_ENTRY_IS_NOT_ITS_EDITIONS_PHYSICAL_BOOK")
    }

    @Test(timeout = 60_000)
    fun `manual audio start at zero does not block known metadata first materialization`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-audio-materialize-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            var dao = database.audiobookDao()
            val canonical = WorkEntity(C, C, "Same Work", "Shared Author", addedAt = 10L)
            dao.upsertWork(canonical)
            dao.insertAudiobooks(listOf(bookA().copy(totalChapters = 0, totalDurationSeconds = 0L)))
            val physicalEntry = entry(BOOK_A, C, 10L)
            val manualEntry = entry(MANUAL_C, C, 100L)
            listOf(physicalEntry, manualEntry).forEach { putEntry(dao, it) }
            dao.replaceEdition(editionA(true).copy(totalChapters = 0, totalDurationSeconds = 0L))
            dao.insertSources(listOf(sourceA()))
            val manualPass = ReadthroughEntity(PASS_C, MANUAL_C, C, "AUDIO", "IN_PROGRESS", 100L,
                null, EDITION_A, "SECONDS", 0, "[]")
            dao.upsertReadthrough(manualPass)
            dao.upsertWorkSource(WorkSourceEntity("manual-audio-claim-a", C, SOURCE, URL_A, addedAt = 10L))
            dao.refreshWorkSearchIndex(C)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(manualPass, dao.readthroughById(PASS_C))
            assertEquals(manualEntry, dao.libraryEntryById(MANUAL_C))
            assertNull(dao.getAudiobookById(MANUAL_C))
            assertNoPhysicalPlayback(dao, listOf(C))
            val beforeBook = checkNotNull(dao.getAudiobookById(BOOK_A))
            val adapter = ReceivedPage()
            val callbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, ManualWorkMergeGate(database)))
            var imported: AudiobookEntity? = null
            val refusal = try {
                imported = importer.importFromSourceUrl(SOURCE, URL_A,
                    onNewBookImported = { callbacks += it.id })
                null
            } catch (failure: WorkIdentityWriteRefused) { failure }
            assertNull("968_MANUAL_AUDIO_START_IS_NOT_PHYSICAL_PLAYBACK_HISTORY", refusal?.refusal)
            assertEquals(BOOK_A, checkNotNull(imported).id)
            assertEquals(listOf(URL_A), adapter.requests)
            assertEquals(emptyList<String>(), callbacks)
            assertEquals(beforeBook.copy(totalChapters = 1, totalDurationSeconds = 120L), dao.getAudiobookById(BOOK_A))
            assertEquals(editionA(true), dao.getEditionById(EDITION_A))
            assertEquals(listOf(sourceA()), dao.getSourcesForBookSync(BOOK_A))
            assertEquals(listOf(ChapterEntity("manual-audio-book-a_ch_1", BOOK_A, 0, "First A", 120L, EDITION_A)), dao.getChaptersListForBook(BOOK_A))
            assertEquals(listOf(SourceTrackEntity("manual-audio-source-a_tr_1", sourceA().id, 0, STREAM_A)), dao.getTracksForSourceSync(sourceA().id))
            assertEquals(manualPass, dao.readthroughById(PASS_C))
            assertEquals(manualEntry, dao.libraryEntryById(MANUAL_C))
            assertEquals(physicalEntry, dao.libraryEntryById(BOOK_A))
            assertEquals(canonical, dao.getWorkById(C))
            assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
            assertEquals(listOf(BOOK_A), dao.getAllAudiobooksOnce().map { it.id })
            assertEquals(2, dao.countLibraryEntries())
            assertNoPhysicalPlayback(dao, listOf(C))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private suspend fun mergeRoundTrip(library: Boolean, primary: String) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-audio-shape-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            var dao = database.audiobookDao()
            listOf(workA(), workB()).forEach { dao.upsertWork(it) }
            if (library) {
                dao.insertAudiobooks(listOf(bookA(), bookB()))
                listOf(entry(BOOK_A, A, 10L), entry(BOOK_B, B, 20L)).forEach { putEntry(dao, it) }
            }
            listOf(editionA(library), editionB(library)).forEach { dao.replaceEdition(it) }
            listOf(entry(MANUAL_A, A, 100L), entry(MANUAL_B, B, 200L)).forEach { putEntry(dao, it) }
            listOf(passA(), passB()).forEach { dao.upsertReadthrough(it) }
            if (library) {
                dao.insertSources(listOf(sourceA(), sourceB()))
                dao.insertChapters(listOf(chapterA(), chapterB()))
                dao.insertSourceTrackRows(listOf(trackA(), trackB()))
                dao.savePlaybackProgress(progressA())
            }
            listOf(A, B).forEach { dao.refreshWorkSearchIndex(it) }
            val before = typedRows(dao.readManualWorkMergeRows(listOf(A, B)))
            assertEquals(listOf(passA(), passB()), dao.allReadthroughs().sortedBy { it.id })
            assertNull(dao.getAudiobookById(MANUAL_A))
            assertNull(dao.getAudiobookById(MANUAL_B))
            assertEquals(listOf(editionA(library), editionB(library)), dao.readManualWorkMergeRows(listOf(A, B)).library.editions.sortedBy { it.id })
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            // Preview carrier classification itself is under test, so do not assert its current conflict list as setup truth.
            assertEquals(primary, ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(
                ManualWorkMergeApprovedPlan(prepared, OPERATION, "Same Work", "Shared Author")))
            assertMerged(database, library)
            database.close()
            database = open()
            assertMerged(database, library)
            assertEquals(ManualWorkMergeUndoResult.Undone(OPERATION),
                ManualWorkMergeCommands(database, ManualWorkMergeGate(database)).undoManualWorkMerge(OPERATION))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("Undo restores every original typed row including opaque progress and FTS", before,
                typedRows(dao.readManualWorkMergeRows(listOf(A, B))))
            assertNull(dao.getWorkById(C))
            assertEquals(listOf(passA(), passB()), dao.allReadthroughs().sortedBy { it.id })
            assertEquals(WorkMergeOperationState.UNDONE, database.workIdentityDao().operationStateById(OPERATION))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private suspend fun assertMerged(database: AudiobookDatabase, library: Boolean) {
        val dao = database.audiobookDao()
        val rows = dao.readManualWorkMergeRows(listOf(C)).library
        assertEquals(listOf(WorkEntity(C, C, "Same Work", "Shared Author", addedAt = 10L)), rows.works)
        assertEquals(listOf(passA().copy(workId = C), passB().copy(workId = C)), rows.readthroughs.sortedBy { it.id })
        assertEquals(entry(MANUAL_A, C, 100L), dao.libraryEntryById(MANUAL_A))
        assertEquals(entry(MANUAL_B, C, 200L), dao.libraryEntryById(MANUAL_B))
        assertNull(dao.getAudiobookById(MANUAL_A))
        assertNull(dao.getAudiobookById(MANUAL_B))
        assertEquals(listOf(editionA(library).copy(workId = if (library) BOOK_A else C),
            editionB(library).copy(workId = if (library) BOOK_B else C)), rows.editions.sortedBy { it.id })
        val protected = database.workIdentityDao().preservedParticipants(OPERATION)
        if (library) {
            assertEquals(listOf(BOOK_A, BOOK_B), rows.books.map { it.id }.sorted())
            assertEquals(entry(BOOK_A, C, 10L), dao.libraryEntryById(BOOK_A))
            assertEquals(entry(BOOK_B, C, 20L), dao.libraryEntryById(BOOK_B))
            assertEquals(listOf(sourceA(), sourceB()), rows.sources.sortedBy { it.id })
            assertEquals(listOf(trackA(), trackB()), rows.tracks.sortedBy { it.id })
            assertEquals(listOf(chapterA(), chapterB()), rows.chapters.sortedBy { it.id })
            assertEquals(listOf(progressA()), rows.progress)
            assertEquals(listOf(PreservedWorkMergeParticipant(OPERATION, BOOK_A, EDITION_A, true),
                PreservedWorkMergeParticipant(OPERATION, BOOK_B, EDITION_B, true)), protected)
            assertEquals(4, rows.entries.size)
        } else {
            assertEquals(2, rows.entries.size)
            assertTrue(rows.books.isEmpty())
            assertTrue(rows.sources.isEmpty())
            assertTrue(rows.tracks.isEmpty())
            assertTrue(rows.chapters.isEmpty())
            assertTrue(protected.isEmpty())
            assertNoPhysicalPlayback(dao, listOf(C))
        }
    }

    private suspend fun assertNoPhysicalPlayback(dao: AudiobookDao, roots: List<String>) {
        val rows = dao.readManualWorkMergeRows(roots).library
        assertTrue(rows.progress.isEmpty())
        assertTrue(rows.bookmarks.isEmpty())
        assertTrue(rows.events.isEmpty())
        assertTrue(rows.failures.isEmpty())
    }

    private suspend fun putEntry(dao: AudiobookDao, row: LibraryEntryEntity) {
        dao.upsertLibraryEntry(id = row.id, workId = row.workId, origin = row.origin,
            isFavorite = row.isFavorite, createdAt = row.createdAt, downloadProgress = row.downloadProgress,
            downloadState = row.downloadState)
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

    private class ReceivedPage : SourceAdapter {
        override val sourceId = SOURCE
        val requests = mutableListOf<String>()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            check(url == URL_A); requests += url
            return SourceBookDetail(title = "Old A", author = "Shared Author", narrator = "Reader A",
                description = "Received A", url = URL_A, language = "uk", totalDurationSeconds = 120L,
                chapters = listOf(SourceChapter("First A", STREAM_A, 120L)))
        }
        override suspend fun search(query: String): List<SourceBook> = error("no search")
        override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no crawl")
    }

    private fun workA() = WorkEntity(A, A, "Old A", "Shared Author", addedAt = 10L)
    private fun workB() = WorkEntity(B, B, "Old B", "Shared Author", addedAt = 20L)
    private fun entry(id: String, owner: String, at: Long) = LibraryEntryEntity(id, owner, "EXPLICIT_SAVE", false, at, 0f, "IDLE")
    private fun passA() = ReadthroughEntity(PASS_A, MANUAL_A, A, "AUDIO", "IN_PROGRESS", 100L, null, EDITION_A, "SECONDS", 0, "[]")
    private fun passB() = ReadthroughEntity(PASS_B, MANUAL_B, B, "AUDIO", "IN_PROGRESS", 200L, null, EDITION_B, "SECONDS", 0, "[]")
    private fun editionA(library: Boolean) = EditionEntity(EDITION_A, if (library) BOOK_A else A, "uk", "Reader A", 1, 120L, 10L)
    private fun editionB(library: Boolean) = EditionEntity(EDITION_B, if (library) BOOK_B else B, "uk", "Reader B", 1, 180L, 20L)
    private fun bookA() = AudiobookEntity(BOOK_A, "Old A", "Shared Author", "Reader A", "Received A", 0,
        genre = "", sourceUrl = URL_A, totalDurationSeconds = 120L, totalChapters = 1)
    private fun bookB() = AudiobookEntity(BOOK_B, "Old B", "Shared Author", "Reader B", "Received B", 0,
        genre = "", sourceUrl = URL_B, totalDurationSeconds = 180L, totalChapters = 1)
    private fun sourceA() = SourceEntity("manual-audio-source-a", BOOK_A, EDITION_A, SOURCE, URL_A, addedAt = 10L)
    private fun sourceB() = SourceEntity("manual-audio-source-b", BOOK_B, EDITION_B, SOURCE, URL_B, addedAt = 20L)
    private fun chapterA() = ChapterEntity("manual-audio-chapter-a", BOOK_A, 0, "First A", 120L, EDITION_A)
    private fun chapterB() = ChapterEntity("manual-audio-chapter-b", BOOK_B, 0, "First B", 180L, EDITION_B)
    private fun trackA() = SourceTrackEntity("manual-audio-track-a", "manual-audio-source-a", 0, STREAM_A)
    private fun trackB() = SourceTrackEntity("manual-audio-track-b", "manual-audio-source-b", 0, STREAM_B)
    private fun progressA() = PlaybackProgressEntity(EDITION_A, BOOK_A, currentPositionSeconds = 31L, lastListenedAt = 111L,
        lastPausedAtEpochMs = 112L, preferredSpeed = 1.25f)

    private companion object {
        const val A = "old a|shared author"
        const val B = "old b|shared author"
        const val C = "same work|shared author"
        const val BOOK_A = "manual-audio-book-a"
        const val BOOK_B = "manual-audio-book-b"
        const val EDITION_A = "manual-audio-edition-a"
        const val EDITION_B = "manual-audio-edition-b"
        const val MANUAL_A = "manual:audio:old a|shared author"
        const val MANUAL_B = "manual:audio:old b|shared author"
        const val MANUAL_C = "manual:audio:same work|shared author"
        const val PASS_A = "rt-manual:audio:old a|shared author"
        const val PASS_B = "rt-manual:audio:old b|shared author"
        const val PASS_C = "rt-manual:audio:same work|shared author"
        const val OPERATION = "manual-audio-shapes-merge"
        const val SOURCE = "sluhayua"
        const val URL_A = "https://sluhay.com.ua/100:alias-a"
        const val URL_B = "https://sluhay.com.ua/200:alias-b"
        const val STREAM_A = "https://mp3.sluhay.com.ua/100/a.mp3"
        const val STREAM_B = "https://mp3.sluhay.com.ua/200/b.mp3"
    }
}
