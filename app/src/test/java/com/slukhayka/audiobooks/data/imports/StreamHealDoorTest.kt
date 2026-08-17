package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.CoverProvenance
import com.slukhayka.audiobooks.data.metadata.ProfileChapter
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SharedProfileEntry
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-32 T4 (#234) — the self-healing door
 * ([LibraryImport.refreshStreamUrl]): a 404/403 stream failure re-fetches the
 * source page, swaps the fresh URL into the primary source's track row, and
 * writes the refreshed profile back best-effort. A page that yields the same
 * URL, a failing re-fetch, an unknown source or a book without a source URL
 * contribute nothing (the player then surfaces the honest failure).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamHealDoorTest {

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

    private fun imports(store: FakeProfileStore?, adapters: List<SourceAdapter> = emptyList()) =
        LibraryImport(dao, context, adapters, profileStore = store)

    private val identity = KnownBookIdentity("Кобзар", "Тарас Шевченко")
    private val bookUrl = "https://sound-books.net/kobzar.html"

    /** Page whose chapter [index] streams from the given URL. */
    private fun detailOf(urls: List<String>) = SourceBookDetail(
        title = "Кобзар",
        author = "Тарас Шевченко",
        url = bookUrl,
        coverImageUrl = "https://sound-books.net/uploads/kobzar.jpg",
        chapters = urls.mapIndexed { index, url ->
            SourceChapter("Розділ ${index + 1}", url, (index + 1) * 100L)
        },
        totalDurationSeconds = urls.size * 100L,
        rating = 4.5,
        genres = listOf("Поезія"),
        description = "Збірка поезій."
    )

    private class FakeAdapter(
        var detail: SourceBookDetail
    ) : SourceAdapter {
        override val sourceId: String = "soundbooks"
        var fetchCalls = 0

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchCalls++
            return detail
        }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = null
    }

    private class ThrowingAdapter : SourceAdapter {
        override val sourceId: String = "soundbooks"
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            throw IllegalStateException("site down")
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = null
    }

    private class FakeProfileStore : SharedBookMetaStore {
        val puts = mutableListOf<BookProfile>()

        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: com.slukhayka.audiobooks.data.metadata.DurationProvenance) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? = null
        override suspend fun putProfile(sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance) {
            puts += profile
        }
        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) = Unit
    }

    private suspend fun tracksOf(bookId: String) = dao.getTracksForBookSync(bookId)

    // --- T4 (#234): the heal door ----------------------------------------

    @Test
    fun `a moved stream heals - fresh URL lands in the track row and the profile refreshes`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/old-1.mp3", "https://arch.sound-books.net/kobzar/old-2.mp3")))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", bookUrl, identity)
        assertNotNull(book)
        val failedUrl = tracksOf(book!!.id).first().url
        // The import itself resolved the page once (T2 write-back) — start
        // the door's observability from a clean slate.
        store.puts.clear()

        // The page moved the first chapter to a fresh URL.
        adapter.detail = detailOf(listOf("https://arch.sound-books.net/kobzar/new-1.mp3", "https://arch.sound-books.net/kobzar/old-2.mp3"))
        val healed = imports(store, listOf(adapter))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = failedUrl)

        assertEquals("https://arch.sound-books.net/kobzar/new-1.mp3", healed)
        assertEquals("one import resolution + one heal re-fetch", 2, adapter.fetchCalls)
        val tracks = tracksOf(book.id)
        assertEquals("https://arch.sound-books.net/kobzar/new-1.mp3", tracks[0].url)
        assertEquals("https://arch.sound-books.net/kobzar/old-2.mp3", tracks[1].url)
        // The refreshed page is written back so the shared base stops serving
        // the dead link.
        assertEquals(1, store.puts.size)
        assertEquals("https://arch.sound-books.net/kobzar/new-1.mp3", store.puts.single().chapters[0].streamUrl)
    }

    @Test
    fun `a page that yields the same URL contributes nothing`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/1.mp3")))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", bookUrl, identity)
        val failedUrl = tracksOf(book!!.id).first().url
        store.puts.clear()

        val healed = imports(store, listOf(adapter))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = failedUrl)

        assertNull(healed)
        assertEquals("one import resolution + one heal re-fetch", 2, adapter.fetchCalls)
        assertEquals(failedUrl, tracksOf(book.id).first().url)
        assertTrue("nothing changed -> no profile write", store.puts.isEmpty())
    }

    @Test
    fun `a failing page re-fetch leaves the track untouched`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/1.mp3")))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", bookUrl, identity)
        val failedUrl = tracksOf(book!!.id).first().url
        store.puts.clear()

        val healed = imports(store, listOf(ThrowingAdapter()))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = failedUrl)

        assertNull(healed)
        assertEquals(failedUrl, tracksOf(book.id).first().url)
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `a book from an unknown source never re-fetches`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/1.mp3")))
        val book = imports(store, listOf(adapter))
            .importBookFromSource(
                "unknown",
                detailOf(listOf("https://arch.sound-books.net/kobzar/1.mp3")).copy(url = "https://example.com/book.html")
            )
        store.puts.clear()

        val healed = imports(store, listOf(adapter))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = "https://arch.sound-books.net/kobzar/1.mp3")

        assertNull(healed)
        assertEquals("no adapter for the source -> no fetch", 0, adapter.fetchCalls)
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `a book without a source URL never re-fetches`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/1.mp3")))
        val book = imports(store, listOf(adapter))
            .importBookFromSource("soundbooks", detailOf(listOf("https://arch.sound-books.net/kobzar/1.mp3")).copy(url = ""))
        store.puts.clear()

        val healed = imports(store, listOf(adapter))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = "https://arch.sound-books.net/kobzar/1.mp3")

        assertNull(healed)
        assertEquals("a local book has no page to re-fetch", 0, adapter.fetchCalls)
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `a reordered page never heals - the index pairing would play the wrong chapter`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/a.mp3", "https://arch.sound-books.net/kobzar/b.mp3")))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", bookUrl, identity)
        val tracks = tracksOf(book!!.id)
        store.puts.clear()

        // The page swapped the chapters around: index 0 now serves chapter
        // 2's audio. Index-based healing would play the WRONG chapter.
        adapter.detail = detailOf(listOf("https://arch.sound-books.net/kobzar/b.mp3", "https://arch.sound-books.net/kobzar/a.mp3"))
        val healed = imports(store, listOf(adapter))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = tracks[0].url)

        assertNull("a reordered page must not heal", healed)
        assertEquals(tracks[0].url, tracksOf(book.id)[0].url)
        assertEquals(tracks[1].url, tracksOf(book.id)[1].url)
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `a bulk move heals - every other chapter kept its own index`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf(listOf("https://arch.sound-books.net/kobzar/a.mp3", "https://arch.sound-books.net/kobzar/b.mp3")))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", bookUrl, identity)
        val tracks = tracksOf(book!!.id)
        store.puts.clear()

        // The whole CDN moved: EVERY track URL changed, none reordered.
        adapter.detail = detailOf(listOf("https://cdn2.sound-books.net/kobzar/a.mp3", "https://cdn2.sound-books.net/kobzar/b.mp3"))
        val healed = imports(store, listOf(adapter))
            .refreshStreamUrl(book.id, chapterIndex = 0, failedUrl = tracks[0].url)

        assertEquals("https://cdn2.sound-books.net/kobzar/a.mp3", healed)
        assertEquals(1, store.puts.size)
    }
}
