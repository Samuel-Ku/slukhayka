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
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
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
class ManualWorkIdentityCreationBookOnlyReferenceRoomTest {
    @Test(timeout = 180_000)
    fun `source Work creation with Access enabled cannot capture an unrelated personal Book-only reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-source-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Other Book", "", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldBefore = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Other Book", oldBefore.title)
            assertEquals("", oldBefore.author)
            assertNull(oldBefore.workId)
            assertNull(oldBefore.mergeKey)
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            val before = typedRows(initial)
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            var outcome: Any? = null
            try {
                outcome = importer.importBookFromSource(SOURCE, detail(), writeBackProfile = false,
                    origin = LibraryEntryOrigin.EXPLICIT_IMPORT, onNewBookImported = { callbacks += it.id })
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
            }
            val oldAfter = dao.getAudiobookById(P)
            assertTrue("968_WORK_CREATION_MUST_NOT_CAPTURE_BOOK_ONLY_REFERENCE_SOURCE_ENABLED", oldAfter != null && oldAfter.workId == null)
            assertNull(outcome)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(committed))
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            assertNull(dao.getAudiobookById(B))
            assertNull(dao.getWorkById(P))
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `source Work creation with Access omitted cannot capture an unrelated personal Book-only reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-source-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Other Book", "", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldBefore = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Other Book", oldBefore.title)
            assertEquals("", oldBefore.author)
            assertNull(oldBefore.workId)
            assertNull(oldBefore.mergeKey)
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            val before = typedRows(initial)
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } })
            var outcome: Any? = null
            try {
                outcome = importer.importBookFromSource(SOURCE, detail(), writeBackProfile = false,
                    origin = LibraryEntryOrigin.EXPLICIT_IMPORT, onNewBookImported = { callbacks += it.id })
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
            }
            val oldAfter = dao.getAudiobookById(P)
            assertTrue("968_WORK_CREATION_MUST_NOT_CAPTURE_BOOK_ONLY_REFERENCE_SOURCE_OMITTED", oldAfter != null && oldAfter.workId == null)
            assertNull(outcome)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(committed))
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            assertNull(dao.getAudiobookById(B))
            assertNull(dao.getWorkById(P))
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `poster Work creation with Access enabled cannot capture an unrelated personal Book-only reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-poster-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Other Book", "", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldBefore = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Other Book", oldBefore.title)
            assertEquals("", oldBefore.author)
            assertNull(oldBefore.workId)
            assertNull(oldBefore.mergeKey)
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            val before = typedRows(initial)
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            var outcome: Any? = null
            try {
                outcome = catalog.upsertCatalogBook(CatalogBook(B, "Collision Title", "Collision Author", URL, coverImageUrl = null))
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
            }
            val oldAfter = dao.getAudiobookById(P)
            assertTrue("968_WORK_CREATION_MUST_NOT_CAPTURE_BOOK_ONLY_REFERENCE_POSTER_ENABLED", oldAfter != null && oldAfter.workId == null)
            assertNull(outcome)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(committed))
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            assertNull(dao.getAudiobookById(B))
            assertNull(dao.getWorkById(P))
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `poster Work creation with Access omitted cannot capture an unrelated personal Book-only reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-poster-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Other Book", "", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldBefore = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Other Book", oldBefore.title)
            assertEquals("", oldBefore.author)
            assertNull(oldBefore.workId)
            assertNull(oldBefore.mergeKey)
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            val before = typedRows(initial)
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } })
            var outcome: Any? = null
            try {
                outcome = catalog.upsertCatalogBook(CatalogBook(B, "Collision Title", "Collision Author", URL, coverImageUrl = null))
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
            }
            val oldAfter = dao.getAudiobookById(P)
            assertTrue("968_WORK_CREATION_MUST_NOT_CAPTURE_BOOK_ONLY_REFERENCE_POSTER_OMITTED", oldAfter != null && oldAfter.workId == null)
            assertNull(outcome)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(committed))
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            assertNull(dao.getAudiobookById(B))
            assertNull(dao.getWorkById(P))
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `enumeration Work creation with Access enabled cannot capture an unrelated personal Book-only reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-enumeration-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Other Book", "", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldBefore = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Other Book", oldBefore.title)
            assertEquals("", oldBefore.author)
            assertNull(oldBefore.workId)
            assertNull(oldBefore.mergeKey)
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            val before = typedRows(initial)
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            var outcome: Any? = null
            try {
                outcome = catalog.writeWorkEdition(sourceId = SOURCE, title = "Collision Title", author = "Collision Author",
                    narrator = "", sourceUrl = URL, language = "")
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
            }
            val oldAfter = dao.getAudiobookById(P)
            assertTrue("968_WORK_CREATION_MUST_NOT_CAPTURE_BOOK_ONLY_REFERENCE_ENUMERATION_ENABLED", oldAfter != null && oldAfter.workId == null)
            assertNull(outcome)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(committed))
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            assertNull(dao.getAudiobookById(B))
            assertNull(dao.getWorkById(P))
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `enumeration Work creation with Access omitted cannot capture an unrelated personal Book-only reference`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-enumeration-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Other Book", "", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldBefore = checkNotNull(dao.getAudiobookById(P))
            assertEquals("Other Book", oldBefore.title)
            assertEquals("", oldBefore.author)
            assertNull(oldBefore.workId)
            assertNull(oldBefore.mergeKey)
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            val before = typedRows(initial)
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } })
            var outcome: Any? = null
            try {
                outcome = catalog.writeWorkEdition(sourceId = SOURCE, title = "Collision Title", author = "Collision Author",
                    narrator = "", sourceUrl = URL, language = "")
            } catch (failure: WorkIdentityWriteRefused) {
                if (failure.refusal != IdentityWriteRefusal.ConflictingSource) throw failure
            }
            val oldAfter = dao.getAudiobookById(P)
            assertTrue("968_WORK_CREATION_MUST_NOT_CAPTURE_BOOK_ONLY_REFERENCE_ENUMERATION_OMITTED", oldAfter != null && oldAfter.workId == null)
            assertNull(outcome)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(before, typedRows(committed))
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            assertNull(dao.getAudiobookById(B))
            assertNull(dao.getWorkById(P))
            assertTrue(committed.library.works.isEmpty())
            assertTrue(committed.catalog.workSources.isEmpty())
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `own nonblank self anchor P equals K remains enrollable by poster with Access enabled`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-own-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Collision Title", "Collision Author", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            assertNull(checkNotNull(dao.getAudiobookById(P)).workId)
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val result = catalog.upsertCatalogBook(CatalogBook(P, "Collision Title", "Collision Author", OLD_URL,
                coverImageUrl = null))
            assertNotNull("968_WORK_CREATION_OWN_MATCHING_SELF_ANCHOR_REMAINS_ALLOWED", result)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            val work = committed.library.works.single()
            assertEquals(P, work.id)
            assertEquals(P, work.mergeKey)
            assertEquals("Collision Title", work.title)
            assertEquals("Collision Author", work.author)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertEquals(P, stored.workId)
            assertEquals(P, stored.mergeKey)
            assertTrue(stored.isFavorite)
            assertTrue(stored.isDownloaded)
            assertEquals(0.375f, stored.downloadProgress, 0f)
            assertEquals("PAUSED", stored.downloadState)
            val claim = committed.catalog.workSources.single()
            assertEquals(P, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(OLD_URL, claim.sourceUrl)
            assertNull(dao.getAudiobookById(B))
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `own nonblank self anchor P equals K remains enrollable by poster with Access omitted`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-own-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            dao.insertAudiobooks(listOf(AudiobookEntity(P, "Collision Title", "Collision Author", "",
                "Honest old Book-only metadata", 0, genre = "", sourceUrl = OLD_URL,
                isDownloaded = true, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.125f)))
            dao.upsertLibraryEntry(id = P, workId = P, isFavorite = true, createdAt = 123L,
                downloadProgress = 0.375f, downloadState = "PAUSED", preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE")
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            val oldEntry = initial.library.entries.single()
            assertEquals(P, oldEntry.workId)
            assertNull(checkNotNull(dao.getAudiobookById(P)).workId)
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val result = catalog.upsertCatalogBook(CatalogBook(P, "Collision Title", "Collision Author", OLD_URL,
                coverImageUrl = null))
            assertNotNull("968_WORK_CREATION_OWN_MATCHING_SELF_ANCHOR_REMAINS_ALLOWED", result)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(initial.library.books, committed.library.books)
            assertEquals(listOf(oldEntry), committed.library.entries)
            val work = committed.library.works.single()
            assertEquals(P, work.id)
            assertEquals(P, work.mergeKey)
            assertEquals("Collision Title", work.title)
            assertEquals("Collision Author", work.author)
            val stored = checkNotNull(dao.getAudiobookById(P))
            assertEquals(P, stored.workId)
            assertEquals(P, stored.mergeKey)
            assertTrue(stored.isFavorite)
            assertTrue(stored.isDownloaded)
            assertEquals(0.375f, stored.downloadProgress, 0f)
            assertEquals("PAUSED", stored.downloadState)
            val claim = committed.catalog.workSources.single()
            assertEquals(P, claim.workId)
            assertEquals(SOURCE, claim.sourceId)
            assertEquals(OLD_URL, claim.sourceUrl)
            assertNull(dao.getAudiobookById(B))
            assertNoAudio(committed)
            assertTrue(callbacks.isEmpty())
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `free source Work creation with Access enabled durably creates its normal separate physical Book`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-free-source-enabled-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            assertNull(dao.getWorkById(P))
            val access = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } }, workIdentityAccess = access)
            val result = importer.importBookFromSource(SOURCE, detail(), writeBackProfile = false,
                origin = LibraryEntryOrigin.EXPLICIT_IMPORT, onNewBookImported = { callbacks += it.id })
            assertEquals("968_WORK_CREATION_TRULY_FREE_TARGET_REMAINS_ALLOWED", B, result.id)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(B), committed.library.books.map { it.id })
            val work = committed.library.works.single()
            assertEquals(P, work.id)
            assertEquals(P, work.mergeKey)
            assertEquals("Collision Title", work.title)
            assertEquals("Collision Author", work.author)
            val entry = committed.library.entries.single()
            assertEquals(B, entry.id)
            assertEquals(P, entry.workId)
            assertEquals("EXPLICIT_IMPORT", entry.origin)
            val edition = committed.library.editions.single()
            assertEquals(B, edition.workId)
            assertEquals("New Reader", edition.narrator)
            assertEquals("uk", edition.language)
            assertEquals(1, edition.totalChapters)
            assertEquals(120L, edition.totalDurationSeconds)
            val physical = committed.library.sources.single()
            assertEquals(B, physical.bookId)
            assertEquals(edition.id, physical.editionId)
            assertEquals(SOURCE, physical.type)
            assertEquals(URL, physical.url)
            val chapter = committed.library.chapters.single()
            assertEquals(B, chapter.bookId)
            assertEquals(edition.id, chapter.editionId)
            assertEquals(0, chapter.chapterIndex)
            assertEquals("Only Chapter", chapter.title)
            assertEquals(120L, chapter.durationSeconds)
            val track = committed.library.tracks.single()
            assertEquals(physical.id, track.sourceId)
            assertEquals(0, track.trackIndex)
            assertEquals(STREAM, track.url)
            assertEquals(listOf(B), callbacks)
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 180_000)
    fun `free source Work creation with Access omitted durably creates its normal separate physical Book`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "bookonly-reference-free-source-omitted-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            var dao = database.audiobookDao()
            database.close()
            database = open()
            dao = database.audiobookDao()
            val initial = dao.readManualWorkMergeRows(ROOTS)
            assertTrue(initial.library.works.isEmpty())
            assertTrue(initial.catalog.workSources.isEmpty())
            assertTrue(initial.personal.tombstones.isEmpty())
            assertNoAudio(initial)
            val identity = database.workIdentityDao()
            val auditBefore = audit(identity)
            assertEquals(0, identity.operationCount())
            assertEquals(0, identity.redirectCount())
            val adapter = NoFetch()
            assertEquals(B, adapter.bookId(URL))
            assertNotEquals(P, adapter.bookId(URL))
            val callbacks = mutableListOf<String>()
            val workCallbacks = mutableListOf<String>()
            assertTrue(initial.library.books.isEmpty())
            assertTrue(initial.library.entries.isEmpty())
            assertNull(dao.getWorkById(P))
            val importer = LibraryImport(dao, context, listOf(adapter), onWorkImported = { workCallbacks += it },
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val catalog = SourceCatalog(dao, listOf(adapter), importer,
                writeBatchRunner = { action -> database.withTransaction { action() } })
            val result = importer.importBookFromSource(SOURCE, detail(), writeBackProfile = false,
                origin = LibraryEntryOrigin.EXPLICIT_IMPORT, onNewBookImported = { callbacks += it.id })
            assertEquals("968_WORK_CREATION_TRULY_FREE_TARGET_REMAINS_ALLOWED", B, result.id)
            val committed = dao.readManualWorkMergeRows(ROOTS)
            assertEquals(listOf(B), committed.library.books.map { it.id })
            val work = committed.library.works.single()
            assertEquals(P, work.id)
            assertEquals(P, work.mergeKey)
            assertEquals("Collision Title", work.title)
            assertEquals("Collision Author", work.author)
            val entry = committed.library.entries.single()
            assertEquals(B, entry.id)
            assertEquals(P, entry.workId)
            assertEquals("EXPLICIT_IMPORT", entry.origin)
            val edition = committed.library.editions.single()
            assertEquals(B, edition.workId)
            assertEquals("New Reader", edition.narrator)
            assertEquals("uk", edition.language)
            assertEquals(1, edition.totalChapters)
            assertEquals(120L, edition.totalDurationSeconds)
            val physical = committed.library.sources.single()
            assertEquals(B, physical.bookId)
            assertEquals(edition.id, physical.editionId)
            assertEquals(SOURCE, physical.type)
            assertEquals(URL, physical.url)
            val chapter = committed.library.chapters.single()
            assertEquals(B, chapter.bookId)
            assertEquals(edition.id, chapter.editionId)
            assertEquals(0, chapter.chapterIndex)
            assertEquals("Only Chapter", chapter.title)
            assertEquals(120L, chapter.durationSeconds)
            val track = committed.library.tracks.single()
            assertEquals(physical.id, track.sourceId)
            assertEquals(0, track.trackIndex)
            assertEquals(STREAM, track.url)
            assertEquals(listOf(B), callbacks)
            assertTrue(workCallbacks.isEmpty())
            assertEquals(auditBefore, audit(identity))
            assertEquals(0, adapter.calls)
            val durable = typedRows(committed)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(durable, typedRows(dao.readManualWorkMergeRows(ROOTS)))
            assertEquals(auditBefore, audit(database.workIdentityDao()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun detail() = SourceBookDetail(title = "Collision Title", author = "Collision Author", narrator = "New Reader",
        url = URL, language = "uk", totalDurationSeconds = 120L,
        chapters = listOf(SourceChapter("Only Chapter", STREAM, 120L)))

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
        const val P = "collision title|collision author"
        const val B = "sluhayua-700:new-import"
        const val SOURCE = "sluhayua"
        const val OLD_URL = "https://sluhay.com.ua/600:legacy"
        const val URL = "https://sluhay.com.ua/700:new-import"
        const val STREAM = "https://cdn.example.test/new-import.mp3"
        const val OPERATION = "bookonly-reference-no-manual-operation"
        val ROOTS = listOf(P, B)
    }
}
