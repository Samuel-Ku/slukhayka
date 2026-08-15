package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.imports.LibraryImport
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
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
 * Repository seam (spec-15 T1): the unified «Увесь каталог» union. Driven by
 * injected fake adapters — no network. Tests external behaviour: every
 * source's catalogue enumeration is collected, books merge into one card per
 * Work via MergeKey with a badge per carried source, 4read is excluded (its
 * catalogue is natively browsed), and the union is ephemeral — nothing lands
 * in Room until a card is tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnifiedCatalogRepositoryTest {

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

    private open class FakeAdapter(
        override val sourceId: String,
        protected val catalogBooks: List<SourceBook>,
        private val sessionBoundFeed: Boolean = false
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = sessionBoundFeed

        override suspend fun search(query: String): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()

        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = catalogBooks
    }

    // ADR-0002 (#138): the catalog tests construct the Source Catalog module
    // directly — no god module, no auto-sync on construction.
    private fun repo(vararg adapters: SourceAdapter) =
        SourceCatalog(dao, adapters.toList(), LibraryImport(dao, context, adapters.toList()))

    private fun book(title: String, author: String, sourceId: String) =
        SourceBook(title = title, author = author, url = "https://$sourceId.example/$title", sourceId = sourceId)

    @Test
    fun `union collects every source's catalogue and merges one card per Work`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", listOf(book("КОБЗАР", "Тарас Шевченко", "soundbooks"))),
            FakeAdapter("audiobookmp3", listOf(book("Кобзар", "Тарас Шевченко", "audiobookmp3"))),
            FakeAdapter("lihtar", listOf(book("Лісова пісня", "Леся Українка", "lihtar")))
        )

        val union = repository.refreshUnifiedCatalog()

        // The same Work found on two sources collapses into ONE card with both
        // badges; the third source's book stays its own card.
        assertEquals(2, union.size)
        val kobzar = union.first { it.title.equals("Кобзар", ignoreCase = true) }
        assertEquals(listOf("audiobookmp3", "soundbooks"), kobzar.sources.map { it.sourceId })
        val lisova = union.first { it.title == "Лісова пісня" }
        assertEquals(listOf("lihtar"), lisova.sources.map { it.sourceId })
    }

    @Test
    fun `4read is excluded from the union - its catalogue is natively browsed`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", listOf(book("Кобзар", "Тарас Шевченко", "4read"))),
            FakeAdapter("soundbooks", listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
        )

        val union = repository.refreshUnifiedCatalog()

        // The 4read-only book is absent (the native catalogue covers it); the
        // book carried by a non-4read source still surfaces with its badge.
        assertEquals(1, union.size)
        assertEquals(listOf("soundbooks"), union.single().sources.map { it.sourceId })
    }

    @Test
    fun `a failing source never breaks the union`() = runBlocking {
        val repository = repo(
            object : FakeAdapter("soundbooks", listOf(book("Кобзар", "Тарас Шевченко", "soundbooks"))) {
                override suspend fun fetchCatalog(limit: Int): List<SourceBook> =
                    throw RuntimeException("boom")
            },
            FakeAdapter("lihtar", listOf(book("Лісова пісня", "Леся Українка", "lihtar")))
        )

        val union = repository.refreshUnifiedCatalog()

        assertEquals(1, union.size)
        assertEquals("Лісова пісня", union.single().title)
    }

    @Test
    fun `union is ephemeral - nothing is imported into Room`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
        )

        repository.refreshUnifiedCatalog()

        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `session-bound sources re-enumerate while others stay cached`() = runBlocking {
        var sessionFetches = 0
        val sessionAdapter = object : FakeAdapter(
            "sluhay",
            listOf(book("Пасажир", "Жан-Крістоф Гранже", "sluhay")),
            sessionBoundFeed = true
        ) {
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                sessionFetches++
                return catalogBooks
            }
        }
        var cachedFetches = 0
        val cachedAdapter = object : FakeAdapter(
            "soundbooks",
            listOf(book("Кобзар", "Тарас Шевченко", "soundbooks"))
        ) {
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                cachedFetches++
                return catalogBooks
            }
        }
        val repository = repo(sessionAdapter, cachedAdapter)

        repository.refreshUnifiedCatalog()
        repository.refreshUnifiedCatalog()
        repository.refreshUnifiedCatalog()

        // The session-bound adapter re-enumerates on EVERY refresh (a fresh
        // challenge session must surface immediately); the plain adapter is
        // fetched once and served from the session cache afterwards. The union
        // stays deduped throughout.
        assertEquals(3, sessionFetches)
        assertEquals(1, cachedFetches)
        assertEquals(2, repository.refreshUnifiedCatalog().size)
    }
}
