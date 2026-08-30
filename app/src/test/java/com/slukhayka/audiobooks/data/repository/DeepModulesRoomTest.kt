package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.contentHashOf
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.imports.ImportPlan
import com.slukhayka.audiobooks.data.imports.ImportPlanner
import com.slukhayka.audiobooks.data.imports.LocalAudioEntry
import com.slukhayka.audiobooks.data.imports.SourceRef
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Repository tests against **in-memory Room** (spec #8 seam decision: the
 * user chose real SQL over `FakeAudiobookDao` for deletion/import semantics).
 *
 * Covers:
 *  - T1: a fresh database no longer receives the mock seed books.
 *  - T2: `deleteBook` cascades chapters/bookmarks/progress and local files.
 *  - T7: `importLocalAudioStream` materialises a playable single-chapter book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeepModulesRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ADR-0002 (#140): the god module is gone — the Room tests compose the
    // five deep modules directly. This holder is a construction convenience,
    // not a facade: every test reaches the module it exercises.
    private class Modules(
        val listening: ListeningStateStore,
        val imports: LibraryImport,
        val catalog: SourceCatalog,
        val downloads: OfflineDownloads,
        val entries: LibraryEntries
    )

    private fun modules(sourceAdapters: List<com.slukhayka.audiobooks.data.source.SourceAdapter> = emptyList()): Modules {
        val imports = LibraryImport(dao, context, sourceAdapters)
        val catalog = SourceCatalog(dao, sourceAdapters, imports)
        return Modules(
            listening = ListeningStateStore(dao),
            imports = imports,
            catalog = catalog,
            downloads = OfflineDownloads(dao, context, catalog),
            entries = LibraryEntries(dao, sourceAdapters)
        )
    }

    /** Seeds the complete post-ADR-0009 aggregate used by joined DAO reads. */
    private suspend fun insertLibraryBooks(books: List<com.slukhayka.audiobooks.data.db.AudiobookEntity>) {
        dao.insertAudiobooks(books)
        books.forEach { book ->
            val workId = MergeKey.keyFor(book.title, book.author).ifBlank { book.id }
            dao.upsertWork(
                WorkEntity(
                    id = workId,
                    mergeKey = workId,
                    title = book.title,
                    author = book.author,
                    seriesTitle = book.seriesTitle,
                    seriesUrl = book.seriesUrl,
                    seriesIndex = book.seriesIndex,
                    coverImageUrl = book.coverImageUrl,
                    addedAt = TestDataFactory.FIXED_CLOCK_MS
                )
            )
            dao.upsertLibraryEntry(
                id = book.id,
                workId = workId,
                isFavorite = book.isFavorite,
                createdAt = TestDataFactory.FIXED_CLOCK_MS,
                downloadProgress = book.downloadProgress
            )
        }
    }

    // ---------------------------------------------------------------------
    // T1: empty-catalog start
    // ---------------------------------------------------------------------

    @Test
    fun `fresh database starts empty of mock seed books`() = runBlocking {
        // ADR-0002 (#138): module construction performs NO network I/O and no
        // seeding — the catalogue sync is an explicit composition-root call.
        // In no case may the old mock seed ids appear.
        modules()

        val books = dao.getAllAudiobooks().first()
        assertTrue(
            "no mock seed books may be inserted",
            books.none { it.id == "2172-ybson-vylyam-neyromant" || it.id == "cyber-dystopia-2077" || it.id == "4read-1984-orwell" }
        )
        assertTrue(dao.getChaptersListForBook("2172-ybson-vylyam-neyromant").isEmpty())
        assertTrue(dao.getBookmarksForBook("2172-ybson-vylyam-neyromant").first().isEmpty())
        assertNull(dao.getPlaybackProgressSync("2172-ybson-vylyam-neyromant"))
    }

    @Test
    fun `repository constructed without sync leaves database empty`() = runBlocking {
        modules()
        assertTrue(dao.getAllAudiobooks().first().isEmpty())
    }

    // ---------------------------------------------------------------------
    // T2: cascading book deletion
    // ---------------------------------------------------------------------

    @Test
    fun `deleteBook cascades chapters bookmarks progress and local files`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        val localFile = File(context.filesDir, "cascade-${book.id}.mp3")
        localFile.writeBytes(ByteArray(64))
        // ADR-0007: the physical copy lives on a Source TRACK, not the chapter.
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(book.mergeKey, book.id, book.narrator)
        val localSourceId = "local-$editionId"

        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(TestDataFactory.chaptersFor(book))
        dao.insertSources(
            listOf(
                com.slukhayka.audiobooks.data.db.SourceEntity(
                    id = localSourceId, bookId = book.id, editionId = editionId, type = "local", url = ""
                )
            )
        )
        dao.insertTracks(
            listOf(
                com.slukhayka.audiobooks.data.db.SourceTrackEntity(
                    id = "$localSourceId-tr-1", sourceId = localSourceId, trackIndex = 0,
                    url = localFile.absolutePath, localFilePath = localFile.absolutePath, isDownloaded = true
                )
            )
        )
        dao.insertBookmark(
            BookmarkEntity(
                bookId = book.id,
                chapterIndex = 0,
                chapterTitle = "Глава 1",
                timestampSeconds = 10L,
                note = "фікстурна закладка",
                createdAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = editionId,
                bookId = book.id,
                currentChapterIndex = 0,
                currentPositionSeconds = 10L,
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )

        mods.entries.deleteBook(book.id)

        assertNull(dao.getAudiobookById(book.id))
        assertTrue(dao.getChaptersListForBook(book.id).isEmpty())
        assertTrue(dao.getBookmarksForBook(book.id).first().isEmpty())
        assertNull(dao.getPlaybackProgressSync(book.id))
        assertTrue(dao.getTracksForBookSync(book.id).isEmpty())
        assertFalse("local file must be deleted", localFile.exists())
    }

    @Test
    fun `deleteBook leaves other books untouched`() = runBlocking {
        val mods = modules()
        val books = TestDataFactory.dataBooks()
        insertLibraryBooks(books)
        dao.insertChapters(TestDataFactory.dataChapters(books))

        mods.entries.deleteBook(books[0].id)

        val remaining = dao.getAllAudiobooks().first()
        assertEquals(books.size - 1, remaining.size)
        assertEquals(books[1].id, remaining.first().id)
        assertEquals(TestDataFactory.CHAPTERS_PER_BOOK, dao.getChaptersListForBook(books[1].id).size)
    }

    // ---------------------------------------------------------------------
    // wayfinder #25 + #26: schema migration 5 -> 6 (first two-table migration)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 5 to 6 adds the speed and pause columns`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-5-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v5 schema for the two tables the migration alters.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER)"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_progress (bookId TEXT NOT NULL PRIMARY KEY, " +
                            "currentChapterIndex INTEGER NOT NULL DEFAULT 0, currentPositionSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_5_6.migrate(db)

        assertTrue("preferredSpeed column must exist", tableColumns(db, "audiobooks").contains("preferredSpeed"))
        assertTrue("lastPausedAtEpochMs column must exist", tableColumns(db, "playback_progress").contains("lastPausedAtEpochMs"))
        db.close()
    }

    // ---------------------------------------------------------------------
    // wayfinder #39: schema migration 6 -> 7 (createdAt for "recently added")
    // ---------------------------------------------------------------------

    @Test
    fun `migration 6 to 7 adds createdAt and backfills existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-6-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v6 schema for the table the migration alters.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL)"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, " +
                            "genre, sourceUrl) VALUES ('b1', 'Книга', 'Автор', 'Читець', '', 0, '', '')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_6_7.migrate(db)

        assertTrue("createdAt column must exist", tableColumns(db, "audiobooks").contains("createdAt"))
        val backfilled = db.query("SELECT createdAt FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
        }
        assertTrue("existing rows must be backfilled with a non-zero stamp", backfilled > 0L)
        db.close()
    }

    // ---------------------------------------------------------------------
    // wayfinder #47/#48/#52 + spec-10 T2: schema migration 7 -> 8 (content
    // hash, SAF tree uri, failure ledger, mergeKey, sources, per-source PK)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 7 to 8 adds hash, tree uri, mergeKey, sources, ledger and widens the progress PK`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-7-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v7 schema for the tables the migration touches.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL, title TEXT NOT NULL, durationSeconds INTEGER NOT NULL, " +
                            "streamUrl TEXT NOT NULL, localFilePath TEXT, isDownloaded INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_progress (bookId TEXT NOT NULL PRIMARY KEY, " +
                            "currentChapterIndex INTEGER NOT NULL DEFAULT 0, currentPositionSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, " +
                            "lastPausedAtEpochMs INTEGER)"
                    )
                    db.execSQL(
                        "INSERT INTO playback_progress (bookId, currentChapterIndex, currentPositionSeconds, " +
                            "lastListenedAt, isCompleted) VALUES ('b1', 2, 500, 1700000000000, 0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_7_8.migrate(db)

        val bookColumns = tableColumns(db, "audiobooks")
        val chapterColumns = tableColumns(db, "chapters")
        assertTrue("sourceTreeUri column must exist", bookColumns.contains("sourceTreeUri"))
        assertTrue("contentHash column must exist", chapterColumns.contains("contentHash"))
        assertTrue(
            "playback_failures table must exist",
            tableColumns(db, "playback_failures").containsAll(
                setOf("id", "timestamp", "bookId", "chapterIndex", "errorCodeName", "streamUrl", "audioEngineMode")
            )
        )
        assertTrue("mergeKey column must exist", tableColumns(db, "audiobooks").contains("mergeKey"))
        assertTrue("sources table must exist", tableExists(db, "sources"))
        val progressColumns = tableColumns(db, "playback_progress")
        assertTrue("sourceKey column must exist", progressColumns.contains("sourceKey"))
        // The old row migrated with sourceKey '' and keeps its values.
        db.query("SELECT * FROM playback_progress WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("sourceKey")))
            assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("currentChapterIndex")))
            assertEquals(500, cursor.getInt(cursor.getColumnIndexOrThrow("currentPositionSeconds")))
        }
        // The composite PK now admits two rows per book.
        db.execSQL(
            "INSERT INTO playback_progress (bookId, sourceKey, currentChapterIndex, currentPositionSeconds, " +
                "lastListenedAt, isCompleted) VALUES ('b1', 'soundbooks', 1, 60, 1700000001000, 0)"
        )
        db.query("SELECT COUNT(*) FROM playback_progress WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // spec-16 T1: schema migration 8 -> 9 (the playback event log)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 8 to 9 adds playback_events and preserves all existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-8-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v8 schema: a book, its progress row and the
                    // multi-source tables the 7->8 migration produced.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, totalDurationSeconds, totalChapters) VALUES " +
                            "('b1', 'Книга', 'Автор', 'Читець', '', 0, '', 'https://4read.org/b1.html', 3600, 3)"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_progress (bookId TEXT NOT NULL, sourceKey TEXT NOT NULL, " +
                            "currentChapterIndex INTEGER NOT NULL, currentPositionSeconds INTEGER NOT NULL, " +
                            "lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL, " +
                            "lastPausedAtEpochMs INTEGER, PRIMARY KEY(bookId, sourceKey))"
                    )
                    db.execSQL(
                        "INSERT INTO playback_progress (bookId, sourceKey, currentChapterIndex, " +
                            "currentPositionSeconds, lastListenedAt, isCompleted) VALUES " +
                            "('b1', '', 1, 300, 1700000000000, 0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_8_9.migrate(db)

        val eventColumns = tableColumns(db, "playback_events")
        assertTrue(
            "playback_events table must carry the spec-16 columns",
            eventColumns.containsAll(
                setOf("id", "bookId", "sourceKey", "kind", "chapterIndex", "positionSeconds", "fromPositionSeconds", "timestamp", "deviceId")
            )
        )
        // The new log accepts an event with the entity defaults.
        db.execSQL(
            "INSERT INTO playback_events (bookId, sourceKey, kind, chapterIndex, positionSeconds, timestamp) " +
                "VALUES ('b1', '', 'RESUME', 0, 0, 1700000000000)"
        )
        db.query("SELECT COUNT(*) FROM playback_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        // Every v8 row survives untouched.
        db.query("SELECT title, totalDurationSeconds FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Книга", cursor.getString(0))
            assertEquals(3600L, cursor.getLong(1))
        }
        db.query("SELECT currentChapterIndex, currentPositionSeconds FROM playback_progress WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(300, cursor.getInt(1))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // wayfinder #42: schema migration 9 -> 10 (the re-scan fingerprint)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 9 to 10 adds lastScanFingerprint and preserves all existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-9-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v9 schema: a book, its local source row (no
                    // fingerprint yet) and a progress row.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, totalDurationSeconds, totalChapters) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', '', 3600, 3)"
                    )
                    db.execSQL(
                        "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "type TEXT NOT NULL, url TEXT NOT NULL, streamOnly INTEGER NOT NULL DEFAULT 0, " +
                            "addedAt INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "INSERT INTO sources (id, bookId, type, url, streamOnly, addedAt) VALUES " +
                            "('b1-local', 'b1', 'local', '', 0, 1700000000000)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_9_10.migrate(db)

        val sourceColumns = tableColumns(db, "sources")
        assertTrue("lastScanFingerprint column must exist", sourceColumns.contains("lastScanFingerprint"))
        // Every v9 row survives untouched — the additive column changes nothing.
        db.query("SELECT id, bookId, type FROM sources WHERE id = 'b1-local'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("b1-local", cursor.getString(0))
            assertEquals("b1", cursor.getString(1))
            assertEquals("local", cursor.getString(2))
        }
        db.query("SELECT title, totalDurationSeconds FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Кобзар", cursor.getString(0))
            assertEquals(3600L, cursor.getLong(1))
        }
        // The new column is nullable and accepts a fingerprint value.
        db.execSQL("UPDATE sources SET lastScanFingerprint = 'abc' WHERE id = 'b1-local'")
        db.close()
    }

    // ---------------------------------------------------------------------
    // wayfinder #55 Q8 / stage-2 S1: schema migration 10 -> 11 (tombstones)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 10 to 11 adds the tombstones table and preserves all existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-10-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v10 schema: a book and its local source row.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, totalDurationSeconds, totalChapters) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', '', 3600, 3)"
                    )
                    db.execSQL(
                        "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "type TEXT NOT NULL, url TEXT NOT NULL, streamOnly INTEGER NOT NULL DEFAULT 0, " +
                            "addedAt INTEGER NOT NULL DEFAULT 0, lastScanFingerprint TEXT)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_10_11.migrate(db)

        assertTrue("tombstones table must exist", tableExists(db, "tombstones"))
        assertEquals(
            setOf("bookId", "deletedAt"),
            tableColumns(db, "tombstones").toSet()
        )
        // The new table accepts a tombstone with the entity defaults.
        db.execSQL("INSERT INTO tombstones (bookId, deletedAt) VALUES ('b1', 1700000000000)")
        db.query("SELECT COUNT(*) FROM tombstones").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        // Every v10 row survives untouched.
        db.query("SELECT title, totalDurationSeconds FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Кобзар", cursor.getString(0))
            assertEquals(3600L, cursor.getLong(1))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // stage-2 S1: schema migration 11 -> 12 (identity columns, corrections,
    // series, edition_settings, failures.category — the #55/#54/#57/#60/#61
    // unified-library bump; additive only)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 11 to 12 backfills identity and creates the memory tables`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-11-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v11 schema: a merged book (has a merge key), a
                    // legacy book (no key), its chapters, a local source row,
                    // a failure ledger row and a tombstone.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, totalDurationSeconds, totalChapters, mergeKey) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', '', 3600, 3, 'кобзар|автор|читець'), " +
                            "('b2', 'Лісова пісня', 'Автор', 'Читець', '', 0, '', '', 1800, 1, '')"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL, title TEXT NOT NULL, durationSeconds INTEGER NOT NULL, " +
                            "streamUrl TEXT NOT NULL, localFilePath TEXT, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "contentHash TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, chapterIndex, title, durationSeconds, streamUrl) VALUES " +
                            "('c1', 'b1', 0, 'Розділ 1', 1200, 'http://x/1.mp3'), " +
                            "('c2', 'b2', 0, 'Розділ 1', 1800, 'http://y/1.mp3')"
                    )
                    db.execSQL(
                        "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "type TEXT NOT NULL, url TEXT NOT NULL, streamOnly INTEGER NOT NULL DEFAULT 0, " +
                            "addedAt INTEGER NOT NULL DEFAULT 0, lastScanFingerprint TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO sources (id, bookId, type, url) VALUES ('b1-local', 'b1', 'local', '')"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_failures (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "timestamp INTEGER NOT NULL, bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, " +
                            "errorCodeName TEXT NOT NULL, streamUrl TEXT NOT NULL, audioEngineMode TEXT NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO playback_failures (timestamp, bookId, chapterIndex, errorCodeName, streamUrl, " +
                            "audioEngineMode) VALUES (1700000000000, 'b1', 0, 'ERROR_CODE_IO_UNSPECIFIED', 'http://x/1.mp3', 'READY')"
                    )
                    db.execSQL(
                        "CREATE TABLE tombstones (bookId TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("INSERT INTO tombstones (bookId, deletedAt) VALUES ('b2', 1700000000000)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_11_12.migrate(db)

        // Identity columns exist and are back-filled: workId = mergeKey for
        // merged books, null for legacy; chapter editionId = bookId.
        assertTrue("workId column must exist", tableColumns(db, "audiobooks").contains("workId"))
        db.query("SELECT workId FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("кобзар|автор|читець", cursor.getString(0))
        }
        db.query("SELECT workId FROM audiobooks WHERE id = 'b2'").use { cursor ->
            cursor.moveToFirst()
            assertNull("legacy rows keep a null workId", cursor.getString(0))
        }
        assertTrue("editionId column must exist", tableColumns(db, "chapters").contains("editionId"))
        db.query("SELECT editionId FROM chapters WHERE id = 'c1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("b1", cursor.getString(0))
        }
        // failures.category is nullable until the S7 classifier lands.
        assertTrue("category column must exist", tableColumns(db, "playback_failures").contains("category"))
        // New memory tables exist with their expected columns.
        assertEquals(setOf("mergeKey", "kind", "value", "origin", "updatedAt", "updatedBy"), tableColumns(db, "corrections").toSet())
        assertEquals(setOf("id", "title", "url"), tableColumns(db, "series").toSet())
        assertEquals(setOf("workId", "seriesId", "position"), tableColumns(db, "series_members").toSet())
        assertEquals(
            setOf("bookId", "sourceKey", "rewindSeconds", "sleepTimerDefaultSeconds", "volumeBoostEnabled", "silenceSkipEnabled", "updatedAt"),
            tableColumns(db, "edition_settings").toSet()
        )
        // The new tables accept rows with the entity defaults (PK semantics).
        db.execSQL("INSERT INTO corrections (mergeKey, kind, value, origin, updatedAt) VALUES ('кобзар|автор|читець', 'NEVER_MATCH', 'b2', 'USER_MADE', 1700000000000)")
        db.execSQL("INSERT INTO series (id, title, url) VALUES ('s1', 'Цикл', 'http://4read.org/series/s1')")
        db.execSQL("INSERT INTO series_members (workId, seriesId, position) VALUES ('кобзар|автор|читець', 's1', 1)")
        db.execSQL("INSERT INTO edition_settings (bookId, sourceKey, volumeBoostEnabled, silenceSkipEnabled, updatedAt) VALUES ('b1', '', 0, 0, 1700000000000)")
        // Every v11 row survives untouched.
        db.query("SELECT title, totalDurationSeconds FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Кобзар", cursor.getString(0))
            assertEquals(3600L, cursor.getLong(1))
        }
        db.query("SELECT COUNT(*) FROM tombstones").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // spec-23 T1: schema migration 12 -> 13 (persisted Works/Editions
    // catalogue; additive only)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 12 to 13 creates the catalogue tables and preserves library data`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-12-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v12 schema: a book with its chapters and a
                    // tombstone — the rows the migration must preserve.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '', workId TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, totalDurationSeconds, totalChapters, mergeKey, workId, isDownloaded) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', '', 3600, 3, 'кобзар|автор|читець', 'кобзар|автор|читець', 1)"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL, title TEXT NOT NULL, durationSeconds INTEGER NOT NULL, " +
                            "streamUrl TEXT NOT NULL, localFilePath TEXT, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "contentHash TEXT, editionId TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, chapterIndex, title, durationSeconds, streamUrl, editionId) VALUES " +
                            "('c1', 'b1', 0, 'Розділ 1', 1200, 'http://x/1.mp3', 'b1')"
                    )
                    db.execSQL(
                        "CREATE TABLE tombstones (bookId TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("INSERT INTO tombstones (bookId, deletedAt) VALUES ('b2', 1700000000000)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_12_13.migrate(db)

        // New catalogue tables exist with their expected columns.
        assertEquals(
            setOf("id", "mergeKey", "title", "author", "narrator", "seriesTitle", "seriesIndex", "coverImageUrl", "addedAt"),
            tableColumns(db, "works").toSet()
        )
        assertEquals(
            setOf("id", "workId", "sourceId", "sourceUrl", "streamOnly", "coverImageUrl", "durationSeconds", "addedAt"),
            tableColumns(db, "editions").toSet()
        )
        // The tables accept rows: a Work + its Edition, FK intact.
        db.execSQL(
            "INSERT INTO works (id, mergeKey, title, author, narrator, addedAt) VALUES " +
                "('кобзар|автор|читець', 'кобзар|автор|читець', 'Кобзар', 'Автор', 'Читець', 1700000000000)"
        )
        db.execSQL(
            "INSERT INTO editions (id, workId, sourceId, sourceUrl, streamOnly, addedAt) VALUES " +
                "('кобзар|автор|читець|4read|x', 'кобзар|автор|читець', '4read', 'http://4read.org/kobzar', 0, 1700000000000)"
        )
        // The FK cascade works (with SQLite foreign keys enabled, as Room does
        // at runtime): deleting the Work removes its Edition.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM works WHERE id = 'кобзар|автор|читець'")
        db.query("SELECT COUNT(*) FROM editions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        // Every v12 row survives untouched.
        db.query("SELECT title, totalDurationSeconds, isDownloaded FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Кобзар", cursor.getString(0))
            assertEquals(3600L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM chapters").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM tombstones").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // ADR-0007: schema migration 13 -> 14 (Editions own Chapters, Sources
    // own tracks). The spec-23 `editions` table becomes `work_sources` (its
    // row is one SOURCE carrying a Work, not a rendition); a new domain
    // `editions` table gets one row per book; chapters drop the physical
    // playback columns (they move to `source_tracks`); sources re-parent to
    // editionId with recomputed ids; progress re-keys to editionId (per book
    // the latest row wins, the sourceKey shadows drop); bookmarks gain
    // editionId. The bookId columns are kept during this expand step.
    // ---------------------------------------------------------------------

    @Test
    fun `migration 13 to 14 re-parents the domain and preserves library data`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-13-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v13 schema: a book with a chapter (carrying the
                    // physical playback columns), a source, two progress rows
                    // (one per sourceKey — the shadow pair), a bookmark, a
                    // work and its spec-23 `editions` row.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '', workId TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, totalDurationSeconds, totalChapters, mergeKey, workId, isDownloaded) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', '', 3600, 3, 'кобзар|автор|читець', 'кобзар|автор|читець', 1), " +
                            "('b2', 'Локальна книга', 'Локальний файл', '', '', 0, '', '', 0, 0, '', '', 1)"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL, title TEXT NOT NULL, durationSeconds INTEGER NOT NULL, " +
                            "streamUrl TEXT NOT NULL, localFilePath TEXT, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "contentHash TEXT, editionId TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, chapterIndex, title, durationSeconds, streamUrl, " +
                            "localFilePath, isDownloaded, contentHash, editionId) VALUES " +
                            "('c1', 'b1', 0, 'Розділ 1', 1200, 'http://x/1.mp3', '/local/1.mp3', 1, 'aaa', 'b1')"
                    )
                    // Untouched tables (v13): the migration must preserve their
                    // rows exactly — a tombstone and one playback event.
                    db.execSQL(
                        "CREATE TABLE tombstones (bookId TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("INSERT INTO tombstones (bookId, deletedAt) VALUES ('b2', 1700000000000)")
                    db.execSQL(
                        "CREATE TABLE playback_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "bookId TEXT NOT NULL, sourceKey TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL DEFAULT 0, positionSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "fromPositionSeconds INTEGER, timestamp INTEGER NOT NULL, deviceId TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "INSERT INTO playback_events (bookId, sourceKey, kind, chapterIndex, positionSeconds, timestamp) " +
                            "VALUES ('b1', '', 'RESUME', 0, 0, 1000)"
                    )
                    db.execSQL(
                        "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "type TEXT NOT NULL, url TEXT NOT NULL, streamOnly INTEGER NOT NULL DEFAULT 0, " +
                            "addedAt INTEGER NOT NULL DEFAULT 0, lastScanFingerprint TEXT)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sources_bookId ON sources(bookId)")
                    db.execSQL(
                        "INSERT INTO sources (id, bookId, type, url, addedAt) VALUES " +
                            "('4read-b1', 'b1', '4read', 'http://4read.org/kobzar', 1700000000000)"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_progress (bookId TEXT NOT NULL, sourceKey TEXT NOT NULL, " +
                            "currentChapterIndex INTEGER NOT NULL, currentPositionSeconds INTEGER NOT NULL, " +
                            "lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL, " +
                            "lastPausedAtEpochMs INTEGER, PRIMARY KEY(bookId, sourceKey))"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_bookId ON playback_progress(bookId)")
                    db.execSQL(
                        "INSERT INTO playback_progress (bookId, sourceKey, currentChapterIndex, " +
                            "currentPositionSeconds, lastListenedAt, isCompleted, lastPausedAtEpochMs) VALUES " +
                            "('b1', '4read', 0, 100, 1000, 0, 2000), " +
                            "('b1', 'soundbooks', 1, 200, 3000, 0, NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, chapterTitle TEXT NOT NULL, " +
                            "timestampSeconds INTEGER NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
                    db.execSQL(
                        "INSERT INTO bookmarks (bookId, chapterIndex, chapterTitle, timestampSeconds, note, createdAt) " +
                            "VALUES ('b1', 1, 'Розділ 2', 120, 'нотатка', 1700000000000)"
                    )
                    db.execSQL(
                        "CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, mergeKey TEXT NOT NULL, " +
                            "title TEXT NOT NULL, author TEXT NOT NULL, narrator TEXT NOT NULL DEFAULT '', " +
                            "seriesTitle TEXT, seriesIndex INTEGER, coverImageUrl TEXT, addedAt INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO works (id, mergeKey, title, author, narrator, addedAt) VALUES " +
                            "('кобзар|автор|читець', 'кобзар|автор|читець', 'Кобзар', 'Автор', 'Читець', 1700000000000)"
                    )
                    db.execSQL(
                        "CREATE TABLE editions (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "sourceId TEXT NOT NULL, sourceUrl TEXT NOT NULL, streamOnly INTEGER NOT NULL DEFAULT 0, " +
                            "coverImageUrl TEXT, durationSeconds INTEGER, addedAt INTEGER NOT NULL, " +
                            "FOREIGN KEY(workId) REFERENCES works(id) ON DELETE CASCADE)"
                    )
                    db.execSQL(
                        "INSERT INTO editions (id, workId, sourceId, sourceUrl, streamOnly, addedAt) VALUES " +
                            "('кобзар|автор|читець|4read|1', 'кобзар|автор|читець', '4read', 'http://4read.org/kobzar', 0, 1700000000000)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_workId ON editions(workId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_sourceId ON editions(sourceId)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_13_14.migrate(db)

        // The v13→v14-era formula (narrator inside the mergeKey): the v16
        // migration remaps these ids to the ADR-0010 formula.
        val expectedEditionId = com.slukhayka.audiobooks.data.EditionId.forBookLegacy("кобзар|автор|читець", "b1")

        // The spec-23 catalogue row is renamed to work_sources (one SOURCE
        // carrying a Work) and its data survives.
        assertEquals(
            setOf("id", "workId", "sourceId", "sourceUrl", "streamOnly", "coverImageUrl", "durationSeconds", "addedAt"),
            tableColumns(db, "work_sources").toSet()
        )
        db.query("SELECT sourceId, sourceUrl FROM work_sources").use { cursor ->
            cursor.moveToFirst()
            assertEquals("4read", cursor.getString(0))
            assertEquals("http://4read.org/kobzar", cursor.getString(1))
        }
        // The new domain editions table: exactly one deterministic row per
        // book — INCLUDING the blank-mergeKey local row (id falls back to the
        // book id in EditionId.forBook).
        assertEquals(
            setOf("id", "workId", "language", "narrator", "totalChapters", "totalDurationSeconds"),
            tableColumns(db, "editions").toSet()
        )
        db.query("SELECT COUNT(*) FROM editions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        db.query("SELECT id, workId, narrator, totalChapters, totalDurationSeconds FROM editions WHERE workId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(expectedEditionId, cursor.getString(0))
            assertEquals("b1", cursor.getString(1))
            assertEquals("Читець", cursor.getString(2))
            assertEquals(3, cursor.getInt(3))
            assertEquals(3600L, cursor.getLong(4))
        }
        db.query("SELECT id FROM editions WHERE workId = 'b2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(com.slukhayka.audiobooks.data.EditionId.forBookLegacy("", "b2"), cursor.getString(0))
        }
        // Chapters: the physical playback columns moved to source_tracks and
        // editionId re-points at the domain edition.
        assertEquals(
            setOf("id", "bookId", "chapterIndex", "title", "durationSeconds", "editionId"),
            tableColumns(db, "chapters").toSet()
        )
        db.query("SELECT title, editionId FROM chapters WHERE id = 'c1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Розділ 1", cursor.getString(0))
            assertEquals(expectedEditionId, cursor.getString(1))
        }
        // source_tracks carries the chapter's physical playback data, one row
        // per (chapter, source of the chapter's book).
        db.query(
            "SELECT sourceId, trackIndex, url, localFilePath, contentHash, isDownloaded FROM source_tracks"
        ).use { cursor ->
            cursor.moveToFirst()
            // Tracks reference the RE-parented source id ($type-$editionId).
            assertEquals("4read-$expectedEditionId", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("http://x/1.mp3", cursor.getString(2))
            assertEquals("/local/1.mp3", cursor.getString(3))
            assertEquals("aaa", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
        }
        // Sources re-parent to the edition; the id is recomputed as
        // $type-$editionId (deterministic, so future writes agree).
        db.query("SELECT id, editionId FROM sources").use { cursor ->
            cursor.moveToFirst()
            assertEquals("4read-$expectedEditionId", cursor.getString(0))
            assertEquals(expectedEditionId, cursor.getString(1))
        }
        // Progress re-keys to editionId: per book the LATEST row wins, the
        // older sourceKey shadow is dropped.
        db.query("SELECT COUNT(*) FROM playback_progress").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT editionId, currentChapterIndex, currentPositionSeconds FROM playback_progress").use { cursor ->
            cursor.moveToFirst()
            assertEquals(expectedEditionId, cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(200L, cursor.getLong(2))
        }
        // Bookmarks anchor to the edition.
        db.query("SELECT editionId FROM bookmarks").use { cursor ->
            cursor.moveToFirst()
            assertEquals(expectedEditionId, cursor.getString(0))
        }
        // Untouched tables keep their exact row counts.
        db.query("SELECT COUNT(*) FROM tombstones").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM playback_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // spec-10 T2: multi-source merge and per-source position
    // ---------------------------------------------------------------------

    @Test
    fun `importBookFromSource merges the same book from two sources into one Work`() = runBlocking {
        val mods = modules()
        val detail1 = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://sound-books.net/ukrainska-literatura/100-kobzar.html",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("Розділ 1", "https://arch.sound-books.net/100/01.mp3"))
        )
        val detail2 = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "КОБЗАР",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://audiobook-mp3.com/uk-audio-99-kobzar",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("01.mp3", "https://cdn.audiobook-mp3.com/kobzar/track-0.mp3"))
        )

        val first = mods.imports.importBookFromSource("soundbooks", detail1)
        val second = mods.imports.importBookFromSource("audiobookmp3", detail2)

        // One Work card, two sources.
        assertEquals(first.id, second.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        val sources = dao.getSourcesForBookSync(first.id)
        assertEquals(2, sources.size)
        assertEquals(setOf("soundbooks", "audiobookmp3"), sources.map { it.type }.toSet())
        // ADR-0007 — the headline: TWO sources of one Work yield ONE logical
        // chapter list (the first source's, owned by the Edition) and TWO
        // track sets (one per source, 1:1 by index).
        assertNotNull(dao.getEditionForWork(first.id))
        assertEquals(1, dao.getChaptersListForBook(first.id).size)
        val tracks = dao.getTracksForBookSync(first.id)
        assertEquals(2, tracks.size)
        // Tracks hang off the deterministic re-parented source ids
        // ($type-$editionId) — one set per source, 1:1 by track index.
        val expectedSourceIds = sources.map { it.id }.toSet()
        assertEquals(expectedSourceIds, tracks.map { it.sourceId }.toSet())
        sources.forEach { s ->
            assertEquals(listOf(0), tracks.filter { it.sourceId == s.id }.map { it.trackIndex })
        }
    }

    @Test
    fun `importBookFromSource creates a second card for a different narration of the same Work`() = runBlocking {
        val mods = modules()
        val base = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            url = "https://sound-books.net/x.html",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("1", "https://arch.sound-books.net/x/01.mp3"))
        )
        val narratorA = base.copy(narrator = "Валерій Завалко", url = "https://sound-books.net/a.html")
        val narratorB = base.copy(narrator = "Богдан Бенюк", url = "https://sound-books.net/b.html")

        val a = mods.imports.importBookFromSource("soundbooks", narratorA)
        val b = mods.imports.importBookFromSource("soundbooks", narratorB)

        // ADR-0011: the narrator is the rendition (Edition) identity — two
        // narrations of the same text are ONE Work with TWO library cards,
        // each with its own Edition and listening state (ADR-0001).
        assertTrue(a.id != b.id)
        assertEquals(2, dao.getAllAudiobooks().first().size)
        assertEquals(1, dao.countWorks())
        assertEquals(2, dao.countLibraryEntries())
        // Both cards anchor to the SAME Work.
        val workIdA = dao.getAudiobookById(a.id)?.workId
        val workIdB = dao.getAudiobookById(b.id)?.workId
        assertEquals(workIdA, workIdB)
        assertTrue(workIdA!!.isNotBlank())
        // Distinct rendition ids → distinct progress rows (ADR-0001).
        val editionA = dao.getEditionForWork(a.id)!!.id
        val editionB = dao.getEditionForWork(b.id)!!.id
        assertTrue(editionA != editionB)
        assertTrue(
            com.slukhayka.audiobooks.data.EditionId.forBook(MergeKey.keyFor("Кобзар", "Тарас Шевченко"), "", "Богдан Бенюк") == editionB
        )
    }

    @Test
    fun `importBookFromSource merges the same narration into its card`() = runBlocking {
        val mods = modules()
        val base = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://sound-books.net/kobzar.html",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("1", "https://arch.sound-books.net/k/1.mp3"))
        )
        // The same narration from a second source: merges into the SAME card.
        val second = base.copy(url = "https://audiobook-mp3.com/kobzar")

        val a = mods.imports.importBookFromSource("soundbooks", base)
        val b = mods.imports.importBookFromSource("audiobookmp3", second)

        // ADR-0011: dedup is per rendition — the same narrator merges into the
        // existing card and attaches the second source (one card, two sources).
        assertEquals(a.id, b.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        assertEquals(1, dao.countWorks())
        assertEquals(2, dao.getSourcesForBookSync(a.id).size)
    }

    @Test
    fun `second narration card keeps its own progress row`() = runBlocking {
        val mods = modules()
        val base = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            url = "https://sound-books.net/x.html",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("1", "https://arch.sound-books.net/x/01.mp3"))
        )
        val narratorA = base.copy(narrator = "Валерій Завалко", url = "https://sound-books.net/a.html")
        val narratorB = base.copy(narrator = "Богдан Бенюк", url = "https://sound-books.net/b.html")

        val a = mods.imports.importBookFromSource("soundbooks", narratorA)
        val b = mods.imports.importBookFromSource("soundbooks", narratorB)
        // Each card's Listening State is keyed by its own Edition.
        mods.listening.updateProgress(a.id, chapterIndex = 1, positionSeconds = 120L)
        mods.listening.updateProgress(b.id, chapterIndex = 0, positionSeconds = 42L)

        assertEquals(2, dao.getAllPlaybackProgress().first().size)
        assertEquals(1, dao.getPlaybackProgressSync(a.id)?.currentChapterIndex)
        assertEquals(0, dao.getPlaybackProgressSync(b.id)?.currentChapterIndex)
        assertTrue(
            dao.getPlaybackProgressSync(a.id)!!.editionId != dao.getPlaybackProgressSync(b.id)!!.editionId
        )
    }

    @Test
    fun `progress survives the source switch - the Edition owns the position`() = runBlocking {
        val mods = modules()
        // The headline fixture: ONE Work, two sources (same narration → same
        // merge key → merge-on-write attaches the second source).
        val detail1 = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://sound-books.net/kobzar.html",
            chapters = listOf(
                com.slukhayka.audiobooks.data.source.SourceChapter("1", "https://arch.sound-books.net/k/1.mp3"),
                com.slukhayka.audiobooks.data.source.SourceChapter("2", "https://arch.sound-books.net/k/2.mp3")
            )
        )
        val detail2 = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "КОБЗАР",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://audiobook-mp3.com/kobzar",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("01", "https://cdn.audiobook-mp3.com/k/1.mp3"))
        )
        val book = mods.imports.importBookFromSource("soundbooks", detail1)
        mods.imports.importBookFromSource("audiobookmp3", detail2)
        assertEquals(2, dao.getSourcesForBookSync(book.id).size)
        // ADR-0007: progress is keyed by the domain Edition — the source that
        // plays is NOT part of the listening identity, so switching sources
        // mid-book keeps the position (no SOURCE_SWITCH, no shadow rows).
        val editionId = dao.getEditionForWork(book.id)!!.id

        // Listen from source A: the position lands on the Edition row.
        mods.listening.updateProgress(book.id, 1, 200L)
        val row = dao.getPlaybackProgressSync(book.id)
        assertEquals(200L, row?.currentPositionSeconds)
        assertEquals(editionId, row?.editionId)
        assertEquals(1, dao.getAllPlaybackProgress().first().size)

        // Switch to source B and keep listening: the same row updates (REPLACE
        // by editionId) — never a second row, never a lost position.
        mods.listening.updateProgress(book.id, 1, 250L)
        assertEquals(1, dao.getAllPlaybackProgress().first().size)
        assertEquals(250L, dao.getPlaybackProgressSync(book.id)?.currentPositionSeconds)
        assertEquals(editionId, dao.getPlaybackProgressSync(book.id)?.editionId)
    }

    // ---------------------------------------------------------------------
    // spec-14 T1: 4read detail flows through the adapter seam
    // ---------------------------------------------------------------------

    // Real 4read book page with the full pmovie profile (same markup shape as
    // FourReadAdapterTest.fullBookPage). Served by a FakeFetcher-driven
    // FourReadAdapter so the repository seam is exercised with no network.
    private fun fourReadPage(): String = """
        <html><head>
        <meta property="og:title" content="Неостанній бій">
        <meta property="og:image" content="https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp">
        </head><body>
        <script>var player = new Playerjs({file:"https://4read.org/m3u/7589.txt"});</script>
        <ul class="pmovie__list">
          <li><span>Жанр:</span> <a href="/svitova-literatura/">Світова література</a> / <a href="/pryhody/">Пригоди</a> / <a href="/fentezi/">Фентезі</a></li>
          <li><span>Автор:</span> <a href="/avtors/kostyantyn-shelest/">Костянтин Шелест</a></li>
          <li><span>Читає:</span> <a href="/chytaje/valerij-zavalko/">Валерій Завалко</a></li>
          <li><span>Триває:</span> 10:57:18</li>
          <li><span>Цикл:</span> <a href="https://4read.org/xfsearch/cikl/maksym-temnyj/">Максим Темний</a> (<span itemprop="volumeNumber">7</span>)</li>
        </ul>
        <div class="pmovie__rating-score">4.9</div>
        <section class="sect pmovie__related carou">
            <h2 class="sect__title sect__header"><span>Можливо,</span> Тебе зацікавить:</h2>
            <div class="sect__content grid-items">
                <div class="poster has-overlay grid-item d-flex fd-column">
                    <div class="poster__desc order-last">
                        <a href="https://4read.org/7611-vkradi-mene-zaraz.html" class="poster__link"><div class="poster__title line-clamp">Вкради мене... Зараз!</div></a>
                        <div class="poster__subtitle ws-nowrap">Сергій Оріанець</div>
                    </div>
                    <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                        <img src="/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp" loading="lazy" alt="Сергій Оріанець - ВКРАДИ МЕНЕ... ЗАРАЗ!">
                    </div>
                </div>
            </div>
        </section>
        </body></html>
    """.trimIndent()

    private val fourReadPlaylist = """[{"title":"Глава 1","file":"https://4read.org/uploads/audio/7589/01.mp3"}]"""

    private fun fourReadRepo(): Modules {
        val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(
            mapOf(
                "https://4read.org/7589-neostannij-bij.html" to fourReadPage(),
                "https://4read.org/m3u/7589.txt" to fourReadPlaylist
            )
        )
        return modules(listOf(com.slukhayka.audiobooks.data.source.FourReadAdapter(fetcher))
        )
    }

    @Test
    fun `getChaptersList never performs an implicit fetch for a chapter-less browser-only 4read book`() = runBlocking {
        val mods = fourReadRepo()
        val book = TestDataFactory.dataBooks()[0].copy(
            id = "4read-7589-neostannij-bij",
            sourceUrl = "https://4read.org/7589-neostannij-bij.html"
        )
        dao.insertAudiobooks(listOf(book))
        // ADR-0009: series persists on the WORK row — seed the Works row + the
        // entry so the page back-fill has somewhere to land.
        dao.upsertWork(
            com.slukhayka.audiobooks.data.db.WorkEntity(
                id = book.id, mergeKey = "", title = book.title,
                author = book.author, addedAt = 0L
            )
        )
        dao.upsertLibraryEntry(
            id = book.id, workId = book.id, isFavorite = false,
            createdAt = 0L, downloadProgress = 0f
        )

        val chapters = mods.catalog.getChaptersList(book.id)

        // 4read is browser-only: a background read must not bypass Cloudflare
        // or manufacture a session. The listener explicitly enters recovery.
        assertTrue(chapters.isEmpty())
        val tracks = dao.getSourcesForBookSync(book.id)
            .flatMap { dao.getTracksForSourceSync(it.id) }
        assertTrue(tracks.isEmpty())
        // A passive detail read leaves the existing book untouched.
        val stored = dao.getAudiobookById(book.id)!!
        assertEquals(book.author, stored.author)
        assertEquals(book.narrator, stored.narrator)
    }

    // ---------------------------------------------------------------------
    // spec-14 T3: the link-import door rides the seam — import-by-URL goes
    // through the adapter's fetchBookPage + the shared import path, never the
    // repository's private extraction.
    // ---------------------------------------------------------------------

    @Test
    fun `importAudiobookFrom4ReadUrl imports through the adapter with the enriched profile`() = runBlocking {
        val mods = fourReadRepo()

        val book = mods.imports.importAudiobookFrom4ReadUrl("https://4read.org/7589-neostannij-bij.html")

        // Extracted by the adapter: real title/author/narrator/chapters.
        assertNotNull(book)
        assertEquals("Неостанній бій", book!!.title)
        assertEquals("Костянтин Шелест", book.author)
        assertEquals("Валерій Завалко", book.narrator)
        assertEquals(4.9f, book.rating)
        assertEquals("Максим Темний", book.seriesTitle)
        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals(1, chapters.size)
        // ADR-0007: the physical stream lives on the source's TRACK rows.
        val tracks = dao.getSourcesForBookSync(book.id)
            .flatMap { dao.getTracksForSourceSync(it.id) }
        assertEquals("https://4read.org/uploads/audio/7589/01.mp3", tracks.single().url)
        // The shared import path writes the source row too.
        val sources = dao.getSourcesForBookSync(book.id)
        assertEquals(1, sources.size)
        assertEquals("4read", sources.single().type)
    }

    @Test
    fun `importAudiobookFrom4ReadUrl returns null when the page yields nothing playable - missing stays absent`() = runBlocking {
        val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(emptyMap())
        val mods = modules(listOf(com.slukhayka.audiobooks.data.source.FourReadAdapter(fetcher))
        )

        // A slug with no fixture returns an empty page → the adapter finds no
        // chapters → the door surfaces the absence as null (spec-14 T5: no
        // forged fallback card; a missing book never appears in the library).
        val book = mods.imports.importAudiobookFrom4ReadUrl("https://4read.org/unknown-book.html")

        assertNull(book)
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }


    @Test
    fun `fetchRelatedBooks upserts related posters through the adapter seam`() = runBlocking {
        val mods = fourReadRepo()
        val book = TestDataFactory.dataBooks()[0].copy(
            id = "4read-7589-neostannij-bij",
            sourceUrl = "https://4read.org/7589-neostannij-bij.html"
        )
        insertLibraryBooks(listOf(book))

        val related = mods.catalog.fetchRelatedBooks(book.id)

        assertEquals(1, related.size)
        assertEquals("4read-7611-vkradi-mene-zaraz", related[0].id)
        assertEquals("Вкради мене... Зараз!", related[0].title)
        assertEquals("Сергій Оріанець", related[0].author)
    }

    // ---------------------------------------------------------------------
    // spec-13 T3: import from a WebView-source captured page (sluhay)
    // ---------------------------------------------------------------------

    // Same markup shape as the T1 capture (trimmed): og:title with the
    // « » <Site>» suffix, authoritative meta rows, data-src cover, inline
    // Playerjs playlist URL. The playlist fetch goes through the adapter's
    // FakeFetcher — no network.
    private fun sluhayCapturedPage(): String = """
        <html><head>
        <meta property="og:title" content="Трохи ненависті - Джо Аберкромбі » Слухай безкоштовні АудіоКниги онлайн українською мовою" />
        <meta property="og:url" content="https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html" />
        <meta property="og:description" content="Над Адуа зависочіли промислові труби." />
        </head><body>
        <h1>Трохи ненависті - Джо Аберкромбі</h1>
        <ul class="pmovie__list">
        <li><span>Назва</span> <span>Трохи ненависті</span></li>
        <li><span>Автор</span> <span>Джо Аберкромбі</span></li>
        <li><span>Тривалість</span> <span>16:41:11</span></li>
        </ul>
        <img data-src="/uploads/posts/books/6150/dzho-aberkrombi-trohi-nenavisti.webp" src="/uploads/posts/books/6150/dzho-aberkrombi-trohi-nenavisti.webp">
        <script>
        Playerjs({id:"playerjs1",file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt"});
        </script>
        </body></html>
    """.trimIndent()

    private val sluhayPlaylist =
        """[{"title":"Трохи ненависті 01.mp3","file":"https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3"}]"""

    private val sluhayBookUrl = "https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html"

    @Test
    fun `importWebSourcePage imports a captured sluhay book with its source row and chapters`() = runBlocking {
        val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(
            mapOf(
                "https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt" to sluhayPlaylist
            )
        )
        val mods = modules(listOf(com.slukhayka.audiobooks.data.source.SluhayAdapter(fetcher))
        )

        val book = mods.imports.importWebSourcePage("sluhay", sluhayBookUrl, sluhayCapturedPage())

        assertNotNull(book)
        assertEquals("Трохи ненависті", book!!.title)
        assertEquals("Джо Аберкромбі", book.author)
        assertEquals(sluhayBookUrl, book.sourceUrl)
        val sources = dao.getSourcesForBookSync(book.id)
        assertEquals(1, sources.size)
        assertEquals("sluhay", sources.single().type)
        assertEquals(sluhayBookUrl, sources.single().url)
        assertFalse("sluhay downloads are allowed (robots open)", sources.single().streamOnly)
        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals(1, chapters.size)
        // ADR-0007: the physical stream lives on the source's TRACK rows.
        val tracks = dao.getSourcesForBookSync(book.id)
            .flatMap { dao.getTracksForSourceSync(it.id) }
        assertEquals("https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3", tracks.single().url)
    }

    @Test
    fun `importWebSourcePage returns null for unknown source unparseable page or unplayable page`() = runBlocking {
        val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(emptyMap())
        val mods = modules(listOf(com.slukhayka.audiobooks.data.source.SluhayAdapter(fetcher))
        )

        // Unknown source id.
        assertNull(mods.imports.importWebSourcePage("nope", sluhayBookUrl, sluhayCapturedPage()))
        // Captured HTML with no playlist and no chapters.
        assertNull(mods.imports.importWebSourcePage("sluhay", sluhayBookUrl, "<html><body>nope</body></html>"))
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `importWebSourcePage with a different narration creates a second rendition card of the same Work`() = runBlocking {
        val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(
            mapOf(
                "https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt" to sluhayPlaylist
            )
        )
        val mods = modules(listOf(com.slukhayka.audiobooks.data.source.SluhayAdapter(fetcher))
        )
        // A 4read copy already in the library. The 4read source carries no
        // narrator metadata, so its card gets the "4read narrator" rendition
        // placeholder; the sluhay captured page likewise yields "sluhay
        // narrator" — a DIFFERENT rendition of the same Work (ADR-0011).
        val existing = mods.imports.importBookFromSource(
            "4read",
            com.slukhayka.audiobooks.data.source.SourceBookDetail(
                title = "Трохи ненависті",
                author = "Джо Аберкромбі",
                url = "https://4read.org/6150-trohi-nenavisti.html",
                chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("1", "https://4read.org/a/1.mp3"))
            )
        )

        val second = mods.imports.importWebSourcePage("sluhay", sluhayBookUrl, sluhayCapturedPage())

        // Two rendition cards under ONE Work (same merge key), each with its
        // own edition and its own source.
        assertNotNull(second)
        assertNotEquals(existing.id, second!!.id)
        assertEquals(2, dao.getAllAudiobooks().first().size)
        assertEquals(existing.mergeKey, second.mergeKey)
        assertNotEquals(dao.getEditionForWork(existing.id)?.id, dao.getEditionForWork(second.id)?.id)
        assertEquals("4read", dao.getSourcesForBookSync(existing.id).single().type)
        assertEquals("sluhay", dao.getSourcesForBookSync(second.id).single().type)
    }

    // ---------------------------------------------------------------------
    // spec-10 T6: downloads across sources
    // ---------------------------------------------------------------------

    @Test
    fun `download is refused for a stream-only book without touching the network`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0].copy(
            sourceUrl = "https://lihtar.in.ua/biblioteka/khudozhnja-literatura/slovo",
            // The first fixture book is downloaded by default; the refusal
            // must be observable as a book that stays un-downloaded.
            isDownloaded = false
        ).also { it.downloadProgress = 0f }
        insertLibraryBooks(listOf(book))
        dao.insertChapters(TestDataFactory.chaptersFor(book))

        val result = mods.downloads.downloadAudiobookOffline(book.id)

        // Stream-only: refused up front, no files, no state change.
        assertEquals(0, result.downloadedChapters)
        assertEquals(0, result.totalChapters)
        val stored = dao.getAudiobookById(book.id)
        assertFalse(stored!!.isDownloaded)
        assertEquals(0f, stored.downloadProgress)
        // ADR-0007: download state lives on the TRACK rows.
        assertTrue(dao.getTracksForBookSync(book.id).none { it.isDownloaded })
    }

    @Test
    fun `download loop runs for a non-stream-only book and reports the outcome`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0].copy(
            sourceUrl = "https://4read.org/7589-neostannij-bij.html",
            isDownloaded = false
        ).also { it.downloadProgress = 0f }
        insertLibraryBooks(listOf(book))
        // Non-http stream urls: the loop executes, skips the network hop and
        // reports every chapter as failed — exercising the whole download path
        // with fakes (in-memory Room), no network. No source/track rows exist,
        // so the playable pairs carry null tracks (ADR-0007) and every chapter
        // stays failed.
        dao.insertChapters(
            listOf(
                ChapterEntity("${book.id}_ch1", book.id, 0, "Глава 1", 60L),
                ChapterEntity("${book.id}_ch2", book.id, 1, "Глава 2", 60L)
            )
        )

        val result = mods.downloads.downloadAudiobookOffline(book.id)

        assertEquals(2, result.totalChapters)
        assertEquals(0, result.downloadedChapters)
        assertFalse(dao.getAudiobookById(book.id)!!.isDownloaded)
        // ADR-0007: download state lives on the TRACK rows.
        assertTrue(dao.getTracksForBookSync(book.id).none { it.isDownloaded })
    }

    @Test
    fun `local import records a LOCAL source row`() = runBlocking {
        val mods = modules()

        val book = mods.imports.importLocalAudioStream("Моя книга.mp3", ByteArrayInputStream(ByteArray(32)))

        val sources = dao.getSourcesForBookSync(book.id)
        assertEquals(1, sources.size)
        assertEquals("local", sources.single().type)
        assertEquals("", sources.single().url)
        // isLocal derivation stays on the blank sourceUrl, untouched.
        assertEquals("", book.sourceUrl)
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        return columns
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { cursor ->
            return cursor.moveToFirst()
        }
    }

    // ---------------------------------------------------------------------
    // wayfinder #26: per-book preferred speed persistence
    // ---------------------------------------------------------------------

    @Test
    fun `preferred speed round-trips through the repository`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        insertLibraryBooks(listOf(book))
        // ADR-0009: the preference lives on the Listening State row — it needs
        // the Edition anchor + a progress row to land.
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook("", book.id, book.narrator)
        dao.insertEdition(com.slukhayka.audiobooks.data.db.EditionEntity(id = editionId, workId = book.id))
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = editionId, bookId = book.id,
                currentChapterIndex = 0, currentPositionSeconds = 10L,
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )

        mods.listening.setPreferredSpeed(book.id, 1.5f)

        assertEquals(1.5f, dao.getAudiobookById(book.id)?.preferredSpeed ?: 0f, 0.001f)

        mods.listening.setPreferredSpeed(book.id, null)
        assertNull(dao.getAudiobookById(book.id)?.preferredSpeed)
    }

    // ---------------------------------------------------------------------
    // wayfinder #25: last-pause marker persistence
    // ---------------------------------------------------------------------

    @Test
    fun `pause marker round-trips through the repository`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        dao.insertAudiobooks(listOf(book))
        // Frozen timestamp — the default lastListenedAt is wall clock and
        // would make this (and any) fixture row non-deterministic. ADR-0007:
        // progress is keyed by the Edition.
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = com.slukhayka.audiobooks.data.EditionId.forBook(book.mergeKey, book.id, book.narrator),
                bookId = book.id,
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )

        mods.listening.updatePausedAt(book.id, 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, dao.getPlaybackProgressSync(book.id)?.lastPausedAtEpochMs)

        mods.listening.updatePausedAt(book.id, null)
        assertNull(dao.getPlaybackProgressSync(book.id)?.lastPausedAtEpochMs)
    }

    // ---------------------------------------------------------------------
    // wayfinder #28: three-level deletion — removeFromLibrary keeps files
    // ---------------------------------------------------------------------

    @Test
    fun `removeFromLibrary deletes rows but keeps local files`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        val localFile = File(context.filesDir, "keep-${book.id}.mp3")
        localFile.writeBytes(ByteArray(64))

        // ADR-0007: the physical copy lives on a Source TRACK, not the chapter.
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(book.mergeKey, book.id, book.narrator)
        val localSourceId = "local-$editionId"
        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(TestDataFactory.chaptersFor(book))
        dao.insertSources(
            listOf(
                com.slukhayka.audiobooks.data.db.SourceEntity(
                    id = localSourceId, bookId = book.id, editionId = editionId, type = "local", url = ""
                )
            )
        )
        dao.insertTracks(
            listOf(
                com.slukhayka.audiobooks.data.db.SourceTrackEntity(
                    id = "$localSourceId-tr-1", sourceId = localSourceId, trackIndex = 0,
                    url = localFile.absolutePath, localFilePath = localFile.absolutePath, isDownloaded = true
                )
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = editionId,
                bookId = book.id,
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )

        mods.entries.removeFromLibrary(book.id)

        assertNull(dao.getAudiobookById(book.id))
        assertTrue(dao.getChaptersListForBook(book.id).isEmpty())
        assertNull(dao.getPlaybackProgressSync(book.id))
        assertTrue("files must survive removeFromLibrary", localFile.exists())
        localFile.delete()
        Unit
    }

    // ---------------------------------------------------------------------
    // spec-9 T1: series metadata persistence
    // ---------------------------------------------------------------------

    @Test
    fun `upserting a catalog book persists its series metadata`() = runBlocking {
        val mods = modules()

        mods.catalog.upsertCatalogBook(
            CatalogBook(
                id = "4read-7589-neostannij-bij",
                title = "Неостанній бій",
                author = "Костянтин Шелест",
                url = "https://4read.org/7589-neostannij-bij.html",
                coverImageUrl = null,
                seriesTitle = "Максим Темний",
                seriesUrl = "https://4read.org/xfsearch/cikl/maksym-temnyj/",
                seriesIndex = 7
            )
        )

        val stored = dao.getAudiobookById("4read-7589-neostannij-bij")
        assertNotNull(stored)
        assertEquals("Максим Темний", stored!!.seriesTitle)
        assertEquals("https://4read.org/xfsearch/cikl/maksym-temnyj/", stored.seriesUrl)
        assertEquals(7, stored.seriesIndex)
    }

    @Test
    fun `re-upserting a known book back-fills its series metadata without losing user state`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        // Insert a book that predates series metadata (e.g. from an earlier sync).
        // ADR-0009: favourite lives on the Library Entry row — seed it.
        dao.insertAudiobooks(listOf(book.copy(isDownloaded = true).also { it.isFavorite = true }))
        dao.upsertLibraryEntry(
            id = book.id, workId = book.id, isFavorite = true,
            createdAt = TestDataFactory.FIXED_CLOCK_MS, downloadProgress = 0f
        )

        mods.catalog.upsertCatalogBook(
            CatalogBook(
                id = book.id,
                title = book.title,
                author = book.author,
                url = book.sourceUrl,
                coverImageUrl = null,
                seriesTitle = "Сага про Дріззта",
                seriesUrl = "https://4read.org/xfsearch/cikl/drizzt/",
                seriesIndex = 2
            )
        )

        val stored = dao.getAudiobookById(book.id)!!
        assertEquals("Сага про Дріззта", stored.seriesTitle)
        assertEquals(2, stored.seriesIndex)
        // User state must survive the back-fill.
        assertTrue(stored.isFavorite)
        assertTrue(stored.isDownloaded)
        // Unrelated fields untouched.
        assertEquals(book.title, stored.title)
    }

    @Test
    fun `upserting a book without series metadata leaves stored series untouched`() = runBlocking {
        val mods = modules()
        // ADR-0009: series persists on the WORK row — seed the book, its
        // Works row (by the key the catalogue write path uses) and the entry.
        val book = TestDataFactory.dataBooks()[1]
        val key = MergeKey.keyFor(book.title, book.author)
        dao.insertAudiobooks(listOf(book))
        dao.upsertWork(
            com.slukhayka.audiobooks.data.db.WorkEntity(
                id = key, mergeKey = key, title = book.title, author = book.author,
                seriesTitle = "Старий цикл", seriesUrl = "https://4read.org/xfsearch/cikl/old/",
                seriesIndex = 1, addedAt = 0L
            )
        )
        dao.upsertLibraryEntry(
            id = book.id, workId = key, isFavorite = false, createdAt = 0L, downloadProgress = 0f
        )

        mods.catalog.upsertCatalogBook(
            CatalogBook(
                id = TestDataFactory.dataBooks()[1].id,
                title = "1984",
                author = "Джордж Орвелл",
                url = "https://4read.org/1984.html",
                coverImageUrl = null,
                seriesTitle = null,
                seriesUrl = null,
                seriesIndex = null
            )
        )

        val stored = dao.getAudiobookById(TestDataFactory.dataBooks()[1].id)!!
        assertEquals("Старий цикл", stored.seriesTitle)
        assertEquals(1, stored.seriesIndex)
    }

    // ---------------------------------------------------------------------
    // T7: local audio import
    // ---------------------------------------------------------------------

    @Test
    fun `importLocalAudioStream creates a downloadable single-chapter book`() = runBlocking {
        val mods = modules()

        val book = mods.imports.importLocalAudioStream("Моя книга.mp3", ByteArrayInputStream(ByteArray(32)))

        assertEquals("Моя книга", book.title)
        assertEquals("Локальний файл", book.author)
        assertTrue(book.isDownloaded)
        assertEquals(1, book.totalChapters)

        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals(1, chapters.size)
        assertEquals(book.id, chapters.first().bookId)
        assertEquals(0, chapters.first().chapterIndex)
        // ADR-0007: the physical copy lives on the local source's TRACK.
        val tracks = dao.getTracksForBookSync(book.id)
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertTrue("local file must exist", File(track.localFilePath!!).exists())
        assertEquals(track.localFilePath, track.url)

        // The imported book shows in the downloaded set.
        val downloaded = dao.getDownloadedAudiobooks().first()
        assertTrue(downloaded.any { it.id == book.id })
    }

    // ---------------------------------------------------------------------
    // Block 4: folder import (SAF tree) — grouping core
    // ---------------------------------------------------------------------

    @Test
    fun `importAudioEntries groups each folder into one book and root files into individual books`() = runBlocking {
        val mods = modules()

        val result = mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) },
                LocalAudioEntry("Лісова пісня.mp3", null) { ByteArrayInputStream(ByteArray(18)) },
                LocalAudioEntry("Промова.mp3", "Історія") { ByteArrayInputStream(ByteArray(19)) }
            )
        )

        assertEquals(3, result.booksImported)
        assertEquals(4, result.filesImported)
        assertEquals(0, result.skippedFiles)

        val books = dao.getAllAudiobooks().first()
        val folderBook = books.first { it.title == "Кобзар" }
        val rootBook = books.first { it.title == "Лісова пісня" }
        val historyBook = books.first { it.title == "Історія" }

        // Folder book: two chapters, all downloaded, pointing at copied files
        // (ADR-0007: the copies live on the TRACK rows).
        assertEquals(2, dao.getChaptersListForBook(folderBook.id).size)
        val folderTracks = dao.getTracksForBookSync(folderBook.id)
        assertEquals(2, folderTracks.size)
        assertTrue(folderTracks.all { it.isDownloaded && it.localFilePath != null })
        assertTrue(folderTracks.all { File(it.localFilePath!!).exists() })

        // Root book: single chapter, title = file name (T7 behaviour).
        assertEquals(1, dao.getChaptersListForBook(rootBook.id).size)
        assertEquals(1, dao.getChaptersListForBook(historyBook.id).size)
    }

    @Test
    fun `importAudioEntries sorts folder chapters by natural file name order`() = runBlocking {
        val mods = modules()

        mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("track10.mp3", "Сага") { ByteArrayInputStream(ByteArray(10)) },
                LocalAudioEntry("track2.mp3", "Сага") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("track1.mp3", "Сага") { ByteArrayInputStream(ByteArray(9)) }
            )
        )

        val book = dao.getAllAudiobooks().first().first { it.title == "Сага" }
        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals("track1", chapters[0].title)
        assertEquals("track2", chapters[1].title)
        assertEquals("track10", chapters[2].title)
        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterIndex })
    }

    @Test
    fun `importAudioEntries skips unreadable files without crashing and skips empty folders`() = runBlocking {
        val mods = modules()

        val result = mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("broken.mp3", "Поламана") { throw java.io.IOException("no access") },
                LocalAudioEntry("good.mp3", "Поламана") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("empty.mp3", "Пуста") { throw java.io.IOException("no access") }
            )
        )

        assertEquals(1, result.booksImported)
        assertEquals(1, result.filesImported)
        assertEquals(2, result.skippedFiles)

        val books = dao.getAllAudiobooks().first()
        // Only the folder with a readable file produced a book; the all-broken
        // folder produced none.
        assertEquals(listOf("Поламана"), books.map { it.title })
    }

    @Test
    fun `importAudioEntries keeps same-named folders from different branches separate`() = runBlocking {
        val mods = modules()

        mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "SeriesA/Кобзар") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("01.mp3", "SeriesB/Кобзар") { ByteArrayInputStream(ByteArray(9)) }
            )
        )

        val books = dao.getAllAudiobooks().first()
        // Two distinct books despite the identical folder names — grouping is
        // by the full relative path, not the bare folder name. Each holds only
        // its own chapter.
        assertEquals(2, books.size)
        assertEquals(2, books.sumOf { dao.getChaptersListForBook(it.id).size })
    }

    @Test
    fun `importAudioEntries preserves the original file extension`() = runBlocking {
        val mods = modules()

        mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("Розділ.ogg", "Книга") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("Глава.m4a", "Книга") { ByteArrayInputStream(ByteArray(9)) }
            )
        )

        val book = dao.getAllAudiobooks().first().first { it.title == "Книга" }
        // ADR-0007: the copied files live on the TRACK rows.
        val paths = dao.getTracksForBookSync(book.id).mapNotNull { it.localFilePath }
        assertTrue(paths.any { it.endsWith(".ogg") })
        assertTrue(paths.any { it.endsWith(".m4a") })
    }

    // ---------------------------------------------------------------------
    // wayfinder #29: smart import — plan (preview) then apply
    // ---------------------------------------------------------------------

    @Test
    fun `applyImportPlan without edits behaves exactly like the direct import`() = runBlocking {
        val mods = modules()
        val entries = listOf(
            LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
            LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) },
            LocalAudioEntry("Лісова пісня.mp3", null) { ByteArrayInputStream(ByteArray(18)) }
        )

        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), entries)
        val result = mods.imports.applyImportPlan(plan, sourceTreeUri = "content://tree")

        assertEquals(2, result.booksImported)
        assertEquals(3, result.filesImported)
        assertEquals(0, result.skippedFiles)
        assertEquals(0, result.duplicateFiles)

        val books = dao.getAllAudiobooks().first()
        val folderBook = books.first { it.title == "Кобзар" }
        assertEquals(2, dao.getChaptersListForBook(folderBook.id).size)
        assertEquals(1, dao.getChaptersListForBook(books.first { it.title == "Лісова пісня" }.id).size)
        // Preview left zero trace: no corrections were persisted.
        assertEquals(0, dao.getCorrectionsForMergeKey("").size)
    }

    @Test
    fun `applyImportPlan attaches an accepted merge to the existing work`() = runBlocking {
        val mods = modules()
        // Seed the library with an existing Work whose key matches.
        val existingKey = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        val existing = com.slukhayka.audiobooks.data.db.AudiobookEntity(
            id = "b1",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "Класика",
            sourceUrl = "http://4read.org/book/1"
        ).also { it.mergeKey = existingKey }
        dao.insertAudiobooks(listOf(existing))
        // ADR-0009: the merge key lives on the Works row — seed it + the entry.
        dao.upsertWork(
            com.slukhayka.audiobooks.data.db.WorkEntity(
                id = existingKey, mergeKey = existingKey, title = "Кобзар",
                author = "Тарас Шевченко", addedAt = 0L
            )
        )
        dao.upsertLibraryEntry(
            id = "b1", workId = existingKey, isFavorite = false, createdAt = 0L, downloadProgress = 0f
        )

        val entries = listOf(LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) })
        val plan = ImportPlanner.buildPlan(
            SourceRef.Folder("content://tree"),
            entries,
            existingWorks = listOf(ImportPlanner.ExistingWork(id = "b1", title = "Кобзар", mergeKey = existingKey))
        )
        val accepted = ImportPlanner.acceptMerge(plan, plan.books.first().id)
        val result = mods.imports.applyImportPlan(accepted, sourceTreeUri = "content://tree")

        // No new card — the chapter joined the existing Work.
        assertEquals(0, result.booksImported)
        assertEquals(1, result.filesImported)
        assertEquals(1, dao.getChaptersListForBook("b1").size)
        assertTrue(
            "a local source must attach to the existing Work",
            dao.getSourcesForBookSync("b1").any { it.type == "local" }
        )
    }

    @Test
    fun `applyImportPlan persists the plan's corrections as remembered memory`() = runBlocking {
        val mods = modules()
        // Seed a same-title Work so the planned book carries a T2 suggestion
        // that can be rejected into a NEVER_MATCH memory.
        val existingKey = MergeKey.keyFor("Книга", "Хтось")
        dao.insertAudiobooks(
            listOf(
                com.slukhayka.audiobooks.data.db.AudiobookEntity(
                    id = "b1", title = "Книга", author = "Хтось", narrator = "",
                    description = "", coverDrawableRes = 0, genre = "", sourceUrl = ""
                ).also { it.mergeKey = existingKey }
            )
        )
        // ADR-0009: the merge key lives on the Works row — seed it + the entry.
        dao.upsertWork(
            com.slukhayka.audiobooks.data.db.WorkEntity(
                id = existingKey, mergeKey = existingKey, title = "Книга",
                author = "Хтось", addedAt = 0L
            )
        )
        dao.upsertLibraryEntry(
            id = "b1", workId = existingKey, isFavorite = false, createdAt = 0L, downloadProgress = 0f
        )
        val entries = listOf(LocalAudioEntry("01.mp3", "Книга") { ByteArrayInputStream(ByteArray(16)) })
        var plan = ImportPlanner.buildPlan(
            SourceRef.Folder("content://tree"),
            entries,
            existingWorks = listOf(ImportPlanner.ExistingWork(id = "b1", title = "Книга", mergeKey = existingKey))
        )
        plan = ImportPlanner.rejectMerge(plan, plan.books.first().id)
        plan = ImportPlanner.editBook(plan, plan.books.first().id, title = "Кобзар", author = "Тарас Шевченко")

        mods.imports.applyImportPlan(plan, sourceTreeUri = "content://tree")

        val all = dao.getCorrectionsForMergeKey("книга|локальна папка")
        val kinds = all.map { it.kind }.toSet()
        assertTrue("NEVER_MATCH must be remembered", "NEVER_MATCH" in kinds)
        assertTrue("FIELD edits must be remembered", "FIELD" in kinds)
    }

    // ---------------------------------------------------------------------
    // wayfinder #42: re-scan of a previously imported folder (hash diff)
    // ---------------------------------------------------------------------

    @Test
    fun `rescanAudioEntries adds newly added files to the known folder book`() = runBlocking {
        val mods = modules()
        mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) }
            ),
            sourceTreeUri = "content://tree/books"
        )

        // The user drops 03.mp3 into the same folder, then re-scans.
        val report = mods.imports.rescanAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) },
                LocalAudioEntry("03.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(18)) }
            ),
            treeUri = "content://tree/books"
        )

        assertEquals(1, report.newChapters)
        assertEquals(0, report.missingFiles)
        assertEquals(0, report.newBooks)

        val book = dao.getAllAudiobooks().first().first { it.title == "Кобзар" }
        assertEquals(3, dao.getChaptersListForBook(book.id).size)
        // ADR-0007: the rebuilt local copies live on the TRACK rows.
        val tracks = dao.getTracksForBookSync(book.id)
        assertEquals(3, tracks.size)
        assertTrue(tracks.all { it.isDownloaded && it.localFilePath != null })
        assertTrue(tracks.all { File(it.localFilePath!!).exists() })
    }

    @Test
    fun `rescanAudioEntries reports files gone from the tree as missing without deleting anything`() = runBlocking {
        val mods = modules()
        mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) }
            ),
            sourceTreeUri = "content://tree/books"
        )
        val book = dao.getAllAudiobooks().first().first { it.title == "Кобзар" }
        val storedChapterIds = dao.getChaptersListForBook(book.id).map { it.id }

        // 01.mp3 was deleted on the device; the folder still has 02.mp3.
        val report = mods.imports.rescanAudioEntries(
            listOf(LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) }),
            treeUri = "content://tree/books"
        )

        assertEquals(1, report.missingFiles)
        // wayfinder #59: the library entry and its private copy survive.
        val after = dao.getAllAudiobooks().first()
        assertEquals(1, after.size)
        assertEquals(2, dao.getChaptersListForBook(book.id).size)
        assertEquals(storedChapterIds, dao.getChaptersListForBook(book.id).map { it.id })
    }

    @Test
    fun `rescanAudioEntries reports a renamed file as moved and skips bytes already in the library`() = runBlocking {
        val mods = modules()
        mods.imports.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) }
            ),
            sourceTreeUri = "content://tree/books"
        )

        // Same bytes as 02.mp3 but under a new name — moved, not new.
        val report = mods.imports.rescanAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("глава-2.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(17)) }
            ),
            treeUri = "content://tree/books"
        )

        assertEquals(1, report.movedFiles)
        assertEquals(0, report.newChapters)
        assertEquals(2, dao.getChaptersListForBook(dao.getAllAudiobooks().first().first { it.title == "Кобзар" }.id).size)
    }

    @Test
    fun `rescanAudioEntries of a new tree imports root files as new single-chapter books`() = runBlocking {
        val mods = modules()

        val report = mods.imports.rescanAudioEntries(
            listOf(
                LocalAudioEntry("Лісова пісня.mp3", null) { ByteArrayInputStream(ByteArray(18)) }
            ),
            treeUri = "content://tree/new"
        )

        assertEquals(1, report.newBooks)
        assertEquals(1, report.newChapters)
        val books = dao.getAllAudiobooks().first()
        assertEquals(1, books.size)
        assertEquals("Лісова пісня", books.first().title)
    }

    @Test
    fun `rescanAudioEntries never duplicates bytes already stored elsewhere in the library`() = runBlocking {
        val mods = modules()
        mods.imports.importAudioEntries(
            listOf(LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) }),
            sourceTreeUri = "content://tree/books"
        )
        val knownBookId = dao.getAllAudiobooks().first().first().id
        val knownChapters = dao.getChaptersListForBook(knownBookId).size

        // A different folder now contains a byte-identical copy of 01.mp3.
        val report = mods.imports.rescanAudioEntries(
            listOf(LocalAudioEntry("01.mp3", "Дублікати") { ByteArrayInputStream(ByteArray(16)) }),
            treeUri = "content://tree/dupes"
        )

        assertEquals(1, report.duplicateFiles)
        assertEquals(0, report.newBooks)
        // Still exactly one book, one chapter — no copy was made.
        assertEquals(1, dao.getAllAudiobooks().first().size)
        assertEquals(knownChapters, dao.getChaptersListForBook(knownBookId).size)
    }

    // ---------------------------------------------------------------------
    // wayfinder #48: content-hash dedupe on local imports
    // ---------------------------------------------------------------------

    @Test
    fun `importLocalAudioStream twice with identical bytes returns the existing book and copies once`() = runBlocking {
        val mods = modules()
        val filesDir = File(context.filesDir, "local_imports")

        val first = mods.imports.importLocalAudioStream("Книга.mp3", ByteArrayInputStream(ByteArray(32)))
        val second = mods.imports.importLocalAudioStream("Книга.mp3", ByteArrayInputStream(ByteArray(32)))

        assertEquals("duplicate import must return the same book", first.id, second.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        assertEquals(1, dao.getChaptersListForBook(first.id).size)
        assertEquals("only one copy may exist on disk", 1, filesDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `importLocalAudioStream with different bytes creates a second book`() = runBlocking {
        val mods = modules()

        mods.imports.importLocalAudioStream("Книга.mp3", ByteArrayInputStream(ByteArray(32)))
        mods.imports.importLocalAudioStream("Книга.mp3", ByteArrayInputStream(ByteArray(33)))

        assertEquals(2, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `importAudioEntries re-import of the same folder is fully deduplicated`() = runBlocking {
        val mods = modules()
        val bytes = ByteArray(16) { it.toByte() }
        val entries = listOf(
            LocalAudioEntry("01.mp3", "Сага") { ByteArrayInputStream(bytes) },
            LocalAudioEntry("02.mp3", "Сага") { ByteArrayInputStream(bytes.copyOf(17)) }
        )
        val filesDir = File(context.filesDir, "local_imports")

        val first = mods.imports.importAudioEntries(entries)
        val filesAfterFirst = filesDir.listFiles()?.size ?: 0
        val second = mods.imports.importAudioEntries(entries)

        assertEquals(1, first.booksImported)
        assertEquals(2, first.filesImported)
        assertEquals(0, first.duplicateFiles)
        assertEquals(0, second.booksImported)
        assertEquals(0, second.filesImported)
        assertEquals(2, second.duplicateFiles)
        assertEquals("no new copies may be written for duplicates", filesAfterFirst, filesDir.listFiles()?.size ?: 0)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        assertEquals(2, dao.getChaptersListForBook(dao.getAllAudiobooks().first().first().id).size)
    }

    @Test
    fun `importAudioEntries persists the chapter content hash`() = runBlocking {
        val mods = modules()
        val bytes = ByteArray(64) { it.toByte() }

        mods.imports.importAudioEntries(
            listOf(LocalAudioEntry("Розділ.mp3", "Книга") { ByteArrayInputStream(bytes) })
        )

        val book = dao.getAllAudiobooks().first().first()
        // ADR-0007: the content hash lives on the TRACK row.
        val track = dao.getTracksForBookSync(book.id).first()
        assertEquals(contentHashOf(ByteArrayInputStream(bytes)), track.contentHash)
    }

    @Test
    fun `folder import stamps the source tree uri on the book`() = runBlocking {
        val mods = modules()

        mods.imports.importAudioEntries(
            listOf(LocalAudioEntry("Розділ.mp3", "Книга") { ByteArrayInputStream(ByteArray(8)) }),
            sourceTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AAudioBooks"
        )

        val book = dao.getAllAudiobooks().first().first()
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AAudioBooks",
            book.sourceTreeUri
        )
    }

    // ---------------------------------------------------------------------
    // wayfinder #50: takedown — removeOfflineDownload purges the local copy
    // but keeps the book in the library
    // ---------------------------------------------------------------------

    @Test
    fun `removeOfflineDownload deletes files clears download state and keeps the book`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        val files = listOf(
            File(context.filesDir, "purge-1.mp3").apply { writeBytes(ByteArray(64)) },
            File(context.filesDir, "purge-2.mp3").apply { writeBytes(ByteArray(64)) }
        )
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(book.mergeKey, book.id, book.narrator)
        val sourceId = "4read-$editionId"
        dao.insertAudiobooks(listOf(book.copy(isDownloaded = true).also { it.downloadProgress = 1f }))
        dao.insertChapters(TestDataFactory.chaptersFor(book))
        dao.insertSources(
            listOf(
                com.slukhayka.audiobooks.data.db.SourceEntity(
                    id = sourceId, bookId = book.id, editionId = editionId, type = "4read", url = book.sourceUrl
                )
            )
        )
        // ADR-0007: download state lives on the TRACK rows.
        dao.insertTracks(
            TestDataFactory.chaptersFor(book).mapIndexed { index, ch ->
                com.slukhayka.audiobooks.data.db.SourceTrackEntity(
                    id = "$sourceId-tr-${index + 1}", sourceId = sourceId, trackIndex = index,
                    url = files[index % files.size].absolutePath,
                    localFilePath = files[index % files.size].absolutePath,
                    isDownloaded = true
                )
            }
        )

        mods.downloads.removeOfflineDownload(book.id)

        assertTrue("copies must be gone", files.all { !it.exists() })
        assertFalse("book must no longer be downloaded", dao.getAudiobookById(book.id)!!.isDownloaded)
        assertEquals(0f, dao.getAudiobookById(book.id)!!.downloadProgress)
        assertTrue(dao.getTracksForBookSync(book.id).all { !it.isDownloaded && it.localFilePath == null })
        assertNotNull("the book itself must survive", dao.getAudiobookById(book.id))
    }

    @Test
    fun `removeOfflineDownload clears hashes so a re-import can copy the files again`() = runBlocking {
        val mods = modules()
        val bytes = ByteArray(16) { it.toByte() }
        val entries = listOf(LocalAudioEntry("01.mp3", "Сага") { ByteArrayInputStream(bytes) })

        val imported = mods.imports.importAudioEntries(entries)
        val book = dao.getAllAudiobooks().first().first()
        assertEquals(1, imported.booksImported)
        val filesDir = File(context.filesDir, "local_imports")
        val copiesBefore = filesDir.listFiles()?.size ?: 0

        mods.downloads.removeOfflineDownload(book.id)
        assertEquals(0, filesDir.listFiles()?.size ?: 0)

        val reimport = mods.imports.importAudioEntries(entries)

        assertEquals("re-import must NOT be blocked by a stale hash", 1, reimport.booksImported)
        assertEquals(0, reimport.duplicateFiles)
        assertEquals("a fresh copy must land on disk", copiesBefore, filesDir.listFiles()?.size ?: 0)
        // The original book survives the takedown; the re-import adds a second,
        // playable copy next to it.
        assertEquals(2, dao.getAllAudiobooks().first().size)
    }

    // ---------------------------------------------------------------------
    // wayfinder #55 Q8 / stage-2 S1: durable tombstones
    // ---------------------------------------------------------------------

    @Test
    fun `deleteBook writes a durable tombstone`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(TestDataFactory.chaptersFor(book))

        mods.entries.deleteBook(book.id)

        assertTrue("deleted book id must be tombstoned", dao.getTombstoneBookIds().contains(book.id))
    }

    @Test
    fun `removeFromLibrary writes a durable tombstone too`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        dao.insertAudiobooks(listOf(book))

        mods.entries.removeFromLibrary(book.id)

        assertTrue("removed book id must be tombstoned", dao.getTombstoneBookIds().contains(book.id))
    }

    @Test
    fun `deleting a two-source Work blocks catalog re-listing from every source`() = runBlocking {
        val mods = modules()
        // One Work carried by two sources (merge-on-write, ADR-0007).
        val detail1 = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://sound-books.net/kobzar.html",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("1", "https://arch.sound-books.net/k/1.mp3"))
        )
        val detail2 = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "КОБЗАР",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://audiobook-mp3.com/kobzar",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("01", "https://cdn.audiobook-mp3.com/k/1.mp3"))
        )
        val book = mods.imports.importBookFromSource("soundbooks", detail1)
        mods.imports.importBookFromSource("audiobookmp3", detail2)
        assertEquals(2, dao.getSourcesForBookSync(book.id).size)

        // Delete the Work: tombstone written, every row of every source gone.
        mods.entries.deleteBook(book.id)
        assertTrue(dao.getTombstoneBookIds().contains(book.id))
        assertTrue(dao.getSourcesForBookSync(book.id).isEmpty())
        assertTrue(dao.getTracksForBookSync(book.id).isEmpty())
        assertTrue(dao.getChaptersListForBook(book.id).isEmpty())

        // ADR-0005: a catalog re-listing of the same poster is a no-op — the
        // Work cannot be resurrected from either source's catalogue row.
        assertNull(
            mods.catalog.upsertCatalogBook(
                CatalogBook(
                    id = book.id,
                    title = detail1.title,
                    author = detail1.author,
                    url = detail1.url,
                    coverImageUrl = null
                )
            )
        )
        assertNull(dao.getAudiobookById(book.id))
    }

    @Test
    fun `an explicit re-import clears the tombstone`() = runBlocking {
        val mods = modules()
        // A catalogue book has a STABLE id derived from its URL (4read-<slug>),
        // so a re-import of the same book lands on the same id.
        val book = TestDataFactory.dataBooks()[0].copy(
            id = "4read-kobzar",
            sourceUrl = "https://4read.org/kobzar.html"
        )
        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(
            TestDataFactory.chaptersFor(TestDataFactory.dataBooks()[0]).map { it.copy(id = "4read-kobzar-ch-${it.chapterIndex + 1}", bookId = "4read-kobzar") }
        )
        mods.entries.deleteBook(book.id)
        assertTrue(dao.getTombstoneBookIds().contains(book.id))

        // The user explicitly re-adds the book from search — the tombstone
        // must clear so the book is visible again.
        val detail = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            url = book.sourceUrl,
            chapters = listOf(
                com.slukhayka.audiobooks.data.source.SourceChapter("Розділ 1", "https://fixtures.invalid/1.mp3")
            )
        )
        mods.imports.importBookFromSource(sourceId = "4read", detail = detail)

        assertFalse("tombstone must clear on explicit import", dao.getTombstoneBookIds().contains(book.id))
    }

    @Test
    fun `a local re-import after delete creates a fresh visible book`() = runBlocking {
        val mods = modules()
        val bytes = ByteArray(16) { it.toByte() }
        mods.imports.importAudioEntries(
            listOf(LocalAudioEntry("01.mp3", "Сага") { ByteArrayInputStream(bytes) })
        )
        val first = dao.getAllAudiobooks().first().first()
        mods.entries.deleteBook(first.id)
        assertTrue(dao.getTombstoneBookIds().contains(first.id))

        // Local ids are time-stamped, so the re-import is a NEW book with a
        // fresh id — never suppressed by the old tombstone, never a duplicate.
        mods.imports.importAudioEntries(
            listOf(LocalAudioEntry("01.mp3", "Сага") { ByteArrayInputStream(bytes) })
        )
        val after = dao.getAllAudiobooks().first()
        assertEquals(1, after.size)
        assertTrue("the fresh book must be visible", after.first().id != first.id)
    }

    // ---------------------------------------------------------------------
    // ADR-0009: schema migration 14 -> 15 (the book row splits into Works and
    // Library Entries; preferredSpeed moves to the Listening State row).
    // EXPAND: library_entries created + back-filled, works gains seriesUrl
    // and one row per mergeable library book, playback_progress gains
    // preferredSpeed. CONTRACT: audiobooks is rebuilt without the fused
    // columns (mergeKey/series*/workId -> Works, isFavorite/createdAt/
    // downloadProgress -> Library Entries, preferredSpeed -> Listening State).
    // ---------------------------------------------------------------------

    @Test
    fun `migration 14 to 15 splits the book row into works and library entries`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-14-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v14 schema: two library books (one mergeable, one
                    // blank-key), a third blank-key book whose crawl Works row
                    // already exists, the domain editions + a progress row, an
                    // untouched tombstone and playback event.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL, " +
                            "downloadProgress REAL NOT NULL, totalDurationSeconds INTEGER NOT NULL, " +
                            "totalChapters INTEGER NOT NULL, rating REAL NOT NULL, " +
                            "isFavorite INTEGER NOT NULL, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, " +
                            "preferredSpeed REAL, createdAt INTEGER NOT NULL DEFAULT 0, sourceTreeUri TEXT, " +
                            "mergeKey TEXT NOT NULL DEFAULT '', workId TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, isDownloaded, downloadProgress, totalDurationSeconds, totalChapters, rating, " +
                            "isFavorite, seriesTitle, seriesUrl, seriesIndex, preferredSpeed, createdAt, mergeKey, workId) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', 'http://4read.org/kobzar', 1, 0.5, 3600, 3, 4.9, " +
                            "1, 'Цикл', 'http://4read.org/xfsearch/cikl/cykl/', 2, 1.25, 1700000000000, 'кобзар|автор|читець', 'кобзар|автор|читець'), " +
                            "('b2', 'Локальна книга', 'Локальний файл', '', '', 0, '', '', 1, 1.0, 0, 0, 0.0, " +
                            "0, NULL, NULL, NULL, NULL, 1600000000000, '', NULL), " +
                            "('b3', 'Війна і мир', 'Лев Толстой', 'Читець 2', '', 0, '', 'http://4read.org/vijna-i-myr', 0, 0.0, 0, 0, 0.0, " +
                            "0, 'Цикл 2', 'http://4read.org/xfsearch/cikl/cykl-2/', 1, NULL, 1500000000000, '', NULL)"
                    )
                    // A crawl Works row for b3 — keyed by the narrator-less
                    // title|author key the catalogue write path uses.
                    db.execSQL(
                        "CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, mergeKey TEXT NOT NULL, " +
                            "title TEXT NOT NULL, author TEXT NOT NULL, narrator TEXT NOT NULL DEFAULT '', " +
                            "seriesTitle TEXT, seriesIndex INTEGER, coverImageUrl TEXT, addedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_works_mergeKey ON works(mergeKey)")
                    db.execSQL(
                        "INSERT INTO works (id, mergeKey, title, author, narrator, seriesTitle, seriesIndex, coverImageUrl, addedAt) VALUES " +
                            "('війна і мир|лев толстой', 'війна і мир|лев толстой', 'Війна і мир', 'Лев Толстой', '', NULL, NULL, NULL, 1400000000000)"
                    )
                    db.execSQL(
                        "CREATE TABLE editions (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "language TEXT NOT NULL DEFAULT '', narrator TEXT NOT NULL DEFAULT '', " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "INSERT INTO editions (id, workId, language, narrator, totalChapters, totalDurationSeconds) VALUES " +
                            "('ed-b1', 'b1', '', 'Читець', 3, 3600)"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_progress (editionId TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "currentChapterIndex INTEGER NOT NULL, currentPositionSeconds INTEGER NOT NULL, " +
                            "lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL, lastPausedAtEpochMs INTEGER)"
                    )
                    db.execSQL(
                        "INSERT INTO playback_progress (editionId, bookId, currentChapterIndex, currentPositionSeconds, " +
                            "lastListenedAt, isCompleted) VALUES ('ed-b1', 'b1', 1, 300, 1700000001000, 0)"
                    )
                    db.execSQL(
                        "CREATE TABLE tombstones (bookId TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("INSERT INTO tombstones (bookId, deletedAt) VALUES ('b9', 1700000000000)")
                    db.execSQL(
                        "CREATE TABLE playback_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "bookId TEXT NOT NULL, sourceKey TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL DEFAULT 0, positionSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "fromPositionSeconds INTEGER, timestamp INTEGER NOT NULL, deviceId TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "INSERT INTO playback_events (bookId, sourceKey, kind, chapterIndex, positionSeconds, timestamp) " +
                            "VALUES ('b1', '', 'RESUME', 0, 0, 1000)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_14_15.migrate(db)

        // EXPAND 1 — library_entries: one row per book, workId = the pinned
        // key for mergeable books, the book id for blank-key ones, and the
        // crawl Works key for a blank-key book whose Works row exists.
        assertEquals(
            setOf("id", "workId", "isFavorite", "createdAt", "downloadProgress"),
            tableColumns(db, "library_entries").toSet()
        )
        db.query("SELECT workId, isFavorite, createdAt, downloadProgress FROM library_entries WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("кобзар|автор|читець", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(1700000000000L, cursor.getLong(2))
            assertEquals(0.5f, cursor.getFloat(3))
        }
        db.query("SELECT workId FROM library_entries WHERE id = 'b2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("b2", cursor.getString(0))
        }
        db.query("SELECT workId FROM library_entries WHERE id = 'b3'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("війна і мир|лев толстой", cursor.getString(0))
        }

        // EXPAND 2 — works gains seriesUrl; the mergeable book gets its Works
        // row with the series, and the crawl row gains the blank-key book's.
        assertTrue("seriesUrl column must exist", tableColumns(db, "works").contains("seriesUrl"))
        db.query("SELECT seriesTitle, seriesUrl, seriesIndex FROM works WHERE id = 'кобзар|автор|читець'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Цикл", cursor.getString(0))
            assertEquals("http://4read.org/xfsearch/cikl/cykl/", cursor.getString(1))
            assertEquals(2, cursor.getInt(2))
        }
        db.query("SELECT seriesUrl, seriesTitle, seriesIndex FROM works WHERE id = 'війна і мир|лев толстой'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("http://4read.org/xfsearch/cikl/cykl-2/", cursor.getString(0))
            assertEquals("Цикл 2", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }

        // EXPAND 3 — preferredSpeed moved to the Listening State row.
        assertTrue("preferredSpeed column must exist", tableColumns(db, "playback_progress").contains("preferredSpeed"))
        db.query("SELECT preferredSpeed FROM playback_progress WHERE editionId = 'ed-b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1.25f, cursor.getFloat(0))
        }

        // CONTRACT — audiobooks keeps only the per-row metadata columns.
        assertEquals(
            setOf(
                "id", "title", "author", "narrator", "description", "coverDrawableRes",
                "coverImageUrl", "genre", "sourceUrl", "isDownloaded", "totalDurationSeconds",
                "totalChapters", "rating", "sourceTreeUri"
            ),
            tableColumns(db, "audiobooks").toSet()
        )
        db.query("SELECT title, isDownloaded FROM audiobooks WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Кобзар", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        assertEquals(3, tableColumns(db, "audiobooks").let { cols -> db.query("SELECT COUNT(*) FROM audiobooks").use { it.moveToFirst(); it.getInt(0) } })

        // Untouched tables keep exactly their rows.
        db.query("SELECT COUNT(*) FROM tombstones").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM playback_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // ADR-0009: the split read/write contract — Works + Library Entry rows
    // are written alongside imports; the reads join through them; the
    // preferred speed lives on the Listening State row.
    // ---------------------------------------------------------------------

    @Test
    fun `import writes the work and library entry alongside and reads join through them`() = runBlocking {
        val mods = modules()
        val detail = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            url = "https://4read.org/kobzar.html",
            series = com.slukhayka.audiobooks.data.source.SeriesRef(name = "Цикл Кобзаря", url = "https://4read.org/xfsearch/cikl/kobzar/", position = 1),
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("01", "https://cdn.invalid/1.mp3"))
        )
        val book = mods.imports.importBookFromSource("4read", detail)

        // The Works row and the Library Entry row landed alongside.
        // ADR-0010: the Work key is bibliographic — the narrator no longer
        // participates (it differentiates Editions, not Works).
        val key = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        assertNotNull("the Work row must exist", dao.findWorkByMergeKey(key))
        assertEquals(1, dao.countLibraryEntries())
        // The JOINed read carries the series from the Work.
        val stored = dao.getAudiobookById(book.id)!!
        assertEquals("Цикл Кобзаря", stored.seriesTitle)
        assertEquals("https://4read.org/xfsearch/cikl/kobzar/", stored.seriesUrl)
        assertEquals(1, stored.seriesIndex)
        assertEquals(key, stored.mergeKey)
    }

    @Test
    fun `favorite download progress and preferred speed live on their own rows`() = runBlocking {
        val mods = modules()
        val book = TestDataFactory.dataBooks()[0]
        // Seed the book the way an import would: audiobooks + entry + edition
        // (the Listening State row needs the Edition anchor).
        dao.insertAudiobooks(listOf(book.copy(isDownloaded = true).also { it.downloadProgress = 0.5f }))
        dao.upsertLibraryEntry(
            id = book.id, workId = book.id, isFavorite = false,
            createdAt = TestDataFactory.FIXED_CLOCK_MS, downloadProgress = 0.5f
        )
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook("", book.id, book.narrator)
        dao.insertEdition(com.slukhayka.audiobooks.data.db.EditionEntity(id = editionId, workId = book.id))

        // Favourite -> Library Entry row.
        mods.entries.toggleFavorite(book.id, true)
        // Download progress -> Library Entry row; isDownloaded stays on audiobooks.
        dao.updateDownloadState(book.id, isDownloaded = false, progress = 0.75f)
        // Preferred speed -> the Listening State row (keyed by the Edition) —
        // the progress row must exist for the preference to land.
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = editionId, bookId = book.id,
                currentChapterIndex = 0, currentPositionSeconds = 10L,
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )
        mods.listening.setPreferredSpeed(book.id, 1.5f)

        val stored = dao.getAudiobookById(book.id)!!
        assertTrue("favourite read through the entry", stored.isFavorite)
        assertEquals(0.75f, stored.downloadProgress)
        assertFalse("isDownloaded stayed on audiobooks", stored.isDownloaded)
        // The JOINed read surfaces the speed from the Listening State row.
        assertEquals(1.5f, stored.preferredSpeed)
        assertEquals(
            1.5f,
            dao.getPlaybackProgressSyncByEdition(editionId)!!.preferredSpeed
        )
    }

    // ---------------------------------------------------------------------
    // ADR-0010: the Work is bibliographic — mergeKey is title|author, the
    // narrator is an Edition property (never a Work property).
    // ---------------------------------------------------------------------

    @Test
    fun `migration 15 to 16 re-keys works on title author merges duplicates and remaps editions with the narrator`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-15-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v15 schema: two mergeable library books (one
                    // single-narration, one pair of narrations of the SAME
                    // text that were two Works under the old narrator-bearing
                    // key), one blank-key local book, and edition-scoped
                    // children (chapter/source/bookmark/progress) to verify
                    // the remap.
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL, " +
                            "totalDurationSeconds INTEGER NOT NULL, totalChapters INTEGER NOT NULL, " +
                            "rating REAL NOT NULL, sourceTreeUri TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO audiobooks (id, title, author, narrator, description, coverDrawableRes, genre, " +
                            "sourceUrl, isDownloaded, totalDurationSeconds, totalChapters, rating) VALUES " +
                            "('b1', 'Кобзар', 'Автор', 'Читець', '', 0, '', 'http://4read.org/kobzar', 1, 3600, 3, 4.9), " +
                            "('b2', 'Локальна книга', 'Локальний файл', 'Локальний читець', '', 0, '', '', 1, 0, 0, 0.0), " +
                            "('b4', 'Війна і мир', 'Лев Толстой', 'Читець 2', '', 0, '', 'http://4read.org/vijna-a', 0, 0, 0, 0.0), " +
                            "('b5', 'Війна і мир', 'Лев Толстой', 'Інший читець', '', 0, '', 'http://4read.org/vijna-b', 0, 0, 0, 0.0)"
                    )
                    db.execSQL(
                        "CREATE TABLE library_entries (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, mergeKey TEXT NOT NULL, " +
                            "title TEXT NOT NULL, author TEXT NOT NULL, narrator TEXT NOT NULL DEFAULT '', " +
                            "seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, coverImageUrl TEXT, " +
                            "addedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_works_mergeKey ON works(mergeKey)")
                    db.execSQL(
                        "INSERT INTO works (id, mergeKey, title, author, narrator, seriesTitle, seriesIndex, addedAt) VALUES " +
                            "('кобзар|автор|читець', 'кобзар|автор|читець', 'Кобзар', 'Автор', 'Читець', 'Цикл', 1, 1000), " +
                            "('війна і мир|лев толстой|читець 2', 'війна і мир|лев толстой|читець 2', 'Війна і мир', 'Лев Толстой', 'Читець 2', NULL, NULL, 2000), " +
                            "('війна і мир|лев толстой|інший читець', 'війна і мир|лев толстой|інший читець', 'Війна і мир', 'Лев Толстой', 'Інший читець', NULL, NULL, 3000)"
                    )
                    db.execSQL(
                        "CREATE TABLE work_sources (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "sourceId TEXT NOT NULL, sourceUrl TEXT NOT NULL, streamOnly INTEGER NOT NULL DEFAULT 0, " +
                            "coverImageUrl TEXT, durationSeconds INTEGER, addedAt INTEGER NOT NULL, " +
                            "FOREIGN KEY(workId) REFERENCES works(id) ON DELETE CASCADE)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_work_sources_workId ON work_sources(workId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_work_sources_sourceId ON work_sources(sourceId)")
                    db.execSQL(
                        "INSERT INTO work_sources (id, workId, sourceId, sourceUrl, streamOnly, addedAt) VALUES " +
                            "('ws1', 'кобзар|автор|читець', '4read', 'http://4read.org/kobzar', 0, 1000), " +
                            "('ws2', 'війна і мир|лев толстой|читець 2', '4read', 'http://4read.org/vijna-a', 0, 2000), " +
                            "('ws3', 'війна і мир|лев толстой|інший читець', '4read', 'http://4read.org/vijna-b', 0, 3000)"
                    )
                    db.execSQL(
                        "CREATE TABLE editions (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "language TEXT NOT NULL DEFAULT '', narrator TEXT NOT NULL DEFAULT '', " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, totalDurationSeconds INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "INSERT INTO editions (id, workId, language, narrator, totalChapters, totalDurationSeconds) VALUES " +
                            "('ed-b1', 'b1', '', 'Читець', 3, 3600), " +
                            "('ed-b2', 'b2', '', 'Локальний читець', 0, 0), " +
                            "('ed-b4', 'b4', '', 'Читець 2', 0, 0), " +
                            "('ed-b5', 'b5', '', 'Інший читець', 0, 0)"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "chapterIndex INTEGER NOT NULL, title TEXT NOT NULL, durationSeconds INTEGER NOT NULL, " +
                            "editionId TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, chapterIndex, title, durationSeconds, editionId) " +
                            "VALUES ('c1', 'b1', 0, 'Розділ 1', 100, 'ed-b1')"
                    )
                    db.execSQL(
                        "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "editionId TEXT, type TEXT NOT NULL, url TEXT NOT NULL, " +
                            "streamOnly INTEGER NOT NULL DEFAULT 0, addedAt INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "INSERT INTO sources (id, bookId, editionId, type, url, streamOnly, addedAt) " +
                            "VALUES ('4read-ed-b1', 'b1', 'ed-b1', '4read', 'http://4read.org/kobzar', 0, 1000)"
                    )
                    db.execSQL(
                        "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, " +
                            "editionId TEXT, chapterIndex INTEGER NOT NULL, chapterTitle TEXT NOT NULL, " +
                            "timestampSeconds INTEGER NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "INSERT INTO bookmarks (bookId, editionId, chapterIndex, chapterTitle, timestampSeconds, note, createdAt) " +
                            "VALUES ('b1', 'ed-b1', 0, 'Розділ 1', 42, '', 1000)"
                    )
                    db.execSQL(
                        "CREATE TABLE playback_progress (editionId TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, " +
                            "currentChapterIndex INTEGER NOT NULL, currentPositionSeconds INTEGER NOT NULL, " +
                            "lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL, " +
                            "lastPausedAtEpochMs INTEGER, preferredSpeed REAL)"
                    )
                    db.execSQL(
                        "INSERT INTO playback_progress (editionId, bookId, currentChapterIndex, currentPositionSeconds, " +
                            "lastListenedAt, isCompleted, preferredSpeed) VALUES ('ed-b1', 'b1', 1, 300, 1700000001000, 0, 1.25)"
                    )
                    // Seed the Library Entry rows — the migration re-points
                    // them, so they must exist.
                    db.execSQL(
                        "INSERT INTO library_entries (id, workId, isFavorite, createdAt, downloadProgress) VALUES " +
                            "('b1', 'кобзар|автор|читець', 1, 1700000000000, 0.5), " +
                            "('b2', 'b2', 0, 1600000000000, 1.0), " +
                            "('b4', 'війна і мир|лев толстой|читець 2', 0, 1500000000000, 0.0), " +
                            "('b5', 'війна і мир|лев толстой|інший читець', 0, 1400000000000, 0.0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase
        // Simulate Room: foreign-key enforcement ON during the migration — the
        // rebuild order must be FK-safe (the new work_sources validates its
        // re-keyed workIds against works_new, which becomes `works`).
        db.execSQL("PRAGMA foreign_keys = ON")

        AudiobookDatabase.MIGRATION_15_16.migrate(db)

        // CONTRACT — works lost its narrator column.
        assertTrue("works must lose narrator", !tableColumns(db, "works").contains("narrator"))

        // The mergeable book's Work is re-keyed on title|author.
        db.query("SELECT seriesTitle, seriesIndex FROM works WHERE id = 'кобзар|автор'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Цикл", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        // The two narrations of «Війна і мир» merged into ONE Work.
        assertEquals(2, db.query("SELECT COUNT(*) FROM works").use { it.moveToFirst(); it.getInt(0) })
        db.query("SELECT COUNT(*) FROM works WHERE id = 'війна і мир|лев толстой'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        // The Library Entries and work_sources both re-pointed at the merged Work.
        db.query("SELECT COUNT(*) FROM library_entries WHERE workId = 'війна і мир|лев толстой'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM work_sources WHERE workId = 'війна і мир|лев толстой'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM work_sources WHERE workId = 'кобзар|автор'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        // The blank-key book keeps its self-anchored entry.
        db.query("SELECT workId FROM library_entries WHERE id = 'b2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("b2", cursor.getString(0))
        }

        // Edition ids now carry the narrator: the merged Work keeps TWO
        // distinct editions (one per narration — ADR-0001), and every
        // edition-scoped child was remapped.
        val edB1 = com.slukhayka.audiobooks.data.EditionId.forBook("кобзар|автор", "b1", "Читець")
        val edB4 = com.slukhayka.audiobooks.data.EditionId.forBook("війна і мир|лев толстой", "b4", "Читець 2")
        val edB5 = com.slukhayka.audiobooks.data.EditionId.forBook("війна і мир|лев толстой", "b5", "Інший читець")
        val edB2 = com.slukhayka.audiobooks.data.EditionId.forBook("", "b2", "Локальний читець")
        db.query("SELECT id FROM editions WHERE workId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB1, cursor.getString(0))
        }
        db.query("SELECT id FROM editions WHERE workId = 'b4'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB4, cursor.getString(0))
        }
        db.query("SELECT id FROM editions WHERE workId = 'b5'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB5, cursor.getString(0))
        }
        assertTrue("the two narrations keep distinct editions", edB4 != edB5)
        db.query("SELECT id FROM editions WHERE workId = 'b2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB2, cursor.getString(0))
        }
        db.query("SELECT editionId FROM chapters WHERE id = 'c1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB1, cursor.getString(0))
        }
        db.query("SELECT editionId FROM sources WHERE id = '4read-ed-b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB1, cursor.getString(0))
        }
        db.query("SELECT editionId FROM bookmarks WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB1, cursor.getString(0))
        }
        db.query("SELECT editionId, preferredSpeed FROM playback_progress WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(edB1, cursor.getString(0))
            assertEquals(1.25f, cursor.getFloat(1))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // Spec-25 (#171): v16 -> v17 adds the universe cache — the `universes`
    // table and the two nullable universe anchors on the `series` table.
    // Pure additions: a v16 database upgrades with every existing row intact.
    // ---------------------------------------------------------------------

    @Test
    fun `migration 16 to 17 adds the universes table and the series universe anchors`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-16-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v16 schema of the two tables the migration
                    // touches: `series` gains the anchors, `universes` is
                    // created. A seeded series row must survive intact.
                    db.execSQL(
                        "CREATE TABLE series (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, url TEXT)"
                    )
                    db.execSQL(
                        "INSERT INTO series (id, title, url) VALUES " +
                            "('s1', 'Епоха божевілля', 'https://4read.org/xfsearch/cikl/epoha-bozhevillja/')"
                    )
                    db.execSQL(
                        "CREATE TABLE series_members (workId TEXT NOT NULL, seriesId TEXT NOT NULL, " +
                            "position INTEGER NOT NULL, PRIMARY KEY(workId, seriesId))"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_16_17.migrate(db)

        // The universe cache exists.
        assertTrue("universes table must exist", tableExists(db, "universes"))
        // The series anchors landed and stay nullable.
        val seriesColumns = tableColumns(db, "series")
        assertTrue("universeId column must exist", seriesColumns.contains("universeId"))
        assertTrue("positionInUniverse column must exist", seriesColumns.contains("positionInUniverse"))
        // The pre-existing row survived with its data and NULL anchors.
        db.query("SELECT title, url, universeId, positionInUniverse FROM series WHERE id = 's1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Епоха божевілля", cursor.getString(0))
            assertEquals("https://4read.org/xfsearch/cikl/epoha-bozhevillja/", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // Spec-25: v17 -> v18 gives the universe cache a bounded TTL —
    // `series_members.resolvedAt` (the epoch-millis stamp of a book's
    // resolution). Pure addition: pre-existing memberships get a NULL stamp,
    // which the resolver treats as stale (one refresh on the next open).
    // ---------------------------------------------------------------------

    @Test
    fun `migration 17 to 18 adds the resolvedAt stamp to series_members`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-17-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v17 schema: series_members without the stamp.
                    db.execSQL(
                        "CREATE TABLE series_members (workId TEXT NOT NULL, seriesId TEXT NOT NULL, " +
                            "position INTEGER NOT NULL, PRIMARY KEY(workId, seriesId))"
                    )
                    db.execSQL(
                        "INSERT INTO series_members (workId, seriesId, position) VALUES ('w1', 's1', 1)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_17_18.migrate(db)

        // The stamp column exists and the pre-existing row survived with a
        // NULL stamp (unknown → stale → refreshed on the next book open).
        assertTrue("resolvedAt column must exist", tableColumns(db, "series_members").contains("resolvedAt"))
        db.query("SELECT seriesId, position, resolvedAt FROM series_members WHERE workId = 'w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("s1", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // Spec-27 (#185) — verification of the library counter and the storage
    // row (BUG-004, BUG-013). BUG-004 is verified live (Room flow re-emits
    // on any table write — the «Книги (N)» counter updates during a
    // background sync); BUG-013's data half is pinned here (the size the
    // «Пам'ять пристрою» row quotes is the real byte count of the offline
    // dir), the refresh call itself lives in MainViewModel.
    // ---------------------------------------------------------------------

    @Test
    fun `allBooks flow is live - inserting a book re-emits the counter (BUG-004)`() = runBlocking {
        val mods = modules()
        assertEquals(0, dao.getAllAudiobooks().first().size)

        val book = TestDataFactory.dataBooks()[0]
        withTimeout(5_000) {
            insertLibraryBooks(listOf(book))
            // The Room invalidation tracker re-queries the observed tables:
            // the flow the «Книги (N)» counter collects emits the grown list
            // without any screen restart — the background-sync liveness the
            // ticket asks to verify.
            val grown = mods.entries.allBooks.first { it.isNotEmpty() }
            assertEquals(listOf(book.id), grown.map { it.id })
        }
    }

    @Test
    fun `cache size reflects files written into the offline dir (BUG-013)`() = runBlocking {
        val mods = modules()
        val dir = File(context.filesDir, com.slukhayka.audiobooks.data.downloads.OfflineDownloads.OFFLINE_AUDIO_DIR)
        dir.mkdirs()
        val file = File(dir, "bug-013.mp3")
        file.writeBytes(ByteArray(2048))
        try {
            // The byte count the «Пам'ять пристрою» row quotes is the real
            // size of the offline dir — truthful after a download writes it.
            assertEquals(2048L, mods.downloads.getAudioCacheSizeBytes())
        } finally {
            file.delete()
        }
    }

    // ---------------------------------------------------------------------
    // #399: schema migration 23 -> 24 (person_bookmarks + editions.addedAt)
    // ---------------------------------------------------------------------

    @Test
    fun `migration 23 to 24 creates person_bookmarks and adds editions addedAt`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-23-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v23 schema: a book with an edition, chapters, and
                    // the facet tables (the v22->v23 migration added
                    // downloadState to library_entries).
                    db.execSQL(
                        "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                            "author TEXT NOT NULL, narrator TEXT NOT NULL, description TEXT NOT NULL, " +
                            "coverDrawableRes INTEGER NOT NULL, coverImageUrl TEXT, genre TEXT NOT NULL, " +
                            "sourceUrl TEXT NOT NULL, isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                            "totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, rating REAL NOT NULL DEFAULT 4.9, " +
                            "sourceTreeUri TEXT, mergeKey TEXT NOT NULL DEFAULT '', workId TEXT)"
                    )
                    db.execSQL(
                        "CREATE TABLE editions (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "language TEXT NOT NULL DEFAULT '', narrator TEXT NOT NULL DEFAULT '', " +
                            "totalChapters INTEGER NOT NULL DEFAULT 0, " +
                            "totalDurationSeconds INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_workId ON editions(workId)")
                    db.execSQL(
                        "CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, mergeKey TEXT NOT NULL, " +
                            "title TEXT NOT NULL, author TEXT NOT NULL, seriesTitle TEXT, " +
                            "seriesUrl TEXT, seriesIndex INTEGER, coverImageUrl TEXT, addedAt INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_works_mergeKey ON works(mergeKey)")
                    db.execSQL(
                        "CREATE TABLE library_entries (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, " +
                            "isFavorite INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, " +
                            "downloadProgress REAL NOT NULL DEFAULT 0, downloadState TEXT NOT NULL DEFAULT 'IDLE')"
                    )
                    // Insert test data: an edition with totalChapters=5
                    db.execSQL(
                        "INSERT INTO editions (id, workId, language, narrator, totalChapters, totalDurationSeconds) " +
                            "VALUES ('ed-1', 'b1', 'uk', 'Читець', 5, 3600)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_23_24.migrate(db)

        // person_bookmarks table exists with expected columns
        assertTrue(
            "person_bookmarks table must exist",
            tableExists(db, "person_bookmarks")
        )
        assertEquals(
            setOf("kind", "id", "displayName", "normalizedName", "createdAt",
                "lastSeenAt", "lastNotifiedAt", "notifyEnabled", "updatedAt"),
            tableColumns(db, "person_bookmarks").toSet()
        )
        // The table accepts a bookmark
        db.execSQL(
            "INSERT INTO person_bookmarks (kind, id, displayName, normalizedName, createdAt, " +
                "lastSeenAt, lastNotifiedAt, notifyEnabled, updatedAt) " +
                "VALUES ('AUTHOR', 'author-test', 'Test', 'test', 1000, 0, 0, 1, 1000)"
        )
        db.query("SELECT COUNT(*) FROM person_bookmarks").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        // editions.addedAt exists and was backfilled for existing rows
        assertTrue("addedAt column must exist", tableColumns(db, "editions").contains("addedAt"))
        db.query("SELECT addedAt FROM editions WHERE id = 'ed-1'").use { cursor ->
            cursor.moveToFirst()
            val addedAt = cursor.getLong(0)
            assertTrue("addedAt must be backfilled with a non-zero stamp", addedAt > 0L)
        }
        db.close()
    }

    @Test
    fun `person_bookmarks upsert is idempotent on same kind+id`() = runBlocking {
        val mods = modules()
        val bookmark1 = com.slukhayka.audiobooks.data.db.PersonBookmarkEntity(
            kind = com.slukhayka.audiobooks.data.db.PersonBookmarkKind.AUTHOR,
            id = "author-1", displayName = "Старе Ім'я",
            normalizedName = "старе ім'я", createdAt = 1000L, updatedAt = 1000L
        )
        val bookmark2 = bookmark1.copy(displayName = "Нове Ім'я", updatedAt = 2000L)
        dao.upsertPersonBookmark(bookmark1)
        dao.upsertPersonBookmark(bookmark2)
        val found = dao.getPersonBookmark(
            com.slukhayka.audiobooks.data.db.PersonBookmarkKind.AUTHOR, "author-1"
        )
        assertEquals("Нове Ім'я", found!!.displayName)
    }
}
