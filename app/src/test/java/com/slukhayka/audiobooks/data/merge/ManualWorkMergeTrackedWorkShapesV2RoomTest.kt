package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
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
class ManualWorkMergeTrackedWorkShapesV2RoomTest {
    @Test(timeout = 60_000)
    fun `tracked Work entries with equal ids and no rendition apply and undo without audio`() = runBlocking {
        exercise(
            editions = emptyList(), operation = "tracked-no-edition-merge",
            previewMessage = "968_WORK_ONLY_TRACKED_ENTRY_NAMESPACE_IS_VALID",
            applyMessage = "968_TRACKED_WORK_WITHOUT_EDITIONS_MUST_APPLY",
        )
    }

    @Test(timeout = 60_000)
    fun `tracked Work entries with equal ids preserve direct catalog renditions through apply and undo`() = runBlocking {
        exercise(
            editions = listOf(editionA(), editionB()), operation = "tracked-catalog-edition-merge",
            previewMessage = "968_TRACKED_CATALOG_WORK_ENTRY_IS_NOT_AMBIGUOUS_NAMESPACE",
            applyMessage = "968_TRACKED_ENTRY_IS_NOT_A_BOOK_OWNER_OF_CATALOG_EDITION",
        )
    }

    @Test(timeout = 60_000)
    fun `manual paper ebook entries without any edition apply and undo complete producer shaped records`() = runBlocking {
        exercise(
            editions = emptyList(), operation = "manual-paper-ebook-no-edition-merge",
            previewMessage = "968_MANUAL_NONAUDIO_ENTRIES_HAVE_VALID_WORK_REFERENCES",
            applyMessage = "968_MANUAL_PAPER_EBOOK_WITHOUT_EDITION_MUST_APPLY",
            entries = listOf(manualEntryB(), manualEntryA()),
            readthroughs = listOf(manualEbook(), manualPaper()),
        )
    }

    private suspend fun exercise(
        editions: List<EditionEntity>, operation: String, previewMessage: String, applyMessage: String,
        entries: List<LibraryEntryEntity> = listOf(entryA(), entryB()),
        readthroughs: List<ReadthroughEntity> = emptyList(),
    ) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-tracked-work-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            var dao = database.audiobookDao()
            listOf(workA(), workB()).forEach { dao.upsertWork(it) }
            entries.forEach {
                dao.upsertLibraryEntry(id = it.id, workId = it.workId, origin = it.origin,
                    isFavorite = it.isFavorite, createdAt = it.createdAt,
                    downloadProgress = it.downloadProgress, downloadState = it.downloadState)
            }
            editions.forEach { dao.replaceEdition(it) }
            readthroughs.forEach { dao.upsertReadthrough(it) }
            listOf(A, B).forEach { dao.refreshWorkSearchIndex(it) }
            val before = image(dao, listOf(A, B))
            assertEquals(listOf(workA(), workB()), before.works)
            assertEquals(entries, before.entries)
            assertEquals(readthroughs, before.readthroughs)
            assertEquals(editions, before.editions)
            assertNoAudio(dao, listOf(A, B), readthroughs)
            assertTrue(database.workIdentityDao().preservedParticipants(operation).isEmpty())

            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            // A personal Entry whose ID equals its Work is not proof of an audio Book.
            assertTrue(previewMessage, prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(A, B), prepared.preview.participants.map { it.work.id }.sorted())
            assertEquals(editions.map { it.id }, prepared.preview.participants.flatMap { it.editions }.map { it.edition.id }.sorted())
            assertTrue("968_ENTRY_ONLY_IDS_MUST_NOT_BECOME_PREVIEW_BOOK_ASSOCIATIONS",
                prepared.preview.participants.flatMap { it.editions }.all { it.bookId == null })
            assertEquals(before, image(dao, listOf(A, B)))
            assertEquals(applyMessage, ManualWorkMergeApplyResult.Applied(operation, C),
                commands.applyManualWorkMerge(ManualWorkMergeApprovedPlan(prepared, operation, "Tracked Combined", "Tracked Author")))
            assertApplied(database, editions, operation, entries, readthroughs)
            database.close()
            database = open()
            assertApplied(database, editions, operation, entries, readthroughs)
            val reopenedCommands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            assertEquals("968_TRACKED_ENTRY_IDS_ARE_NOT_BOOK_PROTECTION_PAIRS_FOR_UNDO",
                ManualWorkMergeUndoResult.Undone(operation), reopenedCommands.undoManualWorkMerge(operation))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("Undo restores exact typed Work, Entry, Edition and FTS beforeimages", before, image(dao, listOf(A, B)))
            assertEquals(listOf(A, B), dao.observeWorks().first().map { it.id }.sorted())
            assertNull(dao.getWorkById(C))
            assertNoAudio(dao, listOf(A, B), readthroughs)
            assertTrue(database.workIdentityDao().preservedParticipants(operation).isEmpty())
            assertEquals(WorkMergeOperationState.UNDONE, database.workIdentityDao().operationStateById(operation))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun assertApplied(
        database: AudiobookDatabase, editions: List<EditionEntity>, operation: String,
        entries: List<LibraryEntryEntity>, readthroughs: List<ReadthroughEntity>,
    ) {
        val dao = database.audiobookDao()
        val actual = image(dao, listOf(C))
        assertEquals(listOf(WorkEntity(C, C, "Tracked Combined", "Tracked Author", addedAt = 10L)), actual.works)
        assertEquals(entries.map { it.copy(workId = C) }, actual.entries)
        assertEquals(readthroughs.map { it.copy(workId = C) }, actual.readthroughs)
        assertEquals(readthroughs.map { it.copy(workId = C) }, dao.allReadthroughs().sortedBy { it.id })
        assertEquals(editions.map { it.copy(workId = C) }, actual.editions)
        assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
        for (entry in actual.entries) assertEquals(entry, dao.libraryEntryById(entry.id))
        assertNoAudio(dao, listOf(C), readthroughs.map { it.copy(workId = C) })
        assertTrue("tracked Entry ids are never preserved real Book/Edition pairs",
            database.workIdentityDao().preservedParticipants(operation).isEmpty())
        assertEquals(WorkMergeOperationState.APPLIED, database.workIdentityDao().operationStateById(operation))
    }

