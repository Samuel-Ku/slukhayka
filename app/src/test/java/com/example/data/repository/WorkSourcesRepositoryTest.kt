package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.AudiobookEntity
import com.example.data.db.WorkEntity
import com.example.data.db.WorkSourceEntity
import com.example.data.imports.LibraryImport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-23 T5, ADR-0007): the «Джерела» section's resolver —
 * every source carrying a Work, from the persisted `work_sources` rows
 * (merge-on-write output), with the source's stream-only marker. Post-merge
 * books list their sources; pre-merge library rows fall back to their own
 * single source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSourcesRepositoryTest {

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

    // ADR-0002 (#140): the catalog tests construct the Source Catalog module
    // directly — no god module.
    private fun catalog() = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))

    /** Inserts the book row AND its Library Entry (ADR-0009 — the entry
     *  carries the workId the reads join through). */
    private suspend fun libraryBook(
        id: String,
        title: String,
        sourceUrl: String,
        workId: String? = null
    ): AudiobookEntity {
        val book = AudiobookEntity(
            id = id,
            title = title,
            author = "Жан-Крістоф Гранже",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            coverImageUrl = null,
            genre = "Детектив",
            sourceUrl = sourceUrl,
            isDownloaded = false,
            totalDurationSeconds = 0L,
            totalChapters = 0,
            rating = 0f
        ).also { it.workId = workId }
        dao.insertAudiobooks(listOf(book))
        dao.upsertLibraryEntry(
            id = id,
            workId = workId ?: id,
            isFavorite = false,
            createdAt = 0L,
            downloadProgress = 0f
        )
        return book
    }

    @Test
    fun `book with two sources lists both with their stream-only markers`() = runBlocking {
        val catalog = catalog()
        // One Work carried by 4read (downloadable) and lihtar (stream-only).
        val work = WorkEntity(
            id = "w-merge-1",
            mergeKey = "пасажир|жанкрісторгранже",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            addedAt = 0L
        )
        dao.upsertWork(work)
        dao.upsertWorkSource(
            WorkSourceEntity(
                id = "w-merge-1|4read|1",
                workId = work.id,
                sourceId = "4read",
                sourceUrl = "https://4read.org/pasazhir.html",
                streamOnly = false,
                addedAt = 0L
            )
        )
        dao.upsertWorkSource(
            WorkSourceEntity(
                id = "w-merge-1|lihtar|1",
                workId = work.id,
                sourceId = "lihtar",
                sourceUrl = "https://lihtar.in.ua/pasazhir.html",
                streamOnly = true,
                addedAt = 0L
            )
        )
        libraryBook("lib-1", "Пасажир", "https://4read.org/pasazhir.html", workId = work.id)

        val rows = catalog.sourcesForBook("lib-1")

        assertEquals(2, rows.size)
        val fourRead = rows.first { it.sourceId == "4read" }
        val lihtar = rows.first { it.sourceId == "lihtar" }
        assertEquals("4read", fourRead.sourceName)
        assertEquals("Lihtar", lihtar.sourceName)
        assertFalse(fourRead.streamOnly)
        assertTrue(lihtar.streamOnly)
        assertEquals("https://4read.org/pasazhir.html", fourRead.url)
    }

    @Test
    fun `pre-merge library row falls back to its own single source`() = runBlocking {
        val catalog = catalog()
        // No workId / no editions yet — the row predates the merge.
        libraryBook("lib-legacy", "Стара книга", "https://4read.org/stara.html")

        val rows = catalog.sourcesForBook("lib-legacy")

        assertEquals(1, rows.size)
        assertEquals("4read", rows.single().sourceId)
        assertEquals("4read", rows.single().sourceName)
        assertFalse(rows.single().streamOnly)
    }

    @Test
    fun `stream-only source policy marks a pre-merge lihtar row`() = runBlocking {
        val catalog = catalog()
        libraryBook("lib-lihtar", "Книга", "https://lihtar.in.ua/kniga.html")

        val rows = catalog.sourcesForBook("lib-lihtar")

        assertEquals(1, rows.size)
        assertTrue(rows.single().streamOnly)
    }

    @Test
    fun `unknown book yields no sources`() = runBlocking {
        val catalog = catalog()
        assertEquals(0, catalog.sourcesForBook("missing").size)
    }
}
