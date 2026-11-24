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

/** Catalog audio renditions and independent non-audio personal relationships share only Work identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeCatalogPaperRoomTest {
    @Test(timeout = 60_000)
    fun `catalog renditions and paper ebook entries survive apply and durable undo without phantom audio`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-catalog-paper-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            var dao = database.audiobookDao()
            listOf(workA(), workB()).forEach { dao.upsertWork(it) }
            listOf(editionA(), editionB()).forEach { dao.replaceEdition(it) }
            listOf(entryA(), entryB()).forEach {
                dao.upsertLibraryEntry(
                    id = it.id, workId = it.workId, origin = it.origin,
                    isFavorite = it.isFavorite, createdAt = it.createdAt,
                    downloadProgress = it.downloadProgress, downloadState = it.downloadState,
                )
            }
            listOf(paper(), ebook()).forEach { dao.upsertReadthrough(it) }
            listOf(A, B).forEach { dao.refreshWorkSearchIndex(it) }
            val before = image(dao, listOf(A, B))
            assertEquals(listOf(workA(), workB()), before.works)
            assertEquals(listOf(editionA(), editionB()), before.editions)
            assertEquals(listOf(entryA(), entryB()), before.entries)
            assertEquals(listOf(paper(), ebook()), before.readthroughs)
            assertNoAudio(dao, listOf(A, B))
            assertTrue(database.workIdentityDao().preservedParticipants(OPERATION).isEmpty())

            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: typed paper and ebook rows are valid personal carriers", prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(A, B), prepared.preview.participants.map { it.work.id }.sorted())
            assertEquals(listOf(EDITION_A, EDITION_B), prepared.preview.participants.flatMap { it.editions }.map { it.edition.id }.sorted())
            assertTrue("SETUP: catalog renditions have no real Book association", prepared.preview.participants.flatMap { it.editions }.all { it.bookId == null })
            assertEquals(before, image(dao, listOf(A, B)))
            assertEquals(
                "968_CATALOG_PAPER_EBOOK_ENTRIES_MUST_APPLY_WITHOUT_AUDIO_BOOKS",
                ManualWorkMergeApplyResult.Applied(OPERATION, C),
                commands.applyManualWorkMerge(ManualWorkMergeApprovedPlan(prepared, OPERATION, "Paper Combined", "Paper Author")),
            )
            assertApplied(database)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertApplied(database)

            val reopenedCommands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            assertEquals(
                "968_PAPER_ENTRIES_ARE_NOT_BOOK_PROTECTION_PAIRS_AND_MUST_UNDO",
                ManualWorkMergeUndoResult.Undone(OPERATION),
                reopenedCommands.undoManualWorkMerge(OPERATION),
            )
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("Undo restores complete original typed records and search rows", before, image(dao, listOf(A, B)))
            assertNull(dao.getWorkById(C))
            assertEquals(listOf(A, B), dao.observeWorks().first().map { it.id }.sorted())
            assertNoAudio(dao, listOf(A, B))
            assertTrue("non-audio Entry IDs never become preserved Book pairs", database.workIdentityDao().preservedParticipants(OPERATION).isEmpty())
            assertEquals(WorkMergeOperationState.UNDONE, database.workIdentityDao().operationStateById(OPERATION))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun assertApplied(database: AudiobookDatabase) {
        val dao = database.audiobookDao()
        assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
        val actual = image(dao, listOf(C))
        assertEquals(listOf(WorkEntity(C, C, "Paper Combined", "Paper Author", addedAt = 10L)), actual.works)
        assertEquals(listOf(editionA().copy(workId = C), editionB().copy(workId = C)), actual.editions)
        assertEquals(listOf(entryA().copy(workId = C), entryB().copy(workId = C)), actual.entries)
        assertEquals(listOf(paper().copy(workId = C), ebook().copy(workId = C)), actual.readthroughs)
        assertEquals(listOf(paper().copy(workId = C), ebook().copy(workId = C)), dao.allReadthroughs().sortedBy { it.id })
        for (entry in actual.entries) assertEquals(entry, dao.libraryEntryById(entry.id))
        for (pass in actual.readthroughs) assertEquals(pass, dao.readthroughById(pass.id))
        assertNoAudio(dao, listOf(C))
        assertTrue("paper and ebook entries are not real Book/Edition protection pairs", database.workIdentityDao().preservedParticipants(OPERATION).isEmpty())
        assertEquals(WorkMergeOperationState.APPLIED, database.workIdentityDao().operationStateById(OPERATION))
    }

    private suspend fun assertNoAudio(dao: AudiobookDao, roots: List<String>) {
        assertEquals(2, dao.countLibraryEntries())
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        val rows = dao.readManualWorkMergeRows(roots).library
        assertEquals(emptyList<String>(), listOf(
            "books" to rows.books, "sources" to rows.sources, "tracks" to rows.tracks,
            "chapters" to rows.chapters, "progress" to rows.progress, "bookmarks" to rows.bookmarks,
            "settings" to rows.settings, "events" to rows.events, "failures" to rows.failures,
            "submissions" to rows.submissions,
        ).filter { it.second.isNotEmpty() }.map { it.first })
        assertTrue(rows.readthroughs.all { it.editionId == null })
    }

    private suspend fun image(dao: AudiobookDao, roots: List<String>): Image {
        val rows = dao.readManualWorkMergeRows(roots)
        return Image(
            rows.library.works.sortedBy { it.id }, rows.library.editions.sortedBy { it.id },
            rows.library.entries.sortedBy { it.id }, rows.library.readthroughs.sortedBy { it.id },
            rows.catalog.ftsRows.sortedBy { it.workId },
        )
    }

    private data class Image(
        val works: List<WorkEntity>, val editions: List<EditionEntity>,
        val entries: List<LibraryEntryEntity>, val readthroughs: List<ReadthroughEntity>,
        val fts: List<WorkSearchFtsEntity>,
    )

    private fun workA() = WorkEntity(A, A, "Paper A", "Paper Author", addedAt = 10L)
    private fun workB() = WorkEntity(B, B, "Paper B", "Paper Author", addedAt = 20L)
    private fun editionA() = EditionEntity(EDITION_A, A, "uk", "Catalog Reader A", 7, 120L, 12L)
    private fun editionB() = EditionEntity(EDITION_B, B, "en", "Catalog Reader B", 9, 180L, 22L)
    private fun entryA() = LibraryEntryEntity("paper-entry-a", A, "EXPLICIT_SAVE", true, 13L, 0f, "IDLE")
    private fun entryB() = LibraryEntryEntity("paper-entry-b", B, "EXPLICIT_IMPORT", false, 23L, 0f, "IDLE")
    private fun paper() = ReadthroughEntity(
        "paper-pass-a", "paper-entry-a", A, "PAPER", "IN_PROGRESS", 100L, null,
        null, "PAGES", 80,
        """[ {"at":101,"u":"PAGES","v":20}, {"at":102,"u":"PAGES","v":80}, {"futureTail":{"paper":"retain exact"}} ]""",
    )
    private fun ebook() = ReadthroughEntity(
        "paper-pass-b", "paper-entry-b", B, "EBOOK", "FINISHED", 200L, 300L,
        null, "PERCENT", 100,
        """[{"at":201,"u":"PERCENT","v":42}, {"at":300,"u":"PERCENT","v":100}, {"futureTail":{"ebook":"own units"}}]""",
    )

    private companion object {
        const val A = "paper a|paper author"
        const val B = "paper b|paper author"
        const val C = "paper combined|paper author"
        const val EDITION_A = "paper-catalog-edition-a"
        const val EDITION_B = "paper-catalog-edition-b"
        const val OPERATION = "catalog-paper-ebook-merge"
    }
}
