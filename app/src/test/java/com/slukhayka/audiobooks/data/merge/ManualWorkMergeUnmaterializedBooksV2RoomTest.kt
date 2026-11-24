package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeUnmaterializedBooksV2RoomTest {
    @Test(timeout = 90_000)
    fun `unmaterialized tracked Books survive apply undo and later audio materialization cannot erase manual separation`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-unmaterialized-books-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            val observedAt = System.currentTimeMillis()
            var dao = database.audiobookDao()
            val originalWorks = listOf(WorkEntity(A, A, "Unmaterialized A", "Tracked Author", addedAt = observedAt),
                WorkEntity(B, B, "Unmaterialized B", "Tracked Author", addedAt = observedAt))
            val originalEntries = listOf(LibraryEntryEntity(A, A, "EXPLICIT_SAVE", false, observedAt, 0f, "IDLE"),
                LibraryEntryEntity(B, B, "EXPLICIT_SAVE", false, observedAt, 0f, "IDLE"))
            originalWorks.forEach { dao.upsertWork(it) }
            originalEntries.forEach { dao.upsertLibraryEntry(id = it.id, workId = it.workId,
                origin = it.origin, isFavorite = it.isFavorite, createdAt = it.createdAt,
                downloadProgress = it.downloadProgress, downloadState = it.downloadState) }
            for ((id, title) in listOf(A to "Unmaterialized A", B to "Unmaterialized B")) {
                dao.insertCatalogBookIfNotTombstoned(id, title, "Tracked Author", "", "", 0,
                    null, "", "", false, 0L, 0, 0f, null)
                dao.refreshWorkSearchIndex(id)
            }
            val before = image(dao, listOf(A, B))
            assertEquals(originalWorks, before.works)
            assertEquals(originalEntries, before.entries)
            assertEquals(listOf(metadataBook(A, "Unmaterialized A"), metadataBook(B, "Unmaterialized B")), before.books)
            assertUnmaterialized(dao, listOf(A, B))
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: real metadata Books without Editions have valid references", prepared.preview.conflicts.isEmpty())
            assertTrue(prepared.preview.participants.all { it.editions.isEmpty() })
            assertEquals("968_REAL_METADATA_BOOKS_WITHOUT_EDITION_MUST_APPLY",
                ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(
                    ManualWorkMergeApprovedPlan(prepared, OPERATION, "Tracked Combined", "Tracked Author")))
            database.close()
            database = open()
            dao = database.audiobookDao()
            val applied = image(dao, listOf(C))
            assertEquals(listOf(WorkEntity(C, C, "Tracked Combined", "Tracked Author", addedAt = observedAt)), applied.works)
            assertEquals(originalEntries.map { it.copy(workId = C) }, applied.entries)
            assertEquals(before.books, applied.books)
            assertUnmaterialized(dao, listOf(C))
            assertTrue("no fake Edition IDs may be used to encode Book-only protection",
                database.workIdentityDao().preservedParticipants(OPERATION).isEmpty())
            assertEquals(ManualWorkMergeUndoResult.Undone(OPERATION),
                ManualWorkMergeCommands(database, ManualWorkMergeGate(database)).undoManualWorkMerge(OPERATION))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(before, image(dao, listOf(A, B)))
            assertNull(dao.getWorkById(C))
            assertUnmaterialized(dao, listOf(A, B))
            assertTrue(database.workIdentityDao().preservedParticipants(OPERATION).isEmpty())

            // Ordinary later typed audio rows, not a protection-table writer or a gate hook.
            materialize(dao, A, "Same materialized", "Shared Author", "Reader", URL)
            materialize(dao, B, "Same materialized", "Shared Author", "Reader", URL)
            dao.upsertWork(WorkEntity("control-work", "unprotected control|control author", "Unprotected control", "Control Author", addedAt = observedAt))
            for (id in listOf("metadata-control-1", "metadata-control-2")) {
                dao.insertAudiobooks(listOf(AudiobookEntity(id, "Unprotected control", "Control Author", "Control Reader", "", 0,
                    genre = "", sourceUrl = CONTROL_URL, totalDurationSeconds = 120L, totalChapters = 1, rating = 0f)))
                dao.upsertLibraryEntry(id, "control-work", isFavorite = false, downloadProgress = 0f, createdAt = observedAt, origin = "EXPLICIT_SAVE")
                materialize(dao, id, "Unprotected control", "Control Author", "Control Reader", CONTROL_URL)
            }
            val bookA = checkNotNull(dao.getAudiobookById(A))
            val bookB = checkNotNull(dao.getAudiobookById(B))
            assertEquals(listOf(A + "-edition", B + "-edition"), listOf(dao.getEditionForWork(A)!!.id, dao.getEditionForWork(B)!!.id))
            val gate = ManualWorkMergeGate(database)
            val merger = DuplicateWorkMerger(dao, gate) { action -> database.withTransaction { action() } }
            assertEquals("968_BOOK_ONLY_MANUAL_SEPARATION_MUST_SURVIVE_FUTURE_EDITION_IDS_AFTER_UNDO",
                1, merger.mergeOnce())
            assertEquals(bookA, dao.getAudiobookById(A))
            assertEquals(bookB, dao.getAudiobookById(B))
            assertNotNull(dao.getAudiobookById("metadata-control-1"))
            assertNull(dao.getAudiobookById("metadata-control-2"))
            assertEquals(listOf("metadata-control-1", A, B), dao.getAllAudiobooksOnce().map { it.id }.sorted())
            assertEquals(WorkMergeOperationState.UNDONE, database.workIdentityDao().operationStateById(OPERATION))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 60_000)
    fun `materialized tracked Books with equal Work ids have one proven Library owner and preserve audio through undo`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-materialized-same-id-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        val operation = "materialized-same-id-merge"
        try {
            val observedAt = System.currentTimeMillis()
            var dao = database.audiobookDao()
            for ((id, title) in listOf(A to "Unmaterialized A", B to "Unmaterialized B")) {
                dao.upsertWork(WorkEntity(id, id, title, "Tracked Author", addedAt = observedAt))
                dao.upsertLibraryEntry(id, id, isFavorite = false, downloadProgress = 0f, createdAt = observedAt, origin = "EXPLICIT_SAVE")
                dao.insertCatalogBookIfNotTombstoned(id, title, "Tracked Author", "", "", 0,
                    null, "", "", false, 0L, 0, 0f, null)
            }
            materialize(dao, A, "Unmaterialized A", "Tracked Author", "Reader A", URL)
            materialize(dao, B, "Unmaterialized B", "Tracked Author", "Reader B", "https://sluhay.com.ua/300:second")
            listOf(A, B).forEach { dao.refreshWorkSearchIndex(it) }
            val before = audioImage(dao, listOf(A, B))
            assertEquals(listOf(A, B), before.books.map { it.id })
            assertEquals(listOf(A to A, B to B), before.entries.map { it.id to it.workId })
            assertEquals(listOf(A + "-edition", B + "-edition"), before.editions.map { it.id })
            assertEquals(listOf(A, B), before.editions.map { it.workId })
            assertEquals(listOf(A to (A + "-edition"), B to (B + "-edition")), before.sources.map { it.bookId to it.editionId })
            assertEquals(listOf(A to (A + "-edition"), B to (B + "-edition")), before.chapters.map { it.bookId to it.editionId })
            assertEquals(listOf(A + "-source", B + "-source"), before.tracks.map { it.sourceId })
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("968_SAME_RAW_WORK_BOOK_ID_WITH_EXPLICIT_AUDIO_EDGES_HAS_ONE_LIBRARY_OWNER",
                prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(A, B), prepared.preview.participants.flatMap { it.editions }.map { it.bookId })
            assertEquals(ManualWorkMergeApplyResult.Applied(operation, C), commands.applyManualWorkMerge(
                ManualWorkMergeApprovedPlan(prepared, operation, "Tracked Combined", "Tracked Author")))
            database.close()
            database = open()
            dao = database.audiobookDao()
            val after = audioImage(dao, listOf(C))
            assertEquals(listOf(WorkEntity(C, C, "Tracked Combined", "Tracked Author", addedAt = observedAt)), after.works)
            assertEquals(before.entries.map { it.copy(workId = C) }, after.entries)
            assertEquals(before.books, after.books)
            assertEquals(before.editions, after.editions)
            assertEquals(before.sources, after.sources)
            assertEquals(before.chapters, after.chapters)
            assertEquals(before.tracks, after.tracks)
            assertEquals(before.progress, after.progress)
            assertEquals(before.bookmarks, after.bookmarks)
            assertEquals(listOf(A to (A + "-edition"), B to (B + "-edition")),
                database.workIdentityDao().preservedParticipants(operation).map { it.bookId to it.editionId }.sortedBy { it.first })
            assertTrue(database.workIdentityDao().preservedParticipants(operation).all { it.autoMergeExcluded })
            assertEquals(ManualWorkMergeUndoResult.Undone(operation),
                ManualWorkMergeCommands(database, ManualWorkMergeGate(database)).undoManualWorkMerge(operation))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("Undo preserves every typed physical carrier and exact search beforeimage", before, audioImage(dao, listOf(A, B)))
            assertNull(dao.getWorkById(C))
            assertEquals(listOf(A, B), dao.getAllAudiobooksOnce().map { it.id }.sorted())
            assertEquals(WorkMergeOperationState.UNDONE, database.workIdentityDao().operationStateById(operation))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun audioImage(dao: AudiobookDao, roots: List<String>): AudioImage {
        val rows = dao.readManualWorkMergeRows(roots)
        return AudioImage(rows.library.works.sortedBy { it.id }, rows.library.entries.sortedBy { it.id },
            rows.library.books.sortedBy { it.id }, rows.library.editions.sortedBy { it.id },
            rows.library.sources.sortedBy { it.id }, rows.library.chapters.sortedBy { it.id },
            rows.library.tracks.sortedBy { it.id }, rows.library.progress.sortedBy { it.editionId },
            rows.library.bookmarks.sortedBy { it.id }, rows.catalog.ftsRows.sortedBy { it.workId })
    }
    private data class AudioImage(
        val works: List<WorkEntity>, val entries: List<LibraryEntryEntity>, val books: List<ManualWorkMergeBookRow>,
        val editions: List<EditionEntity>, val sources: List<SourceEntity>, val chapters: List<ChapterEntity>,
        val tracks: List<SourceTrackEntity>, val progress: List<PlaybackProgressEntity>,
        val bookmarks: List<BookmarkEntity>, val fts: List<WorkSearchFtsEntity>,
    )

    private suspend fun materialize(dao: AudiobookDao, id: String, title: String, author: String, narrator: String, url: String) {
        dao.updateBookTitle(id, title)
        dao.updateBookNames(id, author, narrator)
        dao.updateBookSourceUrl(id, url)
        dao.updateBookStats(id, 1, 120L)
        dao.replaceEdition(EditionEntity(id + "-edition", id, "uk", narrator, 1, 120L))
        dao.insertSources(listOf(SourceEntity(id + "-source", id, id + "-edition", "sluhayua", url)))
        dao.insertChapters(listOf(ChapterEntity(id + "-chapter", id, 0, "First", 120L, id + "-edition")))
        dao.insertSourceTrackRows(listOf(SourceTrackEntity(id + "-track", id + "-source", 0, url + "/stream.mp3")))
    }
    private suspend fun assertUnmaterialized(dao: AudiobookDao, roots: List<String>) {
        val rows = dao.readManualWorkMergeRows(roots).library
        assertEquals(listOf(A, B), rows.books.map { it.id }.sorted())
        assertEquals(emptyList<String>(), listOf("editions" to rows.editions, "sources" to rows.sources,
            "tracks" to rows.tracks, "chapters" to rows.chapters, "progress" to rows.progress,
            "bookmarks" to rows.bookmarks, "readthroughs" to rows.readthroughs, "settings" to rows.settings,
            "events" to rows.events, "failures" to rows.failures, "submissions" to rows.submissions)
            .filter { it.second.isNotEmpty() }.map { it.first })
    }
    private suspend fun image(dao: AudiobookDao, roots: List<String>): Image {
        val rows = dao.readManualWorkMergeRows(roots)
        return Image(rows.library.works.sortedBy { it.id }, rows.library.entries.sortedBy { it.id },
            rows.library.books.sortedBy { it.id }, rows.catalog.ftsRows.sortedBy { it.workId })
    }
    private data class Image(val works: List<WorkEntity>, val entries: List<LibraryEntryEntity>,
        val books: List<ManualWorkMergeBookRow>, val fts: List<WorkSearchFtsEntity>)
    private fun metadataBook(id: String, title: String) = ManualWorkMergeBookRow(id, title, "Tracked Author", "", "", 0,
        null, "", "", false, 0L, 0, 0f, null)
    private companion object {
        const val A = "unmaterialized a|tracked author"
        const val B = "unmaterialized b|tracked author"
        const val C = "tracked combined|tracked author"
        const val OPERATION = "unmaterialized-book-merge"
        const val URL = "https://sluhay.com.ua/100:materialized"
        const val CONTROL_URL = "https://sluhay.com.ua/200:control"
    }
}
