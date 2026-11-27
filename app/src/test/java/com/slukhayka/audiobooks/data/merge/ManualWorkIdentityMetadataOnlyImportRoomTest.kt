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

/** One metadata-only first-materialization candidate on the approved public Commands/import/typed Room boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityMetadataOnlyImportRoomTest {
    @Test(timeout = 60_000)
    fun `known metadata-only source materializes first chapter in preserved identity after manual merge`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-metadata-only-import-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            seed(database.audiobookDao())
            assertMetadataOnly(database.audiobookDao())
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: metadata-only participant has valid stored ownership", prepared.preview.conflicts.isEmpty())
            assertEquals(ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(
                ManualWorkMergeApprovedPlan(prepared, OPERATION, "Same Work", "Shared Author")
            ))
            database.close()
            database = open()
            val dao = database.audiobookDao()
            assertMetadataOnly(dao)
            assertEquals(C, dao.libraryEntryById(BOOK_A)?.workId)
            assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
            val beforeBookA = checkNotNull(dao.getAudiobookById(BOOK_A))
            val beforeEntryA = checkNotNull(dao.libraryEntryById(BOOK_A))
            val beforeB = listOf(dao.getAudiobookById(BOOK_B), dao.getEditionById(EDITION_B),
                dao.libraryEntryById(BOOK_B), dao.getSourcesForBookSync(BOOK_B),
                dao.getChaptersListForBook(BOOK_B), dao.getTracksForSourceSync(sourceB().id),
                dao.getPlaybackProgressSyncByEdition(EDITION_B), dao.getBookmarksForBook(BOOK_B).first())
            val adapter = ReceivedPage()
            val callbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = WorkIdentityAccess(database, ManualWorkMergeGate(database)))
            var imported: AudiobookEntity? = null
            val refusal = try {
                imported = importer.importFromSourceUrl(SOURCE, URL_A, onNewBookImported = { callbacks += it.id })
                null
            } catch (failure: WorkIdentityWriteRefused) {
                failure
            }
            assertNull("968_KNOWN_METADATA_ONLY_SOURCE_MUST_ALLOW_FIRST_MATERIALIZATION", refusal?.refusal)
            assertEquals(BOOK_A, checkNotNull(imported).id)
            assertEquals(listOf(URL_A), adapter.requests)
            assertEquals(emptyList<String>(), callbacks)
            assertEquals(beforeEntryA, dao.libraryEntryById(BOOK_A))
            assertEquals(beforeBookA.copy(totalChapters = 1, totalDurationSeconds = 120L), dao.getAudiobookById(BOOK_A))
            assertEquals(editionA().copy(totalChapters = 1, totalDurationSeconds = 120L), dao.getEditionById(EDITION_A))
            assertEquals(listOf(sourceA()), dao.getSourcesForBookSync(BOOK_A))
            assertEquals(listOf(ChapterEntity("catalog-import-book-a_ch_1", BOOK_A, 0, "First A", 120L, EDITION_A)), dao.getChaptersListForBook(BOOK_A))
            assertEquals(listOf(SourceTrackEntity("catalog-import-source-a_tr_1", sourceA().id, 0, STREAM_A)), dao.getTracksForSourceSync(sourceA().id))
            assertNull(dao.getPlaybackProgressSyncByEdition(EDITION_A))
            assertTrue(dao.getBookmarksForBook(BOOK_A).first().isEmpty())
            assertEquals(beforeB, listOf(dao.getAudiobookById(BOOK_B), dao.getEditionById(EDITION_B),
                dao.libraryEntryById(BOOK_B), dao.getSourcesForBookSync(BOOK_B),
                dao.getChaptersListForBook(BOOK_B), dao.getTracksForSourceSync(sourceB().id),
                dao.getPlaybackProgressSyncByEdition(EDITION_B), dao.getBookmarksForBook(BOOK_B).first()))
            assertEquals(listOf(BOOK_A, BOOK_B), dao.getAllAudiobooksOnce().map { it.id }.sorted())
            assertEquals(listOf(EDITION_A, EDITION_B), dao.readManualWorkMergeRows(listOf(C)).library.editions.map { it.id }.sorted())
            assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
            val finalRows = dao.readManualWorkMergeRows(listOf(C))
            assertEquals(listOf(sourceA(), sourceB()), finalRows.library.sources.sortedBy { it.id })
            assertEquals(2, finalRows.catalog.workSources.size)
            assertTrue(finalRows.catalog.workSources.all { it.workId == C })
            assertEquals(WorkMergeOperationState.APPLIED, database.workIdentityDao().operationStateById(OPERATION))
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
                genre = "", sourceUrl = URL_A, totalDurationSeconds = 0L, totalChapters = 0),
            AudiobookEntity(BOOK_B, "Old B", "Shared Author", "Reader B", "Received B", 0,
                genre = "", sourceUrl = URL_B, totalDurationSeconds = 180L, totalChapters = 1),
        ))
        dao.upsertLibraryEntry(BOOK_A, A, isFavorite = true, createdAt = 10L, downloadProgress = 0f, origin = "EXPLICIT_SAVE")
        dao.upsertLibraryEntry(BOOK_B, B, isFavorite = false, createdAt = 20L, downloadProgress = 0f, origin = "EXPLICIT_SAVE")
        listOf(editionA(), editionB()).forEach { dao.replaceEdition(it) }
        dao.insertSources(listOf(sourceA(), sourceB()))
        dao.insertSourceTrackRows(listOf(trackB()))
        dao.insertChapters(listOf(chapterB()))
        dao.savePlaybackProgress(progressB())
        dao.insertBookmark(bookmarkB())
        dao.upsertWorkSource(WorkSourceEntity("catalog-import-claim-a", A, SOURCE, URL_A, addedAt = 10L))
        dao.upsertWorkSource(WorkSourceEntity("catalog-import-claim-b", B, SOURCE, URL_B, addedAt = 20L))
        dao.refreshWorkSearchIndex(A)
        dao.refreshWorkSearchIndex(B)
    }

    private suspend fun assertMetadataOnly(dao: AudiobookDao) {
        val book = checkNotNull(dao.getAudiobookById(BOOK_A))
        assertEquals(0, book.totalChapters)
        assertEquals(0L, book.totalDurationSeconds)
        assertEquals(editionA(), dao.getEditionById(EDITION_A))
        assertEquals(listOf(sourceA()), dao.getSourcesForBookSync(BOOK_A))
        assertTrue(dao.getChaptersListForBook(BOOK_A).isEmpty())
        assertTrue(dao.getChaptersListForEdition(EDITION_A).isEmpty())
        assertTrue(dao.getTracksForSourceSync(sourceA().id).isEmpty())
        assertNull(dao.getPlaybackProgressSyncByEdition(EDITION_A))
        assertTrue(dao.getBookmarksForBook(BOOK_A).first().isEmpty())
        val rows = dao.readManualWorkMergeRows(listOf(A, B, C))
        assertTrue(rows.personal.corrections.isEmpty())
        assertTrue(rows.library.readthroughs.isEmpty())
    }

    private class ReceivedPage : SourceAdapter {
        override val sourceId = SOURCE
        val requests = mutableListOf<String>()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            check(url == URL_A)
            requests += url
            return SourceBookDetail(title = "Old A", author = "Shared Author", narrator = "Reader A",
                description = "Received A", url = URL_A, language = "uk", totalDurationSeconds = 120L,
                chapters = listOf(SourceChapter("First A", STREAM_A, 120L)))
        }
        override suspend fun search(query: String): List<SourceBook> = error("no search permitted")
        override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed permitted")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no crawl permitted")
    }

    private fun editionA() = EditionEntity(EDITION_A, BOOK_A, "uk", "Reader A", 0, 0L, 10L)
    private fun editionB() = EditionEntity(EDITION_B, BOOK_B, "uk", "Reader B", 1, 180L, 20L)
    private fun sourceA() = SourceEntity("catalog-import-source-a", BOOK_A, EDITION_A, SOURCE, URL_A, addedAt = 10L)
    private fun sourceB() = SourceEntity("catalog-import-source-b", BOOK_B, EDITION_B, SOURCE, URL_B, addedAt = 20L)
    private fun trackB() = SourceTrackEntity("catalog-import-track-b", "catalog-import-source-b", 0, STREAM_B)
    private fun chapterB() = ChapterEntity("catalog-import-chapter-b", BOOK_B, 0, "First B", 180L, EDITION_B)
    private fun progressB() = PlaybackProgressEntity(EDITION_B, BOOK_B, currentPositionSeconds = 77L,
        lastListenedAt = 221L, isCompleted = true, lastPausedAtEpochMs = 222L, preferredSpeed = 0.9f)
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
