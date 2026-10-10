package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.collective.*
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real import/catalogue and persisted block boundaries; source transport is an external fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceDetailCollectiveObservationTest {
    private lateinit var db: AudiobookDatabase
    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private val pageUrl = "https://sluhay.com.ua/100:tyhrolovy"
    private val key = "sluhayua|RECOMMENDATIONS"
    private fun detail() = SourceBookDetail("Тигролови", "Іван Багряний", url = pageUrl,
        chapters = listOf(SourceChapter("Розділ 1", "https://mp3.sluhay.com.ua/100/1.mp3")),
        related = listOf(RelatedBook("Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto")))
    private class Adapter(var detail: SourceBookDetail) : SourceAdapter {
        override val sourceId = "sluhayua"
        val requests = mutableListOf<String>()
        override suspend fun search(query: String): List<SourceBook> = error("no search")
        override suspend fun fetchBookPage(url: String): SourceBookDetail { requests += url; return detail }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
    }

    @Test
    fun `live import persists observed recommendations without fetching or materializing their links`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue("composition alone is inert", adapter.requests.isEmpty())
        val imported = imports.importFromSourceUrl("sluhayua", pageUrl)
        assertNotNull(imported)
        val visible = CollectiveOverviewBlocks(local).read(listOf("sluhayua"))
        assertEquals("one observed recommendation is visible", 1, visible.size)
        assertEquals(listOf("Місто"), visible.single().cards.map { it.title })
        assertEquals(pageUrl, local.active(key)!!.provenanceUrl)
        assertEquals(visible.single(), published.single())
        assertEquals("only the requested detail resolves", listOf(pageUrl), adapter.requests)
        assertNull("related cards are not full library imports",
            db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }

    @Test
    fun `live stream refresh observes its received related cards without resolving their links`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importFromSourceUrl("sluhayua", pageUrl)!!
        val failed = "https://mp3.sluhay.com.ua/100/1.mp3"
        val fresh = "https://mp3.sluhay.com.ua/100/fresh.mp3"
        adapter.detail = detail().copy(
            chapters = listOf(SourceChapter("Розділ 1", fresh)),
            related = listOf(RelatedBook("Сад Гетсиманський", "Іван Багряний", "https://sluhay.com.ua/103:sad"))
        )
        assertEquals(fresh, imports.refreshStreamUrl(imported.id, 0, failed))
        val block = local.active(key)!!
        assertEquals("the live refresh contributes its observed recommendations", listOf("Сад Гетсиманський"),
            block.cards.map { it.title })
        assertEquals(2L, block.version)
        assertEquals(block, published.last())
        assertEquals(2, published.size)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(listOf(pageUrl, pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Сад Гетсиманський", "Іван Багряний")))
    }

    @Test
    fun `book source profiles contribute the recommendations already received on book open`() = runBlocking {
        val adapter = Adapter(detail().copy(
            description = "Живий опис зі сторінки джерела.", rating = 4.75,
            narrator = "Володимир Мовчан", genres = listOf("Пригоди", "Історична проза"),
            visitorComments = listOf("Перша відкрита думка.", "Друга думка.")))
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importBookFromSource("sluhayua", detail(), writeBackProfile = false)
        assertTrue("generic received detail is not a live observation", adapter.requests.isEmpty())
        assertNull(local.active(key))
        val entries = com.slukhayka.audiobooks.data.entries.LibraryEntries(
            db.audiobookDao(), listOf(adapter), onSourceDetailObserved = observe)
        val profiles = entries.fetchSourceProfiles(imported.id)
        assertEquals(listOf(com.slukhayka.audiobooks.data.entries.LibraryEntries.SourceProfile(
            sourceId = "sluhayua", sourceName = "Sluhay UA", url = "https://sluhay.com.ua/100:tyhrolovy",
            description = "Живий опис зі сторінки джерела.", rating = 4.75,
            narrator = "Володимир Мовчан", genres = listOf("Пригоди", "Історична проза"),
            visitorComments = listOf("Перша відкрита думка.", "Друга думка."))), profiles)
        assertEquals("one book-open recommendation block", 1,
            CollectiveOverviewBlocks(local).read(listOf("sluhayua")).size)
        val block = local.active(key)!!
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(block, published.single())
        assertEquals(listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }


    @Test
    fun `book metadata refresh contributes recommendations without changing seeded chapters`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val seed = detail().copy(
            chapters = listOf(SourceChapter("Збережений розділ", "https://mp3.sluhay.com.ua/100/seed.mp3", 600L)),
            totalDurationSeconds = 600L, coverImageUrl = "https://sluhay.com.ua/seed-cover.webp")
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        adapter.detail = detail().copy(chapters = listOf(
            SourceChapter("Живий вступ", "https://mp3.sluhay.com.ua/100/live-1.mp3", 111L),
            SourceChapter("Жива середина", "https://mp3.sluhay.com.ua/100/live-2.mp3", 222L),
            SourceChapter("Живий фінал", "https://mp3.sluhay.com.ua/100/live-3.mp3", 333L)),
            totalDurationSeconds = null, coverImageUrl = null,
            narrator = "Олена Коваль", genres = listOf("Пригоди", "Історична проза"), rating = 4.25)
        assertTrue("generic received detail is not a live observation", adapter.requests.isEmpty())
        assertNull(local.active(key))
        val entries = com.slukhayka.audiobooks.data.entries.LibraryEntries(
            db.audiobookDao(), listOf(adapter), onSourceDetailObserved = observe)
        val beforeChapters = db.audiobookDao().getChaptersListForBook(imported.id)
        val beforeSources = db.audiobookDao().getSourcesForBookSync(imported.id)
        val beforeTracks = beforeSources.associate { it.id to db.audiobookDao().getTracksForSourceSync(it.id) }
        val beforeBook = db.audiobookDao().getAudiobookById(imported.id)!!
        assertEquals(600L, beforeBook.totalDurationSeconds)
        assertEquals("https://sluhay.com.ua/100:tyhrolovy", beforeBook.sourceUrl)
        assertTrue(beforeBook.narrator != "Олена Коваль")
        assertTrue(beforeBook.rating != 4.25f)
        assertEquals(listOf("Збережений розділ"), beforeChapters.map { it.title })
        assertEquals(listOf("sluhayua"), beforeSources.map { it.type })
        assertEquals(listOf("https://sluhay.com.ua/100:tyhrolovy"), beforeSources.map { it.url })
        assertEquals(listOf("https://mp3.sluhay.com.ua/100/seed.mp3"),
            beforeTracks.values.flatten().map { it.url })
        assertEquals(3, adapter.detail.chapters.size)
        entries.refreshBookCoverAndDetails(imported.id)
        assertEquals(beforeChapters, db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(beforeSources, db.audiobookDao().getSourcesForBookSync(imported.id))
        assertEquals(beforeTracks, beforeSources.associate { it.id to db.audiobookDao().getTracksForSourceSync(it.id) })
        val afterBook = db.audiobookDao().getAudiobookById(imported.id)!!
        assertEquals(beforeBook.sourceUrl, afterBook.sourceUrl)
        assertEquals(beforeBook.totalDurationSeconds, afterBook.totalDurationSeconds)
        assertEquals(1, afterBook.totalChapters)
        assertEquals("Олена Коваль", afterBook.narrator)
        assertEquals("Пригоди · Історична проза", afterBook.genre)
        assertEquals(4.25f, afterBook.rating)
        assertEquals(listOf(pageUrl), adapter.requests)
        assertEquals("https://sluhay.com.ua/seed-cover.webp", afterBook.coverImageUrl)
        assertEquals("one metadata-refresh recommendation block", 1,
            CollectiveOverviewBlocks(local).read(listOf("sluhayua")).size)
        val block = local.active(key)!!
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(block, published.single())
        assertEquals(listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }


    @Test
    fun `a pinned cover survives the observed metadata refresh alongside seeded tracks`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val seed = detail().copy(
            chapters = listOf(SourceChapter("Збережений розділ", "https://mp3.sluhay.com.ua/100/seed.mp3", 600L)),
            totalDurationSeconds = 600L, coverImageUrl = "https://sluhay.com.ua/seed-cover.webp")
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        adapter.detail = detail().copy(chapters = listOf(
            SourceChapter("Живий вступ", "https://mp3.sluhay.com.ua/100/live-1.mp3", 111L),
            SourceChapter("Жива середина", "https://mp3.sluhay.com.ua/100/live-2.mp3", 222L),
            SourceChapter("Живий фінал", "https://mp3.sluhay.com.ua/100/live-3.mp3", 333L)),
            totalDurationSeconds = null, coverImageUrl = null,
            narrator = "Олена Коваль", genres = listOf("Пригоди", "Історична проза"), rating = 4.25)
        assertTrue("generic received detail is not a live observation", adapter.requests.isEmpty())
        assertNull(local.active(key))
        val entries = com.slukhayka.audiobooks.data.entries.LibraryEntries(
            db.audiobookDao(), listOf(adapter), onSourceDetailObserved = observe)
        val beforeChapters = db.audiobookDao().getChaptersListForBook(imported.id)
        val beforeSources = db.audiobookDao().getSourcesForBookSync(imported.id)
        val beforeTracks = beforeSources.associate { it.id to db.audiobookDao().getTracksForSourceSync(it.id) }
        com.slukhayka.audiobooks.data.metadata.CoverOverrideStore(db.audiobookDao()).pin(
            bookId = imported.id, mergeKey = MergeKey.keyFor("Тигролови", "Іван Багряний"),
            coverUrl = "https://mine.example/listener-cover.webp", now = 100L)
        adapter.detail = adapter.detail.copy(coverImageUrl = "https://sluhay.com.ua/different-live-cover.webp")
        val beforeBook = db.audiobookDao().getAudiobookById(imported.id)!!
        assertEquals(600L, beforeBook.totalDurationSeconds)
        assertEquals("https://sluhay.com.ua/100:tyhrolovy", beforeBook.sourceUrl)
        assertTrue(beforeBook.narrator != "Олена Коваль")
        assertTrue(beforeBook.rating != 4.25f)
        assertEquals(listOf("Збережений розділ"), beforeChapters.map { it.title })
        assertEquals(listOf("sluhayua"), beforeSources.map { it.type })
        assertEquals(listOf("https://sluhay.com.ua/100:tyhrolovy"), beforeSources.map { it.url })
        assertEquals(listOf("https://mp3.sluhay.com.ua/100/seed.mp3"),
            beforeTracks.values.flatten().map { it.url })
        assertEquals(3, adapter.detail.chapters.size)
        entries.refreshBookCoverAndDetails(imported.id)
        assertEquals(beforeChapters, db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(beforeSources, db.audiobookDao().getSourcesForBookSync(imported.id))
        assertEquals(beforeTracks, beforeSources.associate { it.id to db.audiobookDao().getTracksForSourceSync(it.id) })
        val afterBook = db.audiobookDao().getAudiobookById(imported.id)!!
        assertEquals(beforeBook.sourceUrl, afterBook.sourceUrl)
        assertEquals(beforeBook.totalDurationSeconds, afterBook.totalDurationSeconds)
        assertEquals(1, afterBook.totalChapters)
        assertEquals("Олена Коваль", afterBook.narrator)
        assertEquals("Пригоди · Історична проза", afterBook.genre)
        assertEquals(4.25f, afterBook.rating)
        assertEquals(listOf(pageUrl), adapter.requests)
        assertEquals("https://mine.example/listener-cover.webp", afterBook.coverImageUrl)
        assertEquals("one metadata-refresh recommendation block", 1,
            CollectiveOverviewBlocks(local).read(listOf("sluhayua")).size)
        val block = local.active(key)!!
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(block, published.single())
        assertEquals(listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }
    @Test
    fun `duration enrichment contributes received recommendations even when the duration is absent`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importBookFromSource("sluhayua", detail(), writeBackProfile = false)
        assertTrue("generic received detail is not a live observation", adapter.requests.isEmpty())
        assertNull(local.active(key))
        adapter.detail = detail().copy(totalDurationSeconds = null)
        val adapterLookups = mutableListOf<String>()
        val enrichment = com.slukhayka.audiobooks.data.duration.DurationEnrichment(
            db.audiobookDao(), adapterFor = { sourceId -> adapterLookups += sourceId; adapter },
            onSourceDetailObserved = observe)
        val beforeDuration = db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds
        assertEquals(0, enrichment.enrichUnknownDurations(now = { 1_700_000_000_000L }))
        assertEquals(beforeDuration, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertEquals(0, enrichment.enrichUnknownDurations(now = { 1_700_000_000_001L }))
        assertEquals("throttle adds no resolve or observation", listOf(pageUrl), adapter.requests)
        assertEquals(listOf("sluhayua"), adapterLookups)
        assertEquals("one observed block despite no duration", 1,
            CollectiveOverviewBlocks(local).read(listOf("sluhayua")).size)
        val block = local.active(key)!!
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(block, published.single())
        assertEquals(listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertEquals(1, published.size)
    }

    @Test
    fun `candidate verification observes its received recommendations without importing linked books`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue("composition is inert", adapter.requests.isEmpty())
        assertNull(local.active(key))
        assertTrue(published.isEmpty())
        assertTrue(catalog.verifyIndexCandidate("sluhayua", pageUrl))
        assertEquals("one target resolve before block oracle", listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Тигролови", "Іван Багряний")))
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        val visible = CollectiveOverviewBlocks(local).read(listOf("sluhayua"))
        assertEquals("verification contributes the already received recommendations", 1, visible.size)
        assertEquals("sluhayua|RECOMMENDATIONS", visible.single().blockKey)
        assertEquals("sluhayua", visible.single().sourceId)
        assertEquals(CollectiveBlockKind.RECOMMENDATIONS, visible.single().kind)
        assertEquals("До «Тигролови»", visible.single().name)
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), visible.single().cards)
        assertEquals(pageUrl, visible.single().provenanceUrl)
        assertEquals(visible.single(), published.single())
        assertEquals(listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Тигролови", "Іван Багряний")))
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }
    @Test
    fun `duration enrichment propagates shared write cancellation after preserving the observed detail`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, received ->
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, received)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importBookFromSource("sluhayua", detail(), writeBackProfile = false)
        assertTrue("generic received detail is not live", adapter.requests.isEmpty())
        assertNull(local.active(key))
        adapter.detail = detail().copy(totalDurationSeconds = 3_600L)
        val cancelled = kotlinx.coroutines.CancellationException("shared duration write cancelled")
        val puts = mutableListOf<Triple<String, Long, com.slukhayka.audiobooks.data.metadata.DurationProvenance>>()
        val store = object : com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore by
            com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore() {
            override suspend fun putDuration(editionId: String, durationSeconds: Long,
                provenance: com.slukhayka.audiobooks.data.metadata.DurationProvenance) {
                puts += Triple(editionId, durationSeconds, provenance)
                throw cancelled
            }
        }
        val lookups = mutableListOf<String>()
        val enrichment = com.slukhayka.audiobooks.data.duration.DurationEnrichment(
            db.audiobookDao(), adapterFor = { sourceId -> lookups += sourceId; adapter },
            sharedStore = store, onSourceDetailObserved = observe)
        var actualCancellation: kotlinx.coroutines.CancellationException? = null
        var returnedNormally = false
        try {
            enrichment.enrichUnknownDurations(now = { 1_700_000_000_000L })
            returnedNormally = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            actualCancellation = e
        }
        assertEquals(listOf(pageUrl), adapter.requests)
        assertEquals(listOf("sluhayua"), lookups)
        assertEquals(3_600L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertEquals(1, puts.size)
        assertEquals(com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "sluhayua narrator"), puts.single().first)
        assertEquals(3_600L, puts.single().second)
        assertEquals("sluhayua", puts.single().third.source)
        assertEquals(1_700_000_000_000L, puts.single().third.derivedAt)
        assertEquals(com.slukhayka.audiobooks.data.metadata.DurationProvenance.METHOD_SOURCE_METADATA,
            puts.single().third.method)
        assertEquals(1, published.size)
        val block = local.active(key)!!
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(block, published.single())
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertFalse("shared write cancellation must not become a successful enrichment return", returnedNormally)
        assertNotNull(actualCancellation)
        assertEquals(cancelled.javaClass, actualCancellation!!.javaClass)
        assertEquals("shared duration write cancelled", actualCancellation!!.message)
        assertTrue("original cancellation survives coroutine exception recovery",
            generateSequence<Throwable>(actualCancellation!!) { it.cause }.take(8).any { it === cancelled })
    }
    @Test
    fun `browser direct detail is observed before the empty chapter guard without synthetic recommendations`() = runBlocking {
        val browserUrl = "https://sluhay.com/observer-fixture"
        val browserDetail = detail().copy(url = browserUrl, chapters = emptyList())
        val requests = mutableListOf<String>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhay"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchBookPage(url: String): SourceBookDetail { requests += url; return browserDetail }
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
        }
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, resolved ->
            received += sourceId to resolved
            refresh.observeExplicit(collectiveBlockKey(sourceId, CollectiveBlockKind.RECOMMENDATIONS)) {
                catalog.collectiveRelatedBlock(sourceId, resolved)
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertEquals(SourceAccessMode.BROWSER, SourceAccessPolicy.modeFor("sluhay"))
        assertFalse(SourceRegistry.isScam("sluhay"))
        assertTrue("composition is inert", requests.isEmpty())
        assertNull(imports.importBrowserSourceDirectPage("sluhay", browserUrl))
        assertEquals("one actual browser page before guard", listOf(browserUrl), requests)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertEquals("browser actual detail observed before empty chapter return",
            listOf("sluhay" to browserDetail), received)
        assertNull(local.active("sluhay|RECOMMENDATIONS"))
        assertNull(local.active(key))
        assertTrue("factory does not fabricate non-SluhayUA recommendations", published.isEmpty())
    }

    @Test
    fun `playable browser direct detail is observed once while its generic import stays nonobserving`() = runBlocking {
        val browserUrl = "https://sluhay.com/playable-observer-fixture"
        val resolved = detail().copy(url = browserUrl, narrator = "Живий голос", language = "uk",
            chapters = listOf(SourceChapter("Браузерний розділ", "https://sluhay.com/fixture-audio.mp3", 600L)),
            totalDurationSeconds = 600L)
        val requests = mutableListOf<String>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhay"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchBookPage(url: String): SourceBookDetail { requests += url; return resolved }
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
        }
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter),
            onSourceDetailObserved = { sourceId, detail -> received += sourceId to detail })
        assertEquals(SourceAccessMode.BROWSER, SourceAccessPolicy.modeFor("sluhay"))
        assertFalse(SourceRegistry.isScam("sluhay"))
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val imported = imports.importBrowserSourceDirectPage("sluhay", browserUrl)!!
        assertEquals(listOf(browserUrl), requests)
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Живий голос", "uk")
        val source = db.audiobookDao().getSourcesForBookSync(imported.id).single()
        assertEquals("sluhay-$editionId", source.id)
        assertEquals(editionId, source.editionId)
        assertEquals("sluhay", source.type)
        assertEquals(browserUrl, source.url)
        assertEquals(listOf(com.slukhayka.audiobooks.data.db.SourceTrackEntity(
            "${source.id}_tr_1", source.id, 0, "https://sluhay.com/fixture-audio.mp3")),
            db.audiobookDao().getTracksForSourceSync(source.id))
        assertEquals(listOf(com.slukhayka.audiobooks.data.db.ChapterEntity(
            "${imported.id}_ch_1", imported.id, 0, "Браузерний розділ", 600L, editionId)),
            db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(600L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertEquals("actual browser fetch contributes once; generic import contributes none",
            listOf("sluhay" to resolved), received)
    }
    @Test
    fun `unsupported source feed enrichment observes its one received detail without a linked import`() = runBlocking {
        val ownUrl = "https://sound-books.net/observer-fixture.html"
        val live = detail().copy(url = ownUrl, narrator = "Живий голос",
            coverImageUrl = "https://sound-books.net/live-cover.webp")
        val requests = mutableListOf<String>()
        var feedRequests = 0
        val adapter = object : SourceAdapter {
            override val sourceId = "soundbooks"
            override val supportsSearch = false
            override suspend fun search(query: String): List<SourceBook> = error("unsupported search must not run")
            override suspend fun fetchBookPage(url: String): SourceBookDetail { requests += url; return live }
            override suspend fun fetchNew(limit: Int): List<SourceBook> {
                feedRequests++
                return listOf(SourceBook("Тигролови", "", url = ownUrl, sourceId = "soundbooks"))
            }
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
        }
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter),
            onSourceDetailObserved = { id, resolved -> received += id to resolved })
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports,
            onSourceDetailObserved = { id, resolved -> received += id to resolved })
        assertEquals(0, feedRequests)
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        assertEquals(listOf(SourceBook("Тигролови", "Іван Багряний", narrator = "Живий голос",
            url = ownUrl, coverImageUrl = "https://sound-books.net/live-cover.webp", sourceId = "soundbooks")),
            catalog.searchSource(adapter, "Тигролови"))
        assertEquals(1, feedRequests)
        assertEquals(listOf(ownUrl), requests)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertEquals("actual feed detail observed once before metadata return", listOf("soundbooks" to live), received)
        assertEquals(CollectiveRefreshOutcome.Empty, catalog.collectiveRelatedBlock("soundbooks", live))
    }
    @Test
    fun `explicit catalogue hydration observes its one received detail before generic import`() = runBlocking {
        val base = Adapter(detail())
        val catalogLimits = mutableListOf<Int>()
        val adapter = object : SourceAdapter by base {
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                catalogLimits += limit
                return listOf(SourceBook("Тигролови", "Іван Багряний", url = pageUrl, sourceId = "sluhayua"))
            }
        }
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, resolved ->
            received += sourceId to resolved
            refresh.observeExplicit(key) { catalog.collectiveRelatedBlock(sourceId, resolved) }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(base.requests.isEmpty())
        assertTrue(catalogLimits.isEmpty())
        assertTrue(received.isEmpty())
        assertEquals(SourceCatalog.HydrationResult("sluhayua", found = 1, imported = 1, merged = 0, failed = 0),
            catalog.hydrateWebSourceCatalog("sluhayua", limit = 1))
        assertEquals(listOf(1), catalogLimits)
        assertEquals(listOf(pageUrl), base.requests)
        assertNotNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Тигролови", "Іван Багряний")))
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertEquals("one explicit hydration observation, generic import adds none", listOf("sluhayua" to detail()), received)
        val block = local.active(key)!!
        assertEquals(listOf(CollectiveBlockCard(sourceId = "sluhayua",
            sourceUrl = "https://sluhay.com.ua/102:misto", title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(block, published.single())
    }
    @Test
    fun `own source chapter materialization retains the seeded source track and edition identity`() = runBlocking {
        val adapter = Adapter(detail())
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter))
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports)
        val seed = detail().copy(chapters = emptyList(), totalDurationSeconds = 0L,
            narrator = "Збережений голос", language = "uk")
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        assertEquals("sluhayua-100:tyhrolovy", imported.id)
        assertEquals(0L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertTrue("generic seed is not a resolve", adapter.requests.isEmpty())
        assertTrue(db.audiobookDao().getChaptersListForBook(imported.id).isEmpty())
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Збережений голос", "uk")
        val beforeEdition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(editionId, beforeEdition.id)
        assertEquals(imported.id, beforeEdition.workId)
        assertEquals("Збережений голос", beforeEdition.narrator)
        assertEquals("uk", beforeEdition.language)
        val beforeSource = db.audiobookDao().getSourcesForBookSync(imported.id).single()
        assertEquals("sluhayua-$editionId", beforeSource.id)
        assertEquals("sluhayua", beforeSource.type)
        assertEquals(editionId, beforeSource.editionId)
        assertEquals(pageUrl, beforeSource.url)
        assertTrue(db.audiobookDao().getTracksForSourceSync(beforeSource.id).isEmpty())
        adapter.detail = seed.copy(chapters = listOf(
            SourceChapter("Вступ з власного джерела", "https://mp3.sluhay.com.ua/100/live-intro.mp3", 150L),
            SourceChapter("Фінал з власного джерела", "https://mp3.sluhay.com.ua/100/live-end.mp3", 300L)),
            totalDurationSeconds = 450L)
        val playable = catalog.getPlayableChapters(imported.id)
        assertEquals("one own page resolve", listOf(pageUrl), adapter.requests)
        assertEquals(2, playable.size)
        assertEquals(listOf("https://mp3.sluhay.com.ua/100/live-intro.mp3",
            "https://mp3.sluhay.com.ua/100/live-end.mp3"), playable.map { it.track?.url })
        assertEquals("playable reader must retain the actual own source identity",
            listOf("sluhayua", "sluhayua"), playable.map { it.sourceId })
        assertEquals("materialization must not create a foreign 4read source",
            listOf(beforeSource), db.audiobookDao().getSourcesForBookSync(imported.id))
        val afterEdition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(editionId, afterEdition.id)
        assertEquals(imported.id, afterEdition.workId)
        assertEquals("Збережений голос", afterEdition.narrator)
        assertEquals("uk", afterEdition.language)
        val chapters = listOf(
            com.slukhayka.audiobooks.data.db.ChapterEntity("${imported.id}_ch_1", imported.id, 0,
                "Вступ з власного джерела", 150L, editionId),
            com.slukhayka.audiobooks.data.db.ChapterEntity("${imported.id}_ch_2", imported.id, 1,
                "Фінал з власного джерела", 300L, editionId))
        val tracks = listOf(
            com.slukhayka.audiobooks.data.db.SourceTrackEntity("${beforeSource.id}_tr_1", beforeSource.id, 0,
                "https://mp3.sluhay.com.ua/100/live-intro.mp3"),
            com.slukhayka.audiobooks.data.db.SourceTrackEntity("${beforeSource.id}_tr_2", beforeSource.id, 1,
                "https://mp3.sluhay.com.ua/100/live-end.mp3"))
        assertEquals(chapters, db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(tracks, db.audiobookDao().getTracksForSourceSync(beforeSource.id))
        assertEquals(listOf(SourceCatalog.PlayableChapter(chapters[0], tracks[0], "sluhayua", pageUrl),
            SourceCatalog.PlayableChapter(chapters[1], tracks[1], "sluhayua", pageUrl)), playable)
        assertEquals(450L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertEquals(2, db.audiobookDao().getAudiobookById(imported.id)!!.totalChapters)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }
    @Test
    fun `own source chapter materialization observes its received recommendations after preserving source identity`() = runBlocking {
        val adapter = Adapter(detail())
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, resolved ->
            received += sourceId to resolved
            refresh.observeExplicit(key) { catalog.collectiveRelatedBlock(sourceId, resolved) }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val seed = detail().copy(chapters = emptyList(), totalDurationSeconds = 0L,
            narrator = "Збережений голос", language = "uk")
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        assertTrue("generic seed is not a resolve", adapter.requests.isEmpty())
        assertTrue("generic seed contributes no observation", received.isEmpty())
        assertNull(local.active(key))
        assertTrue(published.isEmpty())
        assertTrue(db.audiobookDao().getChaptersListForBook(imported.id).isEmpty())
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Збережений голос", "uk")
        val beforeEdition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(editionId, beforeEdition.id)
        assertEquals(imported.id, beforeEdition.workId)
        assertEquals("Збережений голос", beforeEdition.narrator)
        assertEquals("uk", beforeEdition.language)
        val beforeSource = db.audiobookDao().getSourcesForBookSync(imported.id).single()
        assertEquals("sluhayua-$editionId", beforeSource.id)
        assertEquals("sluhayua", beforeSource.type)
        assertEquals(editionId, beforeSource.editionId)
        assertEquals(pageUrl, beforeSource.url)
        assertTrue(db.audiobookDao().getTracksForSourceSync(beforeSource.id).isEmpty())
        adapter.detail = seed.copy(chapters = listOf(
            SourceChapter("Вступ з власного джерела", "https://mp3.sluhay.com.ua/100/live-intro.mp3", 150L),
            SourceChapter("Фінал з власного джерела", "https://mp3.sluhay.com.ua/100/live-end.mp3", 300L)),
            totalDurationSeconds = 450L)
        val playable = catalog.getPlayableChapters(imported.id)
        assertEquals("one own page resolve", listOf(pageUrl), adapter.requests)
        assertEquals("materialization must not create a foreign 4read source",
            listOf(beforeSource), db.audiobookDao().getSourcesForBookSync(imported.id))
        val afterEdition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(editionId, afterEdition.id)
        assertEquals(imported.id, afterEdition.workId)
        assertEquals("Збережений голос", afterEdition.narrator)
        assertEquals("uk", afterEdition.language)
        val chapters = listOf(
            com.slukhayka.audiobooks.data.db.ChapterEntity("${imported.id}_ch_1", imported.id, 0,
                "Вступ з власного джерела", 150L, editionId),
            com.slukhayka.audiobooks.data.db.ChapterEntity("${imported.id}_ch_2", imported.id, 1,
                "Фінал з власного джерела", 300L, editionId))
        val tracks = listOf(
            com.slukhayka.audiobooks.data.db.SourceTrackEntity("${beforeSource.id}_tr_1", beforeSource.id, 0,
                "https://mp3.sluhay.com.ua/100/live-intro.mp3"),
            com.slukhayka.audiobooks.data.db.SourceTrackEntity("${beforeSource.id}_tr_2", beforeSource.id, 1,
                "https://mp3.sluhay.com.ua/100/live-end.mp3"))
        assertEquals(chapters, db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(tracks, db.audiobookDao().getTracksForSourceSync(beforeSource.id))
        assertEquals(listOf(SourceCatalog.PlayableChapter(chapters[0], tracks[0], "sluhayua", pageUrl),
            SourceCatalog.PlayableChapter(chapters[1], tracks[1], "sluhayua", pageUrl)), playable)
        assertEquals(450L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertEquals(2, db.audiobookDao().getAudiobookById(imported.id)!!.totalChapters)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertEquals("only actual own-page detail contributes once", listOf("sluhayua" to adapter.detail), received)
        val block = local.active(key)!!
        assertEquals("sluhayua|RECOMMENDATIONS", block.blockKey)
        assertEquals("sluhayua", block.sourceId)
        assertEquals(CollectiveBlockKind.RECOMMENDATIONS, block.kind)
        assertEquals("До «Тигролови»", block.name)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(listOf(CollectiveBlockCard(sourceId = "sluhayua",
            sourceUrl = "https://sluhay.com.ua/102:misto", title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(block, published.single())
        assertEquals(listOf(block), CollectiveOverviewBlocks(local).read(listOf("sluhayua")))
        assertEquals("publication and read fetch no related page", listOf(pageUrl), adapter.requests)
    }
}
