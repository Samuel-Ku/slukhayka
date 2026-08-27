package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogFetchResult
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (ADR-0005): tombstone enforcement lives in the persistence
 * layer — the catalog upsert is an insert-unless-tombstoned statement and
 * returns nothing for a tombstoned Work; catalog fetches assemble their
 * published lists from what actually landed, so sections emptied by skips are
 * not published. Explicit import remains the resurrection door. In-memory
 * Room + a faked 4read fetcher — no network, same style as the schema /
 * migration Room tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TombstoneCatalogGuardTest {

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

    private fun catalog(pages: Map<String, String> = emptyMap()) = SourceCatalog(
        dao,
        emptyList(),
        LibraryImport(dao, context, emptyList()),
        fourReadFetcher = FakeFetcher(pages)
    )

    private fun catalog(fetcher: HttpFetcher) = SourceCatalog(
        dao,
        emptyList(),
        LibraryImport(dao, context, emptyList()),
        fourReadFetcher = fetcher
    )

    private fun poster(url: String, title: String, author: String) = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="$url" class="poster__link"><div class="poster__title line-clamp">$title</div></a>
                <div class="poster__subtitle ws-nowrap">$author</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-06/medium/x.webp" loading="lazy">
            </div>
        </div>
    """.trimIndent()

    private fun seriesPoster(url: String, title: String, author: String, seriesUrl: String, seriesTitle: String) = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="$url" class="poster__link"><div class="poster__title line-clamp">$title</div></a>
                <div class="poster__subtitle ws-nowrap">$author</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-06/medium/x.webp" loading="lazy">
                <div class="poster__series anim"><a href="$seriesUrl">$seriesTitle</a></div>
            </div>
        </div>
    """.trimIndent()

    private fun book(id: String, url: String, title: String, author: String) =
        CatalogBook(id = id, title = title, author = author, url = url, coverImageUrl = null)

    private val seriesUrl = "https://4read.org/xfsearch/cikl/maksym-temnyj/"

    // --- The guard at the upsert -------------------------------------------

    @Test
    fun `tombstoned catalog upsert is a no-op - nothing lands`() = runBlocking {
        val catalog = catalog()
        dao.insertTombstone(TombstoneEntity(bookId = "4read-7589-neostannij-bij"))

        val result = catalog.upsertCatalogBook(
            book(
                id = "4read-7589-neostannij-bij",
                url = "https://4read.org/7589-neostannij-bij.html",
                title = "Неостанній бій",
                author = "Костянтин Шелест"
            )
        )

        assertNull("tombstoned upsert must return nothing", result)
        assertNull("tombstoned upsert must not create a row", dao.getAudiobookById("4read-7589-neostannij-bij"))
    }

    @Test
    fun `live catalog upsert lands the row`() = runBlocking {
        val catalog = catalog()

        val result = catalog.upsertCatalogBook(
            book(
                id = "4read-7611-vkradi-mene-zaraz",
                url = "https://4read.org/7611-vkradi-mene-zaraz.html",
                title = "Вкради мене... Зараз!",
                author = "Сергій Оріанець"
            )
        )

        assertNotNull(result)
        assertNotNull(dao.getAudiobookById("4read-7611-vkradi-mene-zaraz"))
    }

    @Test
    fun `existing tombstoned row is not enriched`() = runBlocking {
        val catalog = catalog()
        // Insert a stale row (e.g. from before the guard) and tombstone it.
        catalog.upsertCatalogBook(
            book(
                id = "4read-1",
                url = "https://4read.org/1.html",
                title = "Книга",
                author = "Автор"
            )
        )
        dao.insertTombstone(TombstoneEntity(bookId = "4read-1"))

        // A re-sync that carries a real duration must NOT enrich the tombstoned row.
        val result = catalog.upsertCatalogBook(
            CatalogBook(
                id = "4read-1",
                title = "Книга",
                author = "Автор",
                url = "https://4read.org/1.html",
                coverImageUrl = null,
                totalDurationSeconds = 7_200L
            )
        )

        assertNull(result)
        assertEquals(0L, dao.getAudiobookById("4read-1")!!.totalDurationSeconds)
    }

    // --- Catalog fetches assemble what actually landed ---------------------

    @Test
    fun `blank remote page is a failed fetch rather than an empty catalogue`() = runBlocking {
        val result = catalog(mapOf(seriesUrl to " ")).fetchSeriesBooksResult(seriesUrl)

        assertTrue(result is CatalogFetchResult.Failure)
    }

    @Test
    fun `nonblank remote page with no books is an honest successful empty result`() = runBlocking {
        val result = catalog(mapOf(seriesUrl to "<html><body></body></html>"))
            .fetchSeriesBooksResult(seriesUrl)

        when (result) {
            is CatalogFetchResult.Success -> assertTrue(result.value.isEmpty())
            CatalogFetchResult.Failure -> error("Expected a successful empty parse")
        }
    }

    @Test
    fun `transport exception is a failed fetch`() = runBlocking {
        val fetcher = object : HttpFetcher() {
            override fun getText(url: String): String = throw IOException("offline")
        }

        val result = catalog(fetcher).fetchSeriesBooksResult(seriesUrl)

        assertTrue(result is CatalogFetchResult.Failure)
    }

    @Test
    fun `fetch cancellation is propagated`() {
        val fetcher = object : HttpFetcher() {
            override fun getText(url: String): String = throw CancellationException("closed")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { catalog(fetcher).fetchSeriesBooksResult(seriesUrl) }
        }
    }

    @Test
    fun `series fetch skips tombstoned works`() = runBlocking {
        val page = """
            <html><body>
                ${poster("https://4read.org/1-persha.html", "Перша книга", "Автор")}
                ${poster("https://4read.org/2-druga.html", "Друга книга", "Автор")}
            </body></html>
        """.trimIndent()
        val catalog = catalog(mapOf(seriesUrl to page))
        dao.insertTombstone(TombstoneEntity(bookId = "4read-2-druga"))

        val books = catalog.fetchSeriesBooks(seriesUrl)

        assertEquals(1, books.size)
        assertEquals("Перша книга", books.single().title)
    }

    @Test
    fun `homepage sections publish only landed books - fully skipped section is not published`() = runBlocking {
        val homepage = """
            <html><body>
                ${poster("https://4read.org/1-persha.html", "Перша книга", "Автор")}
                ${poster("https://4read.org/2-druga.html", "Друга книга", "Автор")}
                ${seriesPoster("https://4read.org/3-serijna.html", "Серійна книга", "Автор", seriesUrl, "Максим Темний")}
            </body></html>
        """.trimIndent()
        val catalog = catalog(mapOf("https://4read.org/" to homepage))
        // Tombstone ALL book posters (the series poster is also a book poster)
        // — the «Новинки» section is emptied and must not be published.
        dao.insertTombstone(TombstoneEntity(bookId = "4read-1-persha"))
        dao.insertTombstone(TombstoneEntity(bookId = "4read-2-druga"))
        dao.insertTombstone(TombstoneEntity(bookId = "4read-3-serijna"))

        val sections = catalog.fetchCatalogSections()

        // Only the «Цикли» (series) section survives; «Новинки» was skipped.
        assertEquals(1, sections.size)
        assertEquals("Цикли", sections.single().title)
        assertTrue(sections.single().books.isEmpty())
        assertEquals(1, sections.single().series.size)
    }

    @Test
    fun `homepage sections keep only the books that landed`() = runBlocking {
        val homepage = """
            <html><body>
                ${poster("https://4read.org/1-persha.html", "Перша книга", "Автор")}
                ${poster("https://4read.org/2-druga.html", "Друга книга", "Автор")}
            </body></html>
        """.trimIndent()
        val catalog = catalog(mapOf("https://4read.org/" to homepage))
        dao.insertTombstone(TombstoneEntity(bookId = "4read-2-druga"))

        val sections = catalog.fetchCatalogSections()

        assertEquals(1, sections.size)
        assertEquals("Новинки", sections.single().title)
        assertEquals(1, sections.single().books.size)
        assertEquals("Перша книга", sections.single().books.single().title)
    }

    // --- Explicit import is the resurrection door --------------------------

    private class FakeAdapter(
        override val sourceId: String,
        private val detail: SourceBookDetail
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail = detail

        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    @Test
    fun `explicit import resurrects a tombstoned work and clears the marker`() = runBlocking {
        val detail = SourceBookDetail(
            title = "Неостанній бій",
            author = "Костянтин Шелест",
            url = "https://4read.org/7589-neostannij-bij.html",
            chapters = listOf(SourceChapter("Глава 1", "https://4read.org/uploads/audio/7589/01.mp3"))
        )
        val imports = LibraryImport(dao, context, listOf(FakeAdapter("4read", detail)))
        val bookId = imports.importFromSourceUrl("4read", detail.url)!!.id

        // Explicitly delete the imported book (tombstone written) — a catalog
        // upsert of the same poster must be a no-op.
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
        assertNull(
            catalog().upsertCatalogBook(
                book(
                    id = bookId,
                    url = detail.url,
                    title = detail.title,
                    author = detail.author
                )
            )
        )

        // Re-importing explicitly resurrects it and clears the marker.
        val resurrected = imports.importFromSourceUrl("4read", detail.url)!!
        assertEquals(bookId, resurrected.id)
        assertNotNull(dao.getAudiobookById(bookId))
        assertTrue(!dao.isBookTombstoned(bookId))
    }
}
