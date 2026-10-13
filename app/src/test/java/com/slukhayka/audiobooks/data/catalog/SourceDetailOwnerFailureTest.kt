package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.collective.*
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public owner doors with real Room; source responses and optional callback failures are external fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceDetailOwnerFailureTest {
    private lateinit var db: AudiobookDatabase
    private lateinit var local: RoomCollectiveFeedBlockStore
    private lateinit var lastGood: CollectiveFeedBlock
    private val pageUrl = "https://sluhay.com.ua/100:tyhrolovy"
    private val seedStream = "https://mp3.sluhay.com.ua/100/seed.mp3"
    private val freshStream = "https://mp3.sluhay.com.ua/100/fresh.mp3"
    private val seedCover = "https://sluhay.com.ua/seed-cover.webp"
    private val liveCover = "https://sluhay.com.ua/live-cover.webp"
    private val received = mutableListOf<Pair<String, SourceBookDetail>>()
    private val adapter = Adapter()

    private fun detail() = SourceBookDetail(
        "Тигролови", "Іван Багряний", url = pageUrl,
        narrator = "Володимир Мовчан", description = "Живий опис зі сторінки джерела.",
        coverImageUrl = liveCover, genres = listOf("Пригоди"), rating = 4.25,
        totalDurationSeconds = 3_600L,
        chapters = listOf(SourceChapter("Розділ 1", freshStream)),
        related = listOf(RelatedBook("Сад Гетсиманський", "Іван Багряний", "https://sluhay.com.ua/103:sad"))
    )

    private class Adapter : SourceAdapter {
        override val sourceId = "sluhayua"
        lateinit var response: SourceBookDetail
        val requests = mutableListOf<String>()
        override suspend fun fetchBookPage(url: String): SourceBookDetail { requests += url; return response }
        override suspend fun search(query: String): List<SourceBook> = error("no linked search")
        override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
    }

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).allowMainThreadQueries().build()
        local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        adapter.response = detail()
        runBlocking {
            val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter))
            val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports)
            val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
                fetch = { error("no implicit source request") }, clock = { 1_000L })
            refresh.observeExplicit("sluhayua|RECOMMENDATIONS") {
                catalog.collectiveRelatedBlock("sluhayua", detail().copy(related = listOf(
                    RelatedBook("Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto"))))
            }
            lastGood = local.active("sluhayua|RECOMMENDATIONS")!!
            assertTrue(adapter.requests.isEmpty())
            assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        }
    }
    @After fun tearDown() = db.close()

    private fun observer(failure: Exception): suspend (String, SourceBookDetail) -> Unit = { id, value ->
        received += id to value
        throw failure
    }
    private fun imports(failure: Exception) = LibraryImport(
        db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observer(failure))

    private suspend fun seed(imports: LibraryImport): String {
        val book = imports.importBookFromSource("sluhayua", detail().copy(
            coverImageUrl = seedCover, totalDurationSeconds = 0L,
            chapters = listOf(SourceChapter("Розділ 1", seedStream))), writeBackProfile = false)
        assertTrue("generic received-detail import does not fetch", adapter.requests.isEmpty())
        assertTrue("generic import does not invoke the live callback", received.isEmpty())
        assertOwnBook(book.id, seedStream, 0L, seedCover)
        return book.id
    }

    private suspend fun assertOwnBook(id: String, stream: String, duration: Long, cover: String) {
        val entries = LibraryEntries(db.audiobookDao(), listOf(adapter))
        val book = entries.getBookSync(id)!!
        assertEquals(1, db.audiobookDao().getAllAudiobooksOnce().size)
        assertEquals("Тигролови", book.title)
        assertEquals("Іван Багряний", book.author)
        assertEquals("https://sluhay.com.ua/100:tyhrolovy", book.sourceUrl)
        assertEquals(1, book.totalChapters)
        assertEquals(duration, book.totalDurationSeconds)
        assertEquals(cover, book.coverImageUrl)
        assertEquals(listOf("Розділ 1"), entries.observeChapters(id).first().map { it.title })
        val sources = entries.getSourcesForBook(id)
        assertEquals(listOf("sluhayua"), sources.map { it.type })
        assertEquals(listOf("https://sluhay.com.ua/100:tyhrolovy"), sources.map { it.url })
        assertEquals(listOf(stream), sources.flatMap { db.audiobookDao().getTracksForSourceSync(it.id) }.map { it.url })
    }

    private suspend fun assertObservedWithoutLinkedWork(expected: SourceBookDetail) {
        assertEquals(listOf("https://sluhay.com.ua/100:tyhrolovy"), adapter.requests)
        assertEquals(listOf("sluhayua" to expected), received)
        assertSame("callback receives the actual fetched response", expected, received.single().second)
        val visible = CollectiveOverviewBlocks(local).read(listOf("sluhayua"))
        assertEquals(listOf(lastGood), visible)
        assertEquals("sluhayua|RECOMMENDATIONS", visible.single().blockKey)
        assertEquals("sluhayua", visible.single().sourceId)
        assertEquals("https://sluhay.com.ua/100:tyhrolovy", visible.single().provenanceUrl)
        assertEquals(1L, visible.single().version)
        assertEquals(listOf(CollectiveBlockCard(
            sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/102:misto",
            title = "Місто", author = "Валер’ян Підмогильний")), visible.single().cards)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Сад Гетсиманський", "Іван Багряний")))
        assertEquals(listOf("https://sluhay.com.ua/100:tyhrolovy"), adapter.requests)
    }

    private suspend fun assertCancellation(expected: CancellationException, operation: suspend () -> Unit) {
        val actual = try { operation(); null } catch (cancelled: CancellationException) { cancelled }
        assertNotNull("callback cancellation must escape the public owner door", actual)
        assertEquals(expected.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue("the original exception survives coroutine recovery",
            generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === expected })
    }

    @Test
    fun `source url import remains playable when its recommendation callback fails`() = runBlocking {
        val failure = IOException("recommendation callback unavailable")
        val book = imports(failure).importFromSourceUrl("sluhayua", pageUrl)!!
        assertOwnBook(book.id, freshStream, 3_600L, liveCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `source url callback cancellation escapes before the empty chapter guard`() = runBlocking {
        adapter.response = detail().copy(chapters = emptyList())
        val cancelled = CancellationException("source url callback cancelled")
        assertCancellation(cancelled) { imports(cancelled).importFromSourceUrl("sluhayua", pageUrl) }
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `stream healing retains the fresh own track when its recommendation callback fails`() = runBlocking {
        val imports = imports(IOException("stream recommendation unavailable"))
        val id = seed(imports)
        assertEquals("https://mp3.sluhay.com.ua/100/fresh.mp3", imports.refreshStreamUrl(id, 0, seedStream))
        assertOwnBook(id, freshStream, 0L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `stream callback cancellation escapes before the empty chapter guard and preserves its own track`() = runBlocking {
        val cancelled = CancellationException("stream callback cancelled")
        val imports = imports(cancelled)
        val id = seed(imports)
        adapter.response = detail().copy(chapters = emptyList())
        assertCancellation(cancelled) { imports.refreshStreamUrl(id, 0, seedStream) }
        assertOwnBook(id, seedStream, 0L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `book source profiles remain visible when their recommendation callback fails`() = runBlocking {
        val id = seed(imports(IOException("generic seed must not observe")))
        val entries = LibraryEntries(db.audiobookDao(), listOf(adapter),
            onSourceDetailObserved = observer(IOException("profile recommendation unavailable")))
        assertEquals(listOf(LibraryEntries.SourceProfile(
            sourceId = "sluhayua", sourceName = "Sluhay UA", url = "https://sluhay.com.ua/100:tyhrolovy",
            description = "Живий опис зі сторінки джерела.", rating = 4.25,
            narrator = "Володимир Мовчан", genres = listOf("Пригоди"), visitorComments = emptyList()
        )), entries.fetchSourceProfiles(id))
        assertOwnBook(id, seedStream, 0L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `profile callback cancellation escapes before the blank profile guard`() = runBlocking {
        val id = seed(imports(IOException("generic seed must not observe")))
        adapter.response = detail().copy(title = "", chapters = emptyList())
        val cancelled = CancellationException("profile callback cancelled")
        val entries = LibraryEntries(db.audiobookDao(), listOf(adapter), onSourceDetailObserved = observer(cancelled))
        assertCancellation(cancelled) { entries.fetchSourceProfiles(id) }
        assertOwnBook(id, seedStream, 0L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `metadata refresh keeps real claims and seeded tracks when its recommendation callback fails`() = runBlocking {
        val id = seed(imports(IOException("generic seed must not observe")))
        val entries = LibraryEntries(db.audiobookDao(), listOf(adapter),
            onSourceDetailObserved = observer(IOException("metadata recommendation unavailable")))
        entries.refreshBookCoverAndDetails(id)
        assertOwnBook(id, seedStream, 3_600L, liveCover)
        assertEquals("Пригоди", entries.getBookSync(id)!!.genre)
        assertEquals(4.25f, entries.getBookSync(id)!!.rating)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `metadata callback cancellation escapes before writing live claims`() = runBlocking {
        val id = seed(imports(IOException("generic seed must not observe")))
        val cancelled = CancellationException("metadata callback cancelled")
        val entries = LibraryEntries(db.audiobookDao(), listOf(adapter), onSourceDetailObserved = observer(cancelled))
        assertCancellation(cancelled) { entries.refreshBookCoverAndDetails(id) }
        assertOwnBook(id, seedStream, 0L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `duration enrichment still writes its real duration when its recommendation callback fails`() = runBlocking {
        val id = seed(imports(IOException("generic seed must not observe")))
        val enrichment = DurationEnrichment(db.audiobookDao(), adapterFor = { adapter },
            onSourceDetailObserved = observer(IOException("duration recommendation unavailable")))
        assertEquals(1, enrichment.enrichUnknownDurations(now = { 1_700_000_000_000L }))
        assertOwnBook(id, seedStream, 3_600L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `duration callback cancellation escapes before updating the stored duration`() = runBlocking {
        val id = seed(imports(IOException("generic seed must not observe")))
        val cancelled = CancellationException("duration callback cancelled")
        val enrichment = DurationEnrichment(db.audiobookDao(), adapterFor = { adapter },
            onSourceDetailObserved = observer(cancelled))
        assertCancellation(cancelled) { enrichment.enrichUnknownDurations(now = { 1_700_000_000_000L }) }
        assertOwnBook(id, seedStream, 0L, seedCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    private fun seeder(failure: Exception, probes: MutableList<String>, imports: LibraryImport,
        imported: MutableList<String>) = LibrarySeeder(
        candidates = { listOf(GlobalSearchResult(
            title = "Тигролови", author = "Іван Багряний", mergeKey = "тигролови|іван багряний",
            sources = listOf(GlobalSearchSource("sluhayua", "Sluhay UA", pageUrl)))) },
        adapterFor = { adapter },
        known = { key -> db.audiobookDao().findByMergeKey(key) != null },
        streamProbe = { stream -> probes += stream; true },
        import = { sourceId, receivedDetail ->
            imported += imports.importBookFromSource(sourceId, receivedDetail, writeBackProfile = false).id
        },
        onSourceDetailObserved = observer(failure))

    @Test
    fun `a real Room seed imports once after an ordinary recommendation callback failure`() = runBlocking {
        val probes = mutableListOf<String>()
        val imported = mutableListOf<String>()
        val failure = IOException("seed recommendation unavailable")
        val result = seeder(failure, probes, imports(failure), imported).seedOnce()
        assertEquals(LibrarySeeder.SeedResult(1, 1, 0, 0), result)
        assertEquals(listOf("https://mp3.sluhay.com.ua/100/fresh.mp3"), probes)
        assertEquals(1, imported.size)
        assertOwnBook(imported.single(), freshStream, 3_600L, liveCover)
        assertObservedWithoutLinkedWork(adapter.response)
    }

    @Test
    fun `seed callback cancellation escapes before the empty chapter guard with no probe or Room import`() = runBlocking {
        adapter.response = detail().copy(chapters = emptyList())
        val probes = mutableListOf<String>()
        val imported = mutableListOf<String>()
        val cancelled = CancellationException("seed callback cancelled")
        assertCancellation(cancelled) { seeder(cancelled, probes, imports(cancelled), imported).seedOnce() }
        assertTrue(probes.isEmpty())
        assertTrue(imported.isEmpty())
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertObservedWithoutLinkedWork(adapter.response)
    }
}
