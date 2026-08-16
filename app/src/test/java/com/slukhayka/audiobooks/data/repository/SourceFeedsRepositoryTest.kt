package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.testing.FakeFetcher
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
 * Repository seam (spec-10 T5): the per-source «Нове з кожного джерела»
 * rows, driven by injected fake adapters — no network. Tests external
 * behaviour: one row per non-empty source, empty sources contribute no row,
 * and a failing source hides only its own row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceFeedsRepositoryTest {

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

    private class FakeAdapter(
        override val sourceId: String,
        private val feedBooks: List<SourceBook>,
        private val failFetchNew: Boolean = false,
        private val sessionBoundFeed: Boolean = false
    ) : SourceAdapter {
        // Spec-13 T4: counts fetchNew calls so the session-bound re-hydration
        // (skip TTL cache) is observable at the repository seam.
        var fetchNewCalls = 0

        override val sessionBound: Boolean get() = sessionBoundFeed

        override suspend fun search(query: String): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            fetchNewCalls += 1
            if (failFetchNew) throw java.io.IOException("fixture failure")
            return feedBooks
        }
    }

    // ADR-0002 (#138): the catalog tests construct the Source Catalog module
    // directly — no god module, no auto-sync on construction. The fetcher is
    // injectable so the cross-source rail test can serve a canned 4read
    // homepage without network.
    private fun repo(vararg adapters: SourceAdapter, fetcher: HttpFetcher? = null) =
        SourceCatalog(
            dao,
            adapters.toList(),
            LibraryImport(dao, context, adapters.toList()),
            fourReadFetcher = fetcher ?: HttpFetcher(referer = "https://4read.org/")
        )

    private fun book(sourceId: String, title: String) =
        SourceBook(title = title, author = "", url = "https://$sourceId.example/$title", sourceId = sourceId)

    @Test
    fun `refreshSourceFeeds publishes one row per non-empty source`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія"))),
            FakeAdapter("lihtar", listOf(book("lihtar", "Лісова пісня")))
        )

        val feeds = repository.refreshSourceFeeds()

        assertEquals(listOf("lihtar", "soundbooks"), feeds.map { it.sourceId }.sorted())
        val soundbooks = feeds.first { it.sourceId == "soundbooks" }
        assertEquals("Sound-Books", soundbooks.sourceName)
        assertEquals(1, soundbooks.books.size)
    }

    @Test
    fun `sources with empty feeds contribute no row`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", emptyList()),
            FakeAdapter("audiobookmp3", listOf(book("audiobookmp3", "Книга")))
        )

        val feeds = repository.refreshSourceFeeds()

        assertEquals(listOf("audiobookmp3"), feeds.map { it.sourceId })
    }

    @Test
    fun `a failing source hides only its own row`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія"))),
            FakeAdapter("lihtar", emptyList(), failFetchNew = true),
            FakeAdapter("audiobookmp3", listOf(book("audiobookmp3", "Книга")))
        )

        val feeds = repository.refreshSourceFeeds()

        // The throwing lihtar contributes nothing; the others still render.
        assertEquals(setOf("soundbooks", "audiobookmp3"), feeds.map { it.sourceId }.toSet())
        assertEquals(2, feeds.size)
    }

    @Test
    fun `4read is excluded from the feed rows - its new arrivals render elsewhere`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", listOf(book("4read", "Неостанній бій"))),
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія")))
        )

        val feeds = repository.refreshSourceFeeds()

        // 4read's «Нове» is already covered by the «Нове на 4read» rows.
        assertTrue(feeds.none { it.sourceId == "4read" })
        assertEquals(listOf("soundbooks"), feeds.map { it.sourceId })
    }

    // ---------------------------------------------------------------------
    // spec-13 T4: session-bound (WebView-pattern) sources — a missing/stale
    // session publishes the CTA row instead of dropping the source; a live
    // session publishes a normal row; every refresh re-hydrates (no TTL cache).
    // ---------------------------------------------------------------------

    @Test
    fun `a session-bound source with no live session publishes the stale CTA row`() = runBlocking {
        val repository = repo(
            FakeAdapter("sluhay", emptyList(), sessionBoundFeed = true),
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія")))
        )

        val feeds = repository.refreshSourceFeeds()

        val sluhay = feeds.first { it.sourceId == "sluhay" }
        assertTrue(sluhay.sessionBound)
        assertTrue(sluhay.books.isEmpty())
        assertEquals("Sluhay", sluhay.sourceName)
        // The other, non-session-bound source still renders its normal row.
        assertTrue(feeds.any { it.sourceId == "soundbooks" && !it.sessionBound })
    }

    @Test
    fun `a session-bound source with a live session publishes a normal row`() = runBlocking {
        val repository = repo(FakeAdapter("sluhay", listOf(book("sluhay", "Пасажир")), sessionBoundFeed = true))

        val feeds = repository.refreshSourceFeeds()

        val sluhay = feeds.single()
        assertFalse(sluhay.sessionBound)
        assertEquals(1, sluhay.books.size)
        assertEquals("Пасажир", sluhay.books.single().title)
    }

    @Test
    fun `a session-bound source re-hydrates on every refresh - no stale TTL cache`() = runBlocking {
        val sluhay = FakeAdapter("sluhay", emptyList(), sessionBoundFeed = true)
        val soundbooks = FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія")))
        val repository = repo(sluhay, soundbooks)

        repository.refreshSourceFeeds()
        repository.refreshSourceFeeds()

        // The session-bound source re-fetches each time (a fresh challenge must
        // surface immediately); the TTL-cached source is fetched only once.
        assertEquals(2, sluhay.fetchNewCalls)
        assertEquals(1, soundbooks.fetchNewCalls)
    }

    // ---------------------------------------------------------------------
    // spec-28 (#192): the cross-source «Новинки» rail — 4read's «Новинки»
    // section (served here by a fake homepage) plus every other source's new
    // feed, merged by Work with a badge per source.
    // ---------------------------------------------------------------------

    private val homepage = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7611-vkradi-mene-zaraz.html" class="poster__link"><div class="poster__title line-clamp">Вкради мене... Зараз!</div></a>
                <div class="poster__subtitle ws-nowrap">Сергій Оріанець</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp" loading="lazy" alt="x">
            </div>
        </div>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7589-neostannij-bij.html" class="poster__link"><div class="poster__title line-clamp">Неостанній бій</div></a>
                <div class="poster__subtitle ws-nowrap">Костянтин Шелест</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-05/medium/4_ks_mt7.webp" loading="lazy" alt="x">
            </div>
        </div>
    """.trimIndent()

    @Test
    fun `the new-arrivals rail merges 4read with other sources and deduplicates by Work`() = runBlocking {
        val soundbooks = FakeAdapter(
            "soundbooks",
            listOf(
                // The SAME Work as the 4read homepage poster — must collapse
                // into one rail card with both badges.
                SourceBook(
                    title = "Вкради мене... Зараз!",
                    author = "Сергій Оріанець",
                    url = "https://sound-books.net/vkrady",
                    sourceId = "soundbooks"
                ),
                SourceBook(
                    title = "Темна матерія",
                    author = "Блейк Крауч",
                    url = "https://sound-books.net/temna",
                    sourceId = "soundbooks"
                )
            )
        )
        val repository = repo(
            soundbooks,
            fetcher = FakeFetcher(responses = mapOf("https://4read.org/" to homepage))
        )

        repository.fetchCatalogSections()
        repository.refreshSourceFeeds()

        val rail = repository.newArrivals.value
        // Вкради мене... Зараз! (merged) + Неостанній бій (4read) + Темна матерія (soundbooks).
        assertEquals(3, rail.size)
        val merged = rail.first { it.title == "Вкради мене... Зараз!" }
        assertEquals("Сергій Оріанець", merged.author)
        // One badge per source, sorted by sourceId.
        assertEquals(listOf("4read", "soundbooks"), merged.sources.map { it.sourceId })
        assertEquals(1, rail.first { it.title == "Неостанній бій" }.sources.size)
        assertEquals("4read", rail.first { it.title == "Неостанній бій" }.sources.single().sourceId)
        assertEquals("soundbooks", rail.first { it.title == "Темна матерія" }.sources.single().sourceId)
    }

    @Test
    fun `the new-arrivals rail is empty when neither sections nor feeds have data`() = runBlocking {
        val repository = repo(FakeAdapter("soundbooks", emptyList()))

        repository.refreshSourceFeeds()

        assertTrue(repository.newArrivals.value.isEmpty())
    }
}
