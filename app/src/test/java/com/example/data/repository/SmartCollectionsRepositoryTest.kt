package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.SourceCatalog
import com.example.data.collections.CollectionEntry
import com.example.data.collections.CollectionList
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
 * Repository seam (spec-16 T2): the smart-collections flow — matched
 * collections recomputed from the catalog union on the SAME trigger as the
 * union itself (refreshUnifiedCatalog), empty collections absent, nothing
 * persisted to Room. Driven by injected fake adapters — no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartCollectionsRepositoryTest {

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
        private val sessionBoundFeed: Boolean = true
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = sessionBoundFeed

        override suspend fun search(query: String): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()

        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = catalogBooks
    }

    private fun repo(
        adapters: List<SourceAdapter>,
        collections: List<CollectionList>,
        liveSources: List<com.example.data.collections.LiveCollectionSource> = emptyList()
    ) = SourceCatalog(
        dao,
        adapters,
        LibraryImport(dao, context, adapters),
        collectionLists = collections,
        liveCollectionSources = liveSources
    )

    private class FakeLiveSource(
        override val sourceId: String,
        var lists: () -> List<CollectionList> = { emptyList() }
    ) : com.example.data.collections.LiveCollectionSource {
        var fetchCount = 0
        override suspend fun fetchLiveCollections(): List<CollectionList> {
            fetchCount++
            return lists()
        }
    }

    private fun book(title: String, author: String, sourceId: String) =
        SourceBook(title = title, author = author, url = "https://$sourceId.example/$title", sourceId = sourceId)

    private val nobel = CollectionList(
        id = "nobel",
        name = "Нобелівські лауреати",
        entries = listOf(CollectionEntry("Ернест Гемінґвей", "Старий і море"))
    )
    private val booker = CollectionList(
        id = "booker",
        name = "Букер",
        entries = listOf(CollectionEntry("Янн Мартел", "Життя Пі"))
    )

    @Test
    fun `collections emit matched cards after a union refresh`() = runBlocking {
        val repository = repo(
            listOf(FakeAdapter("sluhay", listOf(book("Старий і море", "Ернест Гемінґвей", "sluhay")))),
            listOf(nobel, booker)
        )

        repository.refreshUnifiedCatalog()

        val collections = repository.smartCollections.first()
        assertEquals(listOf("nobel"), collections.map { it.id })
        assertEquals(listOf("Старий і море"), collections.single().books.map { it.title })
    }

    @Test
    fun `empty collections are absent from the flow`() = runBlocking {
        val repository = repo(
            listOf(FakeAdapter("sluhay", listOf(book("Собор", "Олесь Гончар", "sluhay")))),
            listOf(nobel, booker)
        )

        repository.refreshUnifiedCatalog()

        // Neither collection matches the union — the flow emits nothing.
        assertTrue(repository.smartCollections.first().isEmpty())
    }

    @Test
    fun `collections recompute when the catalog union changes`() = runBlocking {
        var books = listOf(book("Собор", "Олесь Гончар", "sluhay"))
        val adapter = object : FakeAdapter("sluhay", books) {
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = books
        }
        val repository = repo(listOf(adapter), listOf(nobel))

        repository.refreshUnifiedCatalog()
        assertTrue(repository.smartCollections.first().isEmpty())

        // A newly enumerated book appears in its collection after the next
        // refresh — no action from the listener, nothing persisted.
        books = listOf(book("Старий і море", "Ернест Гемінґвей", "sluhay"))
        repository.refreshUnifiedCatalog()

        assertEquals(listOf("nobel"), repository.smartCollections.first().map { it.id })
        assertEquals("Старий і море", repository.smartCollections.first().single().books.single().title)
    }

    @Test
    fun `nothing is persisted to Room`() = runBlocking {
        val repository = repo(
            listOf(FakeAdapter("sluhay", listOf(book("Старий і море", "Ернест Гемінґвей", "sluhay")))),
            listOf(nobel)
        )

        repository.refreshUnifiedCatalog()
        assertEquals(1, repository.smartCollections.first().size)

        // Collections are computed, never stored.
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    // --- Spec-16 follow-up: live collections --------------------------------

    @Test
    fun `a live collection is matched into the flow like a static one`() = runBlocking {
        val live = FakeLiveSource("live-trending") {
            listOf(CollectionList(id = "live-trending", name = "Популярне зараз", entries = listOf(CollectionEntry("Ернест Гемінґвей", "Старий і море"))))
        }
        val repository = repo(
            listOf(FakeAdapter("sluhay", listOf(book("Старий і море", "Ернест Гемінґвей", "sluhay")))),
            collections = emptyList(),
            liveSources = listOf(live)
        )

        repository.refreshUnifiedCatalog()

        assertEquals(listOf("live-trending"), repository.smartCollections.first().map { it.id })
        assertEquals("Старий і море", repository.smartCollections.first().single().books.single().title)
        // The fetched list is exposed for pinning.
        assertEquals("live-trending", repository.liveCollections.first().single().id)
    }

    @Test
    fun `a failing live source contributes nothing and static collections still work`() = runBlocking {
        val live = FakeLiveSource("live-broken") { throw RuntimeException("boom") }
        val repository = repo(
            listOf(FakeAdapter("sluhay", listOf(book("Старий і море", "Ернест Гемінґвей", "sluhay")))),
            collections = listOf(nobel),
            liveSources = listOf(live)
        )

        repository.refreshUnifiedCatalog()

        // The static collection still matches; the broken live source is gone.
        assertEquals(listOf("nobel"), repository.smartCollections.first().map { it.id })
        assertTrue(repository.liveCollections.first().isEmpty())
    }

    @Test
    fun `live lists are TTL-cached across refreshes`() = runBlocking {
        val live = FakeLiveSource("live-trending") {
            listOf(CollectionList(id = "live-trending", name = "Популярне зараз", entries = listOf(CollectionEntry("Ернест Гемінґвей", "Старий і море"))))
        }
        val repository = repo(
            listOf(FakeAdapter("sluhay", listOf(book("Старий і море", "Ернест Гемінґвей", "sluhay")))),
            collections = emptyList(),
            liveSources = listOf(live)
        )

        repository.refreshUnifiedCatalog()
        repository.refreshUnifiedCatalog()
        repository.refreshUnifiedCatalog()

        // One fetch for the session; the TTL cache serves the rest.
        assertEquals(1, live.fetchCount)
        assertEquals(listOf("live-trending"), repository.smartCollections.first().map { it.id })
    }
}
