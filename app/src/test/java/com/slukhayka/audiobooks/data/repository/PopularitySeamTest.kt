package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.collections.CollectionEntry
import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.metadata.PopularityAssertionPolicy
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
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
 * #485 — the two write seams of the persistent popularity layer:
 *
 * 1. Every live collection refresh records provenance-bearing popularity
 *    assertions (rank signals) for its entries — keyed by the SAME
 *    normalized (title|author) rule the collections matcher and the catalog
 *    union merge on.
 * 2. A resolved source page records the source's claimed rating as a
 *    rating assertion — provenance (source, observation, time), not a
 *    replacement for the stored rating column.
 *
 * The live collection shelves themselves are unchanged (covered by
 * SmartCollectionsRepositoryTest) — this layer rides beneath them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PopularitySeamTest {

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
        protected val catalogBooks: List<SourceBook> = emptyList()
    ) : SourceAdapter {
        override val sessionBound: Boolean = true

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): com.slukhayka.audiobooks.data.source.SourceBookDetail =
            com.slukhayka.audiobooks.data.source.SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = catalogBooks
    }

    private class RatingAdapter(
        override val sourceId: String,
        private val pages: Map<String, com.slukhayka.audiobooks.data.source.SourceBookDetail>
    ) : SourceAdapter {
        override val sessionBound: Boolean = true

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): com.slukhayka.audiobooks.data.source.SourceBookDetail =
            pages[url] ?: com.slukhayka.audiobooks.data.source.SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private class FakeLiveSource(
        override val sourceId: String,
        private val lists: List<CollectionList>
    ) : com.slukhayka.audiobooks.data.collections.LiveCollectionSource {
        override suspend fun fetchLiveCollections(): List<CollectionList> = lists
    }

    private fun book(title: String, author: String, sourceId: String) =
        SourceBook(title = title, author = author, url = "https://$sourceId.example/$title", sourceId = sourceId)

    private fun catalog(liveSources: List<com.slukhayka.audiobooks.data.collections.LiveCollectionSource>) =
        SourceCatalog(
            dao,
            listOf(FakeAdapter("4read")),
            LibraryImport(dao, context, emptyList()),
            liveCollectionSources = liveSources,
            popularityAssertionStore = com.slukhayka.audiobooks.data.catalog.PopularityAssertionStore(dao)
        )

    // ------------------------------------------------------------------
    // Seam 1: live collections → rank assertions
    // ------------------------------------------------------------------

    @Test
    fun `a live collection refresh records rank assertions for its entries`() = runBlocking {
        val live = CollectionList(
            id = "soundbooks-top",
            name = "ТОП-100 sound-books",
            entries = listOf(
                CollectionEntry("Стефан Цвейг", "Шахова новела"),
                CollectionEntry("Артур Конан Дойл", "Собака Баскервілів")
            )
        )
        val catalog = catalog(listOf(FakeLiveSource("soundbooks-top", listOf(live))))

        catalog.refreshUnifiedCatalog()

        val rows = dao.popularityAssertions(PopularityAssertionEntity.KIND_RANK)
        val keys = rows.map { it.mergeKey }
        assertTrue(
            "expected both entries recorded, got $keys",
            keys.contains(PopularityAssertionPolicy.popularityMergeKey("Шахова новела", "Стефан Цвейг"))
        )
        assertTrue(keys.contains(PopularityAssertionPolicy.popularityMergeKey("Собака Баскервілів", "Артур Конан Дойл")))
        assertTrue(rows.all { it.sourceId == "soundbooks-top" })
        assertTrue(rows.all { it.observedAt > 0 })
    }

    @Test
    fun `a re-refresh replaces the row instead of accumulating`() = runBlocking {
        val live = CollectionList(
            id = "sluhayua-popular",
            name = "Популярне у sluhay",
            entries = listOf(CollectionEntry("Іван Нечуй-Левицький", "Кайдашева сім'я"))
        )
        val catalog = catalog(listOf(FakeLiveSource("sluhayua-popular", listOf(live))))

        catalog.refreshUnifiedCatalog()
        val first = dao.popularityAssertions(PopularityAssertionEntity.KIND_RANK).single()
        catalog.refreshUnifiedCatalog()
        val second = dao.popularityAssertions(PopularityAssertionEntity.KIND_RANK).single()

        assertEquals(first.id, second.id)
        assertEquals(first.mergeKey, second.mergeKey)
    }

    @Test
    fun `an entry without a usable merge key contributes nothing`() = runBlocking {
        val live = CollectionList(
            id = "x",
            name = "X",
            entries = listOf(CollectionEntry("", "")) // blank author never matches
        )
        val catalog = catalog(listOf(FakeLiveSource("x", listOf(live))))

        catalog.refreshUnifiedCatalog()

        assertTrue(dao.popularityAssertions(PopularityAssertionEntity.KIND_RANK).isEmpty())
    }

    @Test
    fun `a failing live source contributes no assertions and no collection`() = runBlocking {
        val failing = object : com.slukhayka.audiobooks.data.collections.LiveCollectionSource {
            override val sourceId: String = "broken"
            override suspend fun fetchLiveCollections(): List<CollectionList> =
                throw IllegalStateException("network down")
        }
        val catalog = catalog(listOf(failing))

        catalog.refreshUnifiedCatalog()

        assertTrue(dao.popularityAssertions(PopularityAssertionEntity.KIND_RANK).isEmpty())
        assertTrue(catalog.liveCollections.value.isEmpty())
    }

    // ------------------------------------------------------------------
    // Seam 2: resolved source page → rating assertion
    // ------------------------------------------------------------------

    @Test
    fun `a resolved page records the claimed rating with provenance`() = runBlocking {
        val detail = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Пані Боварі",
            author = "Гюстав Флобер",
            url = "https://4read.org/bovari",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("Розділ 1", "https://4read.org/bovari/1.mp3")),
            rating = 4.5
        )
        val imports = LibraryImport(
            dao,
            context,
            listOf(RatingAdapter("4read", mapOf(detail.url to detail))),
            popularityAssertionStore = com.slukhayka.audiobooks.data.catalog.PopularityAssertionStore(dao)
        )

        imports.importBookFromSource("4read", detail)

        val rows = dao.popularityAssertions(PopularityAssertionEntity.KIND_RATING)
        assertEquals(1, rows.size)
        assertEquals("4read", rows.single().sourceId)
        assertEquals("4.5", rows.single().rawValue)
        assertEquals(
            PopularityAssertionPolicy.popularityMergeKey("Пані Боварі", "Гюстав Флобер"),
            rows.single().mergeKey
        )
    }

    @Test
    fun `a page without a rating records nothing`() = runBlocking {
        val detail = com.slukhayka.audiobooks.data.source.SourceBookDetail(
            title = "Кайдашева сім'я",
            author = "Іван Нечуй-Левицький",
            url = "https://sluhay.com.ua/kaidashi",
            chapters = listOf(com.slukhayka.audiobooks.data.source.SourceChapter("Розділ 1", "https://sluhay.com.ua/kaidashi/1.mp3"))
        )
        val imports = LibraryImport(
            dao,
            context,
            listOf(RatingAdapter("4read", mapOf(detail.url to detail))),
            popularityAssertionStore = com.slukhayka.audiobooks.data.catalog.PopularityAssertionStore(dao)
        )

        imports.importBookFromSource("4read", detail)

        assertTrue(dao.popularityAssertions(PopularityAssertionEntity.KIND_RATING).isEmpty())
    }
}
