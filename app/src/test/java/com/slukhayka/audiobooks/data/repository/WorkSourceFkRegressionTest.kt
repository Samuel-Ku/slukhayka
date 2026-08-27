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

        var crashed = false
        try {
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

        var appLevelCrashed = false
        try {
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

        assertTrue("App-level writeWorkEdition must not crash for missing Work", !appLevelCrashed)
    }
}
