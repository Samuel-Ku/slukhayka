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
class ManualWorkIdentityCatalogPosterTombstoneRoomTest {
    @Test(timeout = 180_000)
    fun `catalog poster cannot materialize a tombstoned canonical Work under a distinct physical ID`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-poster-tombstoned-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val originalWork = WorkEntity(C, KEY, "Poster Work", "Poster Author",
                coverImageUrl = "https://covers.example.test/original.jpg", addedAt = 10L)
            dao.upsertWork(originalWork)
            dao.refreshWorkSearchIndex(C)
            val deleted = TombstoneEntity(C, 900L)
            dao.insertTombstone(deleted)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(originalWork, dao.getWorkById(C))
            assertNull(dao.getWorkById(KEY))
            assertNull(dao.getWorkById(P))
            assertNull(dao.getAudiobookById(P))
            assertFalse(dao.isBookTombstoned(P))
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertNoAudio(initial)
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
            assertEquals(listOf(deleted), initial.personal.tombstones)
            val before = typedRows(initial)
            val outcome = try {
                catalog.upsertCatalogBook(poster())
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.Tombstoned) throw failure
                null
            }
            assertNull("968_CATALOG_POSTER_MUST_NOT_MATERIALIZE_TOMBSTONED_CANONICAL_WORK", dao.getAudiobookById(P))
            assertNull(outcome)
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(originalWork, dao.getWorkById(C))
            assertEquals(listOf(deleted), dao.readManualWorkMergeRows(ROOTS).personal.tombstones)
            assertEquals(auditBefore, audit(identity))
            assertNull(dao.getWorkById(KEY))
            assertNull(dao.getWorkById(P))
            assertEquals(0, adapter.calls)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(before, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertNull(dao.getAudiobookById(P))
            assertEquals(originalWork, dao.getWorkById(C))
            assertEquals(listOf(deleted), dao.readManualWorkMergeRows(ROOTS).personal.tombstones)
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `catalog poster without tombstone materializes only metadata under its existing canonical Work`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "catalog-poster-live-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val originalWork = WorkEntity(C, KEY, "Poster Work", "Poster Author",
                coverImageUrl = "https://covers.example.test/original.jpg", addedAt = 10L)
            dao.upsertWork(originalWork)
            dao.refreshWorkSearchIndex(C)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(originalWork, dao.getWorkById(C))
            assertNull(dao.getWorkById(KEY))
            assertNull(dao.getWorkById(P))
            assertNull(dao.getAudiobookById(P))
            assertFalse(dao.isBookTombstoned(P))
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertNoAudio(initial)
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
            assertTrue(initial.personal.tombstones.isEmpty())
            val outcome = catalog.upsertCatalogBook(poster())
            assertNotNull("968_CATALOG_POSTER_WITHOUT_TOMBSTONE_MATERIALIZES_METADATA_ONLY", outcome)
            assertEquals(P, outcome?.id)
            val landed = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Poster Work", landed.title)
            assertEquals("Poster Author", landed.author)
            assertEquals("", landed.narrator)
            assertEquals(URL, landed.sourceUrl)
            assertEquals(0, landed.totalChapters)
            assertEquals(0L, landed.totalDurationSeconds)
            assertFalse(landed.isDownloaded)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(originalWork), committed.library.works)
            assertEquals(listOf(P), committed.library.books.map { it.id })
            val entry = committed.library.entries.single()
            assertEquals(P, entry.id)
            assertEquals(C, entry.workId)
            assertEquals("CATALOG_SYNC", entry.origin)
            assertFalse(entry.isFavorite)
            val claim = committed.catalog.workSources.single()
            assertEquals("opaque-catalog-poster-work|sluhayua|28f0012f", claim.id)
            assertEquals(C, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(URL, claim.sourceUrl)
            assertFalse(claim.streamOnly)
            assertNull(claim.coverImageUrl)
            assertNull(claim.durationSeconds)
            assertTrue(claim.addedAt > 0L)
            assertTrue(committed.personal.tombstones.isEmpty())
            assertNoAudio(committed)
            assertEquals(originalWork, dao.getWorkById(C))
            assertNull(dao.getWorkById(KEY))
            assertNull(dao.getWorkById(P))
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(originalWork, dao.getWorkById(C))
            assertEquals(landed, dao.getAudiobookById(P))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `catalog poster with Access enabled repairs a nonblank legacy self anchor and preserves personal state`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "poster-legacy-access-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val roots = listOf(P, KEY)
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Poster Work", "Poster Author", "",
                "Legacy description", 0, genre = "", sourceUrl = URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(roots)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val originalEntry = initial.library.entries.single()
            assertEquals(P, originalEntry.workId)
            assertNull(checkNotNull(dao.getAudiobookById(P)).mergeKey)
            val gate = ManualWorkMergeGate(database)
            val access = WorkIdentityAccess(database, gate)
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val result = catalog.upsertCatalogBook(CatalogBook(P, "Poster Work", "Poster Author", URL,
                coverImageUrl = null, seriesTitle = "Poster Series",
                seriesUrl = "https://sluhay.com.ua/series/poster", seriesIndex = 2))
            assertNotNull("968_CATALOG_POSTER_LEGACY_SELF_ANCHOR_REPAIR_REMAINS_COMPATIBLE", result)
            val committed = dao.readManualWorkMergeRows(roots)
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(originalEntry.copy(workId = KEY)), committed.library.entries)
            val work = committed.library.works.single()
            assertEquals(KEY, work.id)
            assertEquals(KEY, work.mergeKey)
            assertEquals("Poster Work", work.title)
            assertEquals("Poster Author", work.author)
            assertEquals("Poster Series", work.seriesTitle)
            assertEquals("https://sluhay.com.ua/series/poster", work.seriesUrl)
            assertEquals(2, work.seriesIndex)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertEquals(KEY, stored.workId)
            assertEquals(KEY, stored.mergeKey)
            assertEquals("Poster Series", stored.seriesTitle)
            assertEquals(2, stored.seriesIndex)
            assertTrue(stored.isFavorite)
            assertTrue(stored.isDownloaded)
            assertEquals(0.375f, stored.downloadProgress, 0f)
            assertEquals("PAUSED", stored.downloadState)
            val claim = committed.catalog.workSources.single()
            assertEquals(KEY, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(URL, claim.sourceUrl)
            assertNoAudio(committed)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(roots)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `catalog poster with Access omitted repairs a nonblank legacy self anchor and preserves personal state`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "poster-legacy-default-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val roots = listOf(P, KEY)
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Poster Work", "Poster Author", "",
                "Legacy description", 0, genre = "", sourceUrl = URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(roots)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val originalEntry = initial.library.entries.single()
            assertEquals(P, originalEntry.workId)
            assertNull(checkNotNull(dao.getAudiobookById(P)).mergeKey)
            val importer = LibraryImport(dao, context, listOf(adapter))
            val catalog = SourceCatalog(dao, listOf(adapter), importer)
            val result = catalog.upsertCatalogBook(CatalogBook(P, "Poster Work", "Poster Author", URL,
                coverImageUrl = null, seriesTitle = "Poster Series",
                seriesUrl = "https://sluhay.com.ua/series/poster", seriesIndex = 2))
            assertNotNull("968_CATALOG_POSTER_LEGACY_SELF_ANCHOR_REPAIR_REMAINS_COMPATIBLE", result)
            val committed = dao.readManualWorkMergeRows(roots)
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(originalEntry.copy(workId = KEY)), committed.library.entries)
            val work = committed.library.works.single()
            assertEquals(KEY, work.id)
            assertEquals(KEY, work.mergeKey)
            assertEquals("Poster Work", work.title)
            assertEquals("Poster Author", work.author)
            assertEquals("Poster Series", work.seriesTitle)
            assertEquals("https://sluhay.com.ua/series/poster", work.seriesUrl)
            assertEquals(2, work.seriesIndex)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertEquals(KEY, stored.workId)
            assertEquals(KEY, stored.mergeKey)
            assertEquals("Poster Series", stored.seriesTitle)
            assertEquals(2, stored.seriesIndex)
            assertTrue(stored.isFavorite)
            assertTrue(stored.isDownloaded)
            assertEquals(0.375f, stored.downloadProgress, 0f)
            assertEquals("PAUSED", stored.downloadState)
            val claim = committed.catalog.workSources.single()
            assertEquals(KEY, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(URL, claim.sourceUrl)
            assertNoAudio(committed)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(roots)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `catalog poster preserves an existing blank key personal self anchor without creating audio or Work`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "poster-blank-existing-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val roots = listOf(P, KEY)
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Untitled Poster", "", "",
                "Legacy description", 0, genre = "", sourceUrl = URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(roots)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            val originalEntry = initial.library.entries.single()
            assertEquals(P, originalEntry.workId)
            val gate = ManualWorkMergeGate(database)
            val access = WorkIdentityAccess(database, gate)
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val result = catalog.upsertCatalogBook(CatalogBook(P, "Untitled Poster", "", URL, coverImageUrl = null))
            assertNotNull("968_CATALOG_POSTER_EXISTING_BLANK_KEY_STAYS_BOOK_ONLY", result)
            val committed = dao.readManualWorkMergeRows(roots)
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(originalEntry), committed.library.entries)
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertNull(stored.workId)
            assertNull(stored.mergeKey)
            assertEquals("Untitled Poster", stored.title)
            assertEquals("", stored.author)
            assertTrue(stored.isFavorite)
            assertTrue(stored.isDownloaded)
            assertEquals(0.375f, stored.downloadProgress, 0f)
            assertEquals("PAUSED", stored.downloadState)
            assertNoAudio(committed)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(roots)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `catalog poster materializes a new blank key Book only with a technical self anchor`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "poster-blank-new-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            val roots = listOf(P, KEY)
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(roots)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            val gate = ManualWorkMergeGate(database)
            val access = WorkIdentityAccess(database, gate)
            val importer = LibraryImport(dao, context, listOf(adapter),
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val result = catalog.upsertCatalogBook(CatalogBook(P, "Untitled Poster", "", URL, coverImageUrl = null))
            assertNotNull("968_CATALOG_POSTER_NEW_BLANK_KEY_STAYS_BOOK_ONLY", result)
            assertEquals(P, result?.id)
            val committed = dao.readManualWorkMergeRows(roots)
            assertEquals(listOf(P), committed.library.books.map { it.id })
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            val entry = committed.library.entries.single()
            assertEquals(P, entry.id)
            assertEquals(P, entry.workId)
            assertEquals("CATALOG_SYNC", entry.origin)
            assertFalse(entry.isFavorite)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertNull(stored.workId)
            assertNull(stored.mergeKey)
            assertEquals("Untitled Poster", stored.title)
            assertEquals("", stored.author)
            assertEquals("", stored.narrator)
            assertEquals(URL, stored.sourceUrl)
            assertEquals(0, stored.totalChapters)
            assertEquals(0L, stored.totalDurationSeconds)
            assertFalse(stored.isDownloaded)
            assertNoAudio(committed)
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val committedRows = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(committedRows, typedRows(dao.readManualWorkMergeRows(roots)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun poster() = CatalogBook(P, "Poster Work", "Poster Author", URL, coverImageUrl = null)

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
        const val C = "opaque-catalog-poster-work"
        const val KEY = "poster work|poster author"
        const val P = "sluhayua-300:poster"
        const val SOURCE = "sluhayua"
        const val URL = "https://sluhay.com.ua/300:poster"
        const val OPERATION = "catalog-poster-no-manual-operation"
        val ROOTS = listOf(C, KEY, P)
    }
}
