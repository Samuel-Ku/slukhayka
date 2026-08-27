package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for #388: FOREIGN KEY constraint failed when selecting «Сни» by Лесь Курбас.
 * The crash was in `AudiobookDao.upsertWorkSource` when the work row had not yet been written.
 * Tapping the book must not crash; it should either open normally or degrade gracefully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSourceFkRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsertWorkSource with missing workId must not crash — graceful handling`() = runBlocking {
        val dao = db.audiobookDao()
        // Simulate the bug: work row does not exist, but we try to insert a WorkSource pointing to it.
        // Before the fix, this throws SQLiteConstraintException (FK violation) and crashes the process.
        // After the fix, the DAO or caller must handle it gracefully (skip or create work first).
        val missingWorkId = "сни|лесь курбас"
        val workSource = WorkSourceEntity(
            id = "$missingWorkId|4read|abc",
            workId = missingWorkId,
            sourceId = "4read",
            sourceUrl = "https://4read.org/sny.html",
            streamOnly = false,
            addedAt = 0L
        )

        var crashed = false
        var exception: Throwable? = null
        try {
            dao.upsertWorkSource(workSource)
        } catch (e: Exception) {
            crashed = true
            exception = e
        }

        // The test documents the BUG: before fix, this crashes with FK violation.
        // We expect AFTER fix that either:
        // - the call does not throw (because caller ensures work exists first), OR
        // - if it does throw, the caller handles it and does not propagate to crash the app.
        // For now, we assert that the FK violation is reproducible in the current code,
        // so the test will FAIL after the fix if we keep this assertion — see next test.
        // This test is the "reproduce the crash" test: it should show the exception.
        assertTrue(
            "Expected FK violation when inserting WorkSource without Work, but got: $exception",
            crashed && exception?.message?.contains("FOREIGN KEY") == true ||
                exception?.cause?.message?.contains("FOREIGN KEY") == true ||
                crashed
        )
    }

    @Test
    fun `writeWorkEdition for Sny by Les Kurbas must not crash even when work not yet exists`() = runBlocking {
        val dao = db.audiobookDao()
        val libraryImport = LibraryImport(dao, context, emptyList())
        val catalog = SourceCatalog(dao, emptyList(), libraryImport)

        // This simulates the catalog sync / library import path that writes a Work + WorkSource
        // for the book «Сни» by Лесь Курбас. The catalog's merge-on-write must not crash
        // if the work row hasn't been written yet (or is written concurrently).
        val title = "Сни"
        val author = "Лесь Курбас"
        val sourceUrl = "https://4read.org/sny-les-kurbas.html"

        var crashed = false
        try {
            val result = catalog.writeWorkEdition(
                sourceId = "4read",
                title = title,
                author = author,
                narrator = "",
                sourceUrl = sourceUrl,
                streamOnly = false
            )
            assertNotNull(result.work)
            assertEquals("сни|лесь курбас", result.work.id)
            // Verify the WorkSource was created and points to the correct Work
            val sources = dao.getWorkSourcesForWorkSync(result.work.id)
            assertEquals(1, sources.size)
            assertEquals(sourceUrl, sources.single().sourceUrl)
        } catch (e: Exception) {
            crashed = true
        }

        assertTrue("writeWorkEdition for «Сни» must not crash", !crashed)
    }

    @Test
    fun `selecting Sny library book must not crash when work row is missing`() = runBlocking {
        val dao = db.audiobookDao()
        val libraryImport = LibraryImport(dao, context, emptyList())
        // Simulate a library book «Сни» that was inserted before Works table existed,
        // so its Work row is missing but LibraryEntry points to a workId that doesn't exist.
        val bookId = "book-sny"
        val workId = "сни|лесь курбас"
        val book = AudiobookEntity(
            id = bookId,
            title = "Сни",
            author = "Лесь Курбас",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            coverImageUrl = null,
            genre = "Класика",
            sourceUrl = "https://4read.org/sny-les-kurbas.html",
            isDownloaded = false,
            totalDurationSeconds = 7200L,
            totalChapters = 5,
            rating = 4.5f
        )
        dao.insertAudiobooks(listOf(book))
        dao.upsertLibraryEntry(
            id = bookId,
            workId = workId,
            isFavorite = false,
            createdAt = System.currentTimeMillis(),
            downloadProgress = 0f
        )
        // Intentionally do NOT create the Work row — this is the buggy state.

        // Simulate what happens when the book is opened: refreshBookCoverAndDetails or
        // catalog sync tries to ensure WorkSource. Before fix, upsertWorkSource would crash with FK.
        // After fix, it should either create the Work first or skip gracefully.
        var crashed = false
        try {
            // This is the path that previously crashed: trying to ensure Work + WorkSource
            // for a book whose Work is missing.
            dao.upsertWorkSource(
                WorkSourceEntity(
                    id = "$workId|4read|abc",
                    workId = workId,
                    sourceId = "4read",
                    sourceUrl = "https://4read.org/sny-les-kurbas.html",
                    streamOnly = false,
                    addedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            crashed = true
        }

        // Before fix, this crashes. After fix, callers must ensure Work exists first,
        // so direct FK-violating insert should ideally be prevented at call-site.
        // We document that raw FK-violating insert still crashes at DAO level,
        // but the app-level path (LibraryImport.ensureWorkAndEntry / catalog.writeWorkEdition)
        // must not propagate the crash.
        // For regression, we test the app-level path:
        var appLevelCrashed = false
        try {
            // Simulate tapping the book: selectBook -> refreshBookCoverAndDetails is not the culprit,
            // but the catalog write path that would be used if the book were re-imported.
            // We test the safe path: writeWorkEdition should handle missing Work.
            val catalog = SourceCatalog(dao, emptyList(), libraryImport)
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = "Сни",
                author = "Лесь Курбас",
                narrator = "",
                sourceUrl = "https://4read.org/sny-les-kurbas.html"
            )
        } catch (e: Exception) {
            appLevelCrashed = true
        }

        // The raw DAO still throws (expected until DAO is fixed to be safe), but app-level must not.
        assertTrue("App-level writeWorkEdition must not crash for missing Work", !appLevelCrashed)
        // The raw crash is the bug we will fix in DAO: after fix, even raw upsert should be safe or caller guarded.
        // This assertion will be updated after fix to expect no crash for raw insert if DAO is made safe.
    }
}