    private suspend fun assertNoAudio(dao: AudiobookDao, roots: List<String>, expectedPasses: List<ReadthroughEntity>) {
        assertEquals(2, dao.countLibraryEntries())
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        val rows = dao.readManualWorkMergeRows(roots).library
        assertEquals(emptyList<String>(), listOf(
            "books" to rows.books, "sources" to rows.sources, "tracks" to rows.tracks,
            "chapters" to rows.chapters, "progress" to rows.progress, "bookmarks" to rows.bookmarks,
            "settings" to rows.settings,
            "events" to rows.events, "failures" to rows.failures, "submissions" to rows.submissions,
        ).filter { it.second.isNotEmpty() }.map { it.first })
        assertEquals(expectedPasses, rows.readthroughs.sortedBy { it.id })
    }

    private suspend fun image(dao: AudiobookDao, roots: List<String>): Image {
        val snapshot = dao.readManualWorkMergeRows(roots)
        return Image(snapshot.library.works.sortedBy { it.id }, snapshot.library.entries.sortedBy { it.id },
            snapshot.library.editions.sortedBy { it.id }, snapshot.library.readthroughs.sortedBy { it.id },
            snapshot.catalog.ftsRows.sortedBy { it.workId })
    }
    private data class Image(
        val works: List<WorkEntity>, val entries: List<LibraryEntryEntity>,
        val editions: List<EditionEntity>, val readthroughs: List<ReadthroughEntity>, val fts: List<WorkSearchFtsEntity>,
    )
    private fun workA() = WorkEntity(A, A, "Tracked A", "Tracked Author", addedAt = 10L)
    private fun workB() = WorkEntity(B, B, "Tracked B", "Tracked Author", addedAt = 20L)
    private fun entryA() = LibraryEntryEntity(A, A, "EXPLICIT_SAVE", true, 13L, 0f, "IDLE")
    private fun entryB() = LibraryEntryEntity(B, B, "EXPLICIT_SAVE", false, 23L, 0f, "IDLE")
    private fun editionA() = EditionEntity("tracked-catalog-edition-a", A, "uk", "Catalog Reader A", 7, 120L, 12L)
    private fun editionB() = EditionEntity("tracked-catalog-edition-b", B, "en", "Catalog Reader B", 9, 180L, 22L)
    private fun manualEntryA() = LibraryEntryEntity("manual:paper:tracked a|tracked author", A, "EXPLICIT_SAVE", false, 13L, 0f, "IDLE")
    private fun manualEntryB() = LibraryEntryEntity("manual:ebook:tracked b|tracked author", B, "EXPLICIT_IMPORT", false, 23L, 0f, "IDLE")
    private fun manualPaper() = ReadthroughEntity(
        "rt-manual:paper:tracked a|tracked author", "manual:paper:tracked a|tracked author", A,
        "PAPER", "IN_PROGRESS", 13L, null, null, "PAGES", 0, "[]",
    )
    private fun manualEbook() = ReadthroughEntity(
        "rt-manual:ebook:tracked b|tracked author", "manual:ebook:tracked b|tracked author", B,
        "EBOOK", "IN_PROGRESS", 23L, null, null, "PERCENT", 0, "[]",
    )
    private companion object {
        const val A = "tracked a|tracked author"
        const val B = "tracked b|tracked author"
        const val C = "tracked combined|tracked author"
    }
}
