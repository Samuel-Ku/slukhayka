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
class ManualWorkIdentityCatalogRawBookRepairRoomTest {
    @Test(timeout = 180_000)
    fun `catalog poster with Access enabled repairs raw metadata Book without Entry or Work`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "raw-only-poster-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Detached Poster", "Detached Author", "",
                "Honest legacy catalog metadata", 0, genre = "", sourceUrl = URL,
                isDownloaded = false, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(P), initial.library.books.map { it.id })
            assertTrue(initial.library.entries.isEmpty())
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            assertNotNull(dao.getAudiobookById(P))
            assertNull(dao.libraryEntryById(P))
            assertNull(dao.getWorkById(KEY))
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
            val outcome = catalog.upsertCatalogBook(CatalogBook(P, "Detached Poster", "Detached Author", URL,
                coverImageUrl = null, seriesTitle = "Detached Series",
                seriesUrl = "https://sluhay.com.ua/series/detached", seriesIndex = 3))
            assertNotNull("968_CATALOG_POSTER_RAW_BOOK_WITHOUT_ENTRY_REMAINS_REPAIRABLE", outcome)
            assertEquals(P, outcome?.id)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(initial.library.books, committed.library.books)
            val work = committed.library.works.single()
            assertEquals(KEY, work.id)
            assertEquals(KEY, work.mergeKey)
            assertEquals("Detached Poster", work.title)
            assertEquals("Detached Author", work.author)
            assertEquals("Detached Series", work.seriesTitle)
            assertEquals("https://sluhay.com.ua/series/detached", work.seriesUrl)
            assertEquals(3, work.seriesIndex)
            val entry = committed.library.entries.single()
            assertEquals(P, entry.id)
            assertEquals(KEY, entry.workId)
            assertEquals("CATALOG_SYNC", entry.origin)
            assertFalse(entry.isFavorite)
            assertEquals(0f, entry.downloadProgress, 0f)
            assertEquals("IDLE", entry.downloadState)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertEquals(KEY, stored.workId)
            assertEquals(KEY, stored.mergeKey)
            assertEquals("Detached Series", stored.seriesTitle)
            assertEquals("https://sluhay.com.ua/series/detached", stored.seriesUrl)
            assertEquals(3, stored.seriesIndex)
            assertFalse(stored.isDownloaded)
            assertEquals("", stored.narrator)
            assertEquals(0, stored.totalChapters)
            assertEquals(0L, stored.totalDurationSeconds)
            val claim = committed.catalog.workSources.single()
            assertEquals("detached poster|detached author|sluhayua|d8a988b2", claim.id)
            assertEquals(KEY, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(URL, claim.sourceUrl)
            assertFalse(claim.streamOnly)
            assertNull(claim.coverImageUrl)
            assertNull(claim.durationSeconds)
            assertTrue(claim.addedAt > 0L)
            assertNoAudio(committed)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(stored, dao.getAudiobookById(P))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `catalog poster with Access omitted repairs raw metadata Book without Entry or Work`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "raw-only-poster-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Detached Poster", "Detached Author", "",
                "Honest legacy catalog metadata", 0, genre = "", sourceUrl = URL,
                isDownloaded = false, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(P), initial.library.books.map { it.id })
            assertTrue(initial.library.entries.isEmpty())
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            assertNotNull(dao.getAudiobookById(P))
            assertNull(dao.libraryEntryById(P))
            assertNull(dao.getWorkById(KEY))
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val importer = LibraryImport(dao, context, listOf(adapter))
            val catalog = SourceCatalog(dao, listOf(adapter), importer)
            val outcome = catalog.upsertCatalogBook(CatalogBook(P, "Detached Poster", "Detached Author", URL,
                coverImageUrl = null, seriesTitle = "Detached Series",
                seriesUrl = "https://sluhay.com.ua/series/detached", seriesIndex = 3))
            assertNotNull("968_CATALOG_POSTER_RAW_BOOK_WITHOUT_ENTRY_REMAINS_REPAIRABLE", outcome)
            assertEquals(P, outcome?.id)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(initial.library.books, committed.library.books)
            val work = committed.library.works.single()
            assertEquals(KEY, work.id)
            assertEquals(KEY, work.mergeKey)
            assertEquals("Detached Poster", work.title)
            assertEquals("Detached Author", work.author)
            assertEquals("Detached Series", work.seriesTitle)
            assertEquals("https://sluhay.com.ua/series/detached", work.seriesUrl)
            assertEquals(3, work.seriesIndex)
            val entry = committed.library.entries.single()
            assertEquals(P, entry.id)
            assertEquals(KEY, entry.workId)
            assertEquals("CATALOG_SYNC", entry.origin)
            assertFalse(entry.isFavorite)
            assertEquals(0f, entry.downloadProgress, 0f)
            assertEquals("IDLE", entry.downloadState)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertEquals(KEY, stored.workId)
            assertEquals(KEY, stored.mergeKey)
            assertEquals("Detached Series", stored.seriesTitle)
            assertEquals("https://sluhay.com.ua/series/detached", stored.seriesUrl)
            assertEquals(3, stored.seriesIndex)
            assertFalse(stored.isDownloaded)
            assertEquals("", stored.narrator)
            assertEquals(0, stored.totalChapters)
            assertEquals(0L, stored.totalDurationSeconds)
            val claim = committed.catalog.workSources.single()
            assertEquals("detached poster|detached author|sluhayua|d8a988b2", claim.id)
            assertEquals(KEY, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(URL, claim.sourceUrl)
            assertFalse(claim.streamOnly)
            assertNull(claim.coverImageUrl)
            assertNull(claim.durationSeconds)
            assertTrue(claim.addedAt > 0L)
            assertNoAudio(committed)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(stored, dao.getAudiobookById(P))
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
        const val P = "sluhayua-500:raw-poster"
        const val KEY = "detached poster|detached author"
        const val SOURCE = "sluhayua"
        const val URL = "https://sluhay.com.ua/500:raw-poster"
        const val OPERATION = "raw-poster-no-manual-operation"
        val ROOTS = listOf(P, KEY)
    }
}
