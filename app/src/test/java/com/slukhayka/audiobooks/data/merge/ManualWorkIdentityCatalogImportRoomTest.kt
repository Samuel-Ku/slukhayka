package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
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

/** One new candidate on the approved public Commands/catalog/import/typed Room boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityCatalogImportRoomTest {
    @Test(timeout = 60_000)
    fun `old catalog identity and exact source import retain the manually preserved narration after reopening`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-identity-catalog-import-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            seed(database.audiobookDao())
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: supported remote participants have no unresolved reference", prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(BOOK_A, BOOK_B), prepared.preview.snapshot.library.books.map { it.id }.sorted())
            assertEquals(ManualWorkMergeApplyResult.Applied(OPERATION, C), commands.applyManualWorkMerge(
                ManualWorkMergeApprovedPlan(prepared, OPERATION, "Same Work", "Shared Author")
            ))
            assertPreserved(database.audiobookDao())
            database.close()
            database = open()
            val dao = database.audiobookDao()
            assertPreserved(dao)
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val canonical = checkNotNull(dao.getWorkById(C))
            assertEquals(WorkResolution.Resolved(canonical), access.resolve(WorkIdentityInput.WorkId(A)))
            assertEquals(WorkResolution.Resolved(canonical), access.resolve(WorkIdentityInput.MergeKey(A)))
            val adapter = ReceivedPage()
            val importedCallbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = access)

            // Old title/author resolves exactly to the donor merge key, not the new canonical text.
            val refreshed = catalog.writeWorkEdition(
                sourceId = SOURCE, title = "Old A", author = "Shared Author", narrator = "Reader A",
                sourceUrl = URL_A, durationSeconds = 120L, language = "uk",
            )
            assertEquals("968_CATALOG_REFRESH_MUST_RESOLVE_OLD_WORK_IDENTITY", C, refreshed.work.id)
            assertFalse("a resolved alias is not a newly created Work", refreshed.workCreated)
            assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
            assertNull(dao.getWorkById(A))
            assertNull(dao.getWorkById(B))
            assertPreserved(dao)
            assertTrue("catalog refresh performs no source fetch", adapter.requests.isEmpty())

            repeat(2) {
                val imported = importer.importFromSourceUrl(SOURCE, URL_A,
                    onNewBookImported = { importedCallbacks += it.id })
                assertNotNull("existing exact source still opens a narration", imported)
                assertEquals("exact source evidence must reuse its preserved Book", BOOK_A, imported!!.id)
                assertEquals(EDITION_A, dao.getEditionForWork(imported.id)?.id)
                assertEquals("repeat import of an existing narration is not a new-book event", emptyList<String>(), importedCallbacks)
                assertPreserved(dao)
            }
            assertEquals(listOf(URL_A, URL_A), adapter.requests)
            assertEquals(emptyList<String>(), importedCallbacks)
            assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
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
