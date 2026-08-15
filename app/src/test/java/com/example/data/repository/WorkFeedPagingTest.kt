package com.example.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingConfig
import androidx.paging.Pager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.AudiobookEntity
import com.example.data.db.WorkFeedRow
import com.example.data.imports.LibraryImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-23 T4): the endless merged feed pages through a
 * large synthetic catalogue without gaps or duplicates, and the source /
 * genre / sort filters compose with paging. Pages are pulled directly from
 * the PagingSource (deterministic — no flow-collection timing), which is
 * exactly what Pager does over these sources: `LoadParams.Refresh(key =
 * offset)` returns a page whose `nextKey` is the next offset, looped until
 * exhaustion. Dedup is inherited from merge-on-write — the feed never
 * re-implements it at read time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkFeedPagingTest {

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

    /** Pulls every page of [source] via Refresh-key loops, mimicking Pager. */
    private suspend fun collectAll(source: PagingSource<Int, WorkFeedRow>): List<WorkFeedRow> {
        val rows = mutableListOf<WorkFeedRow>()
        var key: Int? = null
        do {
            val result = source.load(
                PagingSource.LoadParams.Refresh(key = key, loadSize = 25, placeholdersEnabled = false)
            )
            assertTrue("expected a page, got $result", result is PagingSource.LoadResult.Page)
            val page = result as PagingSource.LoadResult.Page
            rows += page.data
            key = page.nextKey
        } while (key != null)
        return rows
    }

    @Test
    fun `feed pages through a large synthetic catalogue without gaps or duplicates`() = runBlocking {
        val catalog = catalog()
        val count = 500
        for (i in 0 until count) {
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = "Книга $i",
                author = "Автор ${i % 50}",
                narrator = "",
                sourceUrl = "https://4read.org/book-$i.html"
            )
        }
        assertEquals(count, dao.countWorks())

        val rows = collectAll(dao.pagedWorksFeedRecent(null, null))

        // Every synthetic book present exactly once — no gaps, no duplicates.
        assertEquals(count, rows.size)
        assertEquals(count, rows.map { it.workId }.toSet().size)
        assertEquals(count, rows.map { it.title }.toSet().size)
    }

    @Test
    fun `source filter returns only Works carried by that source and composes with paging`() = runBlocking {
        val catalog = catalog()
        val fourRead = 100
        val sluhay = 100
        for (i in 0 until fourRead) {
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = "4read-книга $i",
                author = "Автор $i",
                narrator = "",
                sourceUrl = "https://4read.org/b$i.html"
            )
        }
        for (i in 0 until sluhay) {
            catalog.writeWorkEdition(
                sourceId = "sluhay",
                title = "Sluhay-книга $i",
                author = "Автор $i",
                narrator = "",
                sourceUrl = "https://sluhay.com/b$i.html"
            )
        }
        assertEquals(fourRead + sluhay, dao.countWorks())

        val rows = collectAll(dao.pagedWorksFeedRecent(sourceId = "4read", genre = null))

        // Only the 4read-carried Works survive the filter — and the filter
        // composes with paging (the same keys page through the filtered set).
        assertEquals(fourRead, rows.size)
        assertTrue(rows.all { it.title.startsWith("4read-") })
    }

    @Test
    fun `genre filter returns only Works whose library row carries the genre`() = runBlocking {
        val catalog = catalog()
        for (i in 0 until 20) {
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = "Книга $i",
                author = "Автор $i",
                narrator = "",
                sourceUrl = "https://4read.org/g$i.html"
            )
        }
        // Link the first five Works into the library with the genre set — the
        // feed's genre filter joins on this (LEFT JOIN: null until linked).
        // ADR-0009: the link lives on the Library Entry row.
        val withGenre = dao.observeWorks().first().take(5)
        dao.insertAudiobooks(
            withGenre.map { work ->
                AudiobookEntity(
                    id = "lib-${work.id}",
                    title = work.title,
                    author = work.author,
                    narrator = work.narrator,
                    description = "",
                    coverDrawableRes = 0,
                    coverImageUrl = null,
                    genre = "Фантастика",
                    sourceUrl = work.mergeKey.ifBlank { "https://4read.org/lib-${work.id}.html" },
                    isDownloaded = false,
                    totalDurationSeconds = 0L,
                    totalChapters = 0,
                    rating = 0f
                )
            }
        )
        withGenre.forEach { work ->
            dao.upsertLibraryEntry(
                id = "lib-${work.id}",
                workId = work.id,
                isFavorite = false,
                createdAt = 0L,
                downloadProgress = 0f
            )
        }

        val rows = collectAll(dao.pagedWorksFeedRecent(sourceId = null, genre = "Фантастика"))

        assertEquals(5, rows.size)
        assertTrue(rows.all { it.genre == "Фантастика" })
    }

    @Test
    fun `title sort returns Works ordered by title and the module accessor serves the same feed`() = runBlocking {
        val catalog = catalog()
        val titles = listOf("Зебра", "атом", "Яблуко", "Море", "Вітер")
        titles.forEachIndexed { i, title ->
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = title,
                author = "Автор",
                narrator = "",
                sourceUrl = "https://4read.org/sort-$i.html"
            )
        }

        val rows = collectAll(catalog.pagedWorkFeedByTitle(null, null))

        assertEquals(titles.size, rows.size)
        // SQLite COLLATE NOCASE is ASCII-only: it does NOT fold Cyrillic
        // case, so uppercase Cyrillic (U+0410–U+042F) sorts before lowercase
        // (U+0430–U+044F). Pin the real ordering contract — that is exactly
        // what the app's title-sorted feed shows.
        assertEquals(
            listOf("Вітер", "Зебра", "Море", "Яблуко", "атом"),
            rows.map { it.title }
        )
        // The recent-first feed also serves the same rows (different order),
        // and the module accessor is the same PagingSource the Pager uses.
        val recent = collectAll(catalog.pagedWorkFeedRecent(null, null))
        assertEquals(titles.size, recent.map { it.workId }.toSet().size)
    }

    @Test
    fun `feed row carries the source count for the sources badge`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://4read.org/pasazhir.html"
        )
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://sluhay.com/pasazhir.html"
        )

        val rows = collectAll(dao.pagedWorksFeedRecent(null, null))

        assertEquals(1, rows.size)
        // Two sources carry one Work — the «2 джерела» badge input
        // (ADR-0007: counted over work_sources).
        assertEquals(2, rows.single().sourceCount)
    }
}
