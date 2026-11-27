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

/** One topology refusal candidate on the approved public Commands/import/typed Room boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkIdentityImportTopologyRefusalRoomTest {
    @Test(timeout = 60_000)
    fun `exact source import refuses a cross-Edition chapter anchor even when every count matches`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-identity-topology-refusal-${UUID.randomUUID()}.db"
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
            val originalRows = typedRows(dao.readManualWorkMergeRows(listOf(C)))
            val changedChapter = chapterA().copy(editionId = EDITION_B)
            dao.insertChapters(listOf(changedChapter))
            val chaptersA = dao.getChaptersListForBook(BOOK_A)
            assertEquals("SETUP: Book A still has one chapter", 1, chaptersA.size)
            assertEquals(changedChapter, chaptersA.single())
            assertEquals(1, checkNotNull(dao.getAudiobookById(BOOK_A)).totalChapters)
            assertEquals(1, checkNotNull(dao.getEditionById(EDITION_A)).totalChapters)
            assertEquals(EDITION_B, chaptersA.single().editionId)
            val corrupted = dao.readManualWorkMergeRows(listOf(C))
            assertEquals(listOf(trackA()), corrupted.library.tracks.filter { it.sourceId == "catalog-import-source-a" })
            assertEquals(sourceA(), corrupted.library.sources.single { it.id == "catalog-import-source-a" })
            val expectedRows = originalRows.toMutableMap()
            expectedRows["chapters"] = listOf(changedChapter, chapterB()).groupingBy<ChapterEntity, Any?> { it }.eachCount()
            val beforeRows = typedRows(corrupted)
            assertEquals("SETUP: only Chapter A editionId changed", expectedRows, beforeRows)
            val identity = database.workIdentityDao()
            val beforeAudit = listOf(identity.operationCount(), identity.redirectCount(),
                identity.operationStateById(OPERATION), identity.preservedParticipants(OPERATION),
                listOf(identity.redirectByAlias("WORK_ID", A), identity.redirectByAlias("WORK_ID", B),
                    identity.redirectByAlias("MERGE_KEY", A), identity.redirectByAlias("MERGE_KEY", B)))
            val adapter = ReceivedPage()
            val importedCallbacks = mutableListOf<String>()
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } },
                workIdentityAccess = access)
            val refusal = try {
                importer.importFromSourceUrl(SOURCE, URL_A, onNewBookImported = { importedCallbacks += it.id })
                null
            } catch (failure: WorkIdentityWriteRefused) {
                failure
            }
            assertNotNull("968_IMPORT_MUST_REFUSE_CROSS_EDITION_CHAPTER_TOPOLOGY", refusal)
            assertEquals(IdentityWriteRefusal.ConflictingSource, refusal!!.refusal)
            assertEquals(beforeRows, typedRows(dao.readManualWorkMergeRows(listOf(C))))
            assertEquals(beforeAudit, listOf(identity.operationCount(), identity.redirectCount(),
                identity.operationStateById(OPERATION), identity.preservedParticipants(OPERATION),
                listOf(identity.redirectByAlias("WORK_ID", A), identity.redirectByAlias("WORK_ID", B),
                    identity.redirectByAlias("MERGE_KEY", A), identity.redirectByAlias("MERGE_KEY", B))))
            assertEquals(listOf(URL_A), adapter.requests)
            assertEquals(emptyList<String>(), importedCallbacks)
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
