package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.facets.GenreFacetAssertion
import com.slukhayka.audiobooks.data.facets.GenreSourceFacetReplacement
import com.slukhayka.audiobooks.data.facets.LocalFacetDelta
import com.slukhayka.audiobooks.data.facets.WorkFacetDelta
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-23 T4): the endless merged feed pages through a
 * large synthetic catalogue without gaps or duplicates, and the local facet
 * sets / sort state compose with paging. Pages are pulled directly from
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

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        // Every synthetic book present exactly once — no gaps, no duplicates.
        assertEquals(count, rows.size)
        assertEquals(count, rows.map { it.workId }.toSet().size)
        assertEquals(count, rows.map { it.title }.toSet().size)
    }

    @Test
    fun `no facet filter includes Works from every Source`() = runBlocking {
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

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        assertEquals(fourRead + sluhay, rows.size)
    }

    @Test
    fun `single and multi genre filters use indexed OR and empty selection means all`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("4read", "Відьмак", "Анджей Сапковський", "", "https://4read/witcher", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("sluhay", "Пасажир", "Жан-Крістоф Гранже", "", "https://sluhay/passenger", genreTexts = listOf("Детектив"))
        catalog.writeWorkEdition("sluhay", "Без жанру", "Автор", "", "https://sluhay/unknown")

        assertEquals(listOf("Дюна"), collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction")))).map { it.title })
        assertEquals(
            setOf("Дюна", "Відьмак"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction", "fantasy")))).map { it.title }.toSet()
        )
        assertEquals(4, collectAll(catalog.pagedWorkFeedRecent()).size)
        assertTrue(collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("poetry")))).isEmpty())
    }

    @Test
    fun `facet sync under active filter keeps the filter and one card per Work`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("sluhay", "Дюна", "Френк Герберт", "", "https://sluhay/dune", genreTexts = listOf("Фентезі"))
        val filter = WorkFacetFilter(setOf("science-fiction"))

        assertEquals(listOf("Дюна"), collectAll(catalog.pagedWorkFeedRecent(filter)).map { it.title })
        val work = dao.observeWorks().first().single()
        catalog.facetWriter.apply(
            listOf(LocalFacetDelta(WorkFacetDelta(work.id, listOf(GenreFacetAssertion("Фантастика", "sync", 5)))))
        )

        val afterSync = collectAll(catalog.pagedWorkFeedRecent(filter))
        assertEquals(1, afterSync.size)
        assertEquals(work.id, afterSync.single().workId)
    }

    @Test
    fun `newer Source genre replacement removes only that Source and never duplicates a Work`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("sluhay", "Дюна", "Френк Герберт", "", "https://sluhay/dune", genreTexts = listOf("Фентезі"))
        val work = dao.observeWorks().first().single()

        catalog.facetWriter.apply(
            listOf(
                LocalFacetDelta(
                    WorkFacetDelta(
                        workId = work.id,
                        genreSourceReplacements = listOf(
                            GenreSourceFacetReplacement(
                                sourceId = "4read",
                                documentUpdatedAt = Long.MAX_VALUE,
                                assertions = emptyList()
                            )
                        )
                    )
                )
            )
        )

        assertTrue(collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction")))).isEmpty())
        val remaining = collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("fantasy"))))
        assertEquals(1, remaining.size)
        assertEquals(work.id, remaining.single().workId)
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

        val rows = collectAll(catalog.pagedWorkFeedByTitle())

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
        val recent = collectAll(catalog.pagedWorkFeedRecent())
        assertEquals(titles.size, recent.map { it.workId }.toSet().size)
    }

    @Test
    fun `feed row carries its Work's duration from the Edition`() = runBlocking {
        // Spec-24 T1: the feed card shows the full book duration, and the
        // duration is the Edition's listening total (ADR-0010) — joined for
        // Works whose library copy has an Edition; null for browse-only Works.
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
            title = "Інша книга",
            author = "Інший автор",
            narrator = "",
            sourceUrl = "https://sluhay.com/insha.html"
        )

        // Link «Пасажир» into the library and give its Edition a real total
        // — the same shape importBookFromSource produces.
        val work = dao.observeWorks().first().first { it.title == "Пасажир" }
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "lib-pasazhir",
                    title = work.title,
                    author = work.author,
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    coverImageUrl = null,
                    genre = "Детектив",
                    sourceUrl = "https://4read.org/pasazhir.html",
                    isDownloaded = false,
                    totalDurationSeconds = 60_061L,
                    totalChapters = 0,
                    rating = 0f
                )
            )
        )
        dao.upsertLibraryEntry(
            id = "lib-pasazhir",
            workId = work.id,
            isFavorite = false,
            createdAt = 0L,
            downloadProgress = 0f
        )
        dao.insertEdition(
            EditionEntity(
                id = "ed-pasazhir",
                workId = "lib-pasazhir",
                narrator = "",
                totalChapters = 0,
                totalDurationSeconds = 60_061L
            )
        )
        // A sibling rendition has its own library/Edition rows but must not
        // multiply the bibliographic Work card.
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "lib-pasazhir-2", title = work.title, author = work.author,
                    narrator = "Інший диктор", description = "", coverDrawableRes = 0,
                    genre = "Детектив", sourceUrl = "https://sluhay/pasazhir.html"
                )
            )
        )
        dao.upsertLibraryEntry("lib-pasazhir-2", work.id, false, 0L, 0f)
        dao.insertEdition(EditionEntity("ed-pasazhir-2", "lib-pasazhir-2", narrator = "Інший диктор", totalDurationSeconds = 61_000L))

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        val pasazhir = rows.first { it.title == "Пасажир" }
        val insha = rows.first { it.title == "Інша книга" }
        assertEquals(1, rows.count { it.title == "Пасажир" })
        // The linked Work carries its Edition's total; the browse-only Work
        // has no Edition yet — null, never a fabricated duration.
        assertEquals(60_061L, pasazhir.durationSeconds)
        assertNull(insha.durationSeconds)
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

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        assertEquals(1, rows.size)
        // Two sources carry one Work — the «2 джерела» badge input
        // (ADR-0007: counted over work_sources).
        assertEquals(2, rows.single().sourceCount)
    }
}
