package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import kotlinx.coroutines.flow.first
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
        language = "uk",
        genres = listOf("Поезія"),
        description = "Збірка поезій."
    )

    private class FakeAdapter(
        var detail: SourceBookDetail
    ) : SourceAdapter {
        override val sourceId: String = "soundbooks"
        var fetchCalls = 0
        var beforeFetch: (suspend () -> Unit)? = null

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchCalls++
            beforeFetch?.invoke()
            return detail
        }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = null
    }

    @Test
    fun `manual reverse moves audio with chapters and refuses a stale editor`() = runBlocking {
        val adapter = FakeAdapter(detailOf(listOf("https://cdn.test/one.mp3", "https://cdn.test/two.mp3")))
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val before = dao.getChaptersListForBook(book.id)
        val tracks = dao.getTracksForBookSync(book.id)
        assertEquals(ChapterReorderResult.APPLIED, imports.reorderChapters(book.id, before.map { it.id }, before.reversed().map { it.id }))
        assertEquals(before.reversed().map { it.id }, dao.getChaptersListForBook(book.id).map { it.id })
        val playable = com.slukhayka.audiobooks.data.catalog.SourceCatalog(dao, listOf(adapter), imports).getPlayableChapters(book.id)
        assertEquals(before.reversed().map { it.title }, playable.map { it.chapter.title })
        assertEquals(listOf("https://cdn.test/two.mp3", "https://cdn.test/one.mp3"), playable.map { it.track?.url })
        assertEquals(tracks.reversed().map { it.id }, dao.getTracksForBookSync(book.id).map { it.id })
        assertEquals(ChapterReorderResult.STALE, imports.reorderChapters(book.id, before.map { it.id }, before.map { it.id }))
    }

    @Test
    fun `playback never pairs chapters read before reorder with tracks read after it`() = runBlocking {
        val adapter = FakeAdapter(detailOf(listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3")))
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val ids = dao.getChaptersListForBook(book.id).map { it.id }
        var crossed = false
        val racingDao = object : AudiobookDao by dao {
            override suspend fun getChaptersListForBook(bookId: String): List<com.slukhayka.audiobooks.data.db.ChapterEntity> {
                val before = dao.getChaptersListForBook(bookId)
                if (!crossed && bookId == book.id) {
                    crossed = true
                    assertEquals(ChapterReorderResult.APPLIED, imports.reorderChapters(bookId, ids, ids.reversed()))
                }
                return before
            }
        }
        val catalog = com.slukhayka.audiobooks.data.catalog.SourceCatalog(racingDao, listOf(adapter), imports)
        val playable = catalog.getPlayableChapters(book.id)
        assertTrue(crossed)
        assertEquals(mapOf(ids[0] to "https://cdn.test/a.mp3", ids[1] to "https://cdn.test/b.mp3"), playable.associate { it.chapter.id to it.track?.url })
        assertEquals(ids.reversed(), playable.map { it.chapter.id })
        assertEquals(ids.reversed(), catalog.storedEditionSources(book.id).single().map { it.chapter.id })
    }

    @Test
    fun `manual order preserves raw anchors and projects the same audio after repeated edits`() = runBlocking {
        val adapter = FakeAdapter(detailOf(listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3", "https://cdn.test/c.mp3")))
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val before = dao.getChaptersListForBook(book.id)
        val edition = dao.getEditionForWork(book.id)!!
        val progress = PlaybackProgressEntity(edition.id, book.id, currentChapterIndex = 0, currentPositionSeconds = 42L, lastListenedAt = 100L, isCompleted = true, lastPausedAtEpochMs = 90L, preferredSpeed = 1.5f)
        dao.savePlaybackProgress(progress)
        dao.insertBookmark(BookmarkEntity(bookId = book.id, editionId = edition.id, chapterIndex = 0, chapterTitle = before[0].title, timestampSeconds = 17L, note = "Якір", createdAt = 100L))
        val bookmarks = dao.getBookmarksForBookSync(book.id)
        val sources = dao.getSourcesForBookSync(book.id)
        val tracks = dao.getTracksForBookSync(book.id)
        val first = before.map { it.id }
        assertEquals(ChapterReorderResult.APPLIED, imports.reorderChapters(book.id, first, first.reversed()))
        assertEquals(progress, dao.getPlaybackProgressSync(book.id))
        assertEquals(bookmarks, dao.getBookmarksForBookSync(book.id))
        assertEquals(edition, dao.getEditionForWork(book.id))
        assertEquals(sources, dao.getSourcesForBookSync(book.id))
        assertEquals(tracks.map { it.copy(trackIndex = 2 - it.trackIndex) }.sortedBy { it.trackIndex }, dao.getTracksForBookSync(book.id))
        val listening = ListeningStateStore(dao)
        assertEquals(2, listening.getProgressSync(book.id)!!.currentChapterIndex)
        assertEquals(2, listening.observeBookmarks(book.id).first().single().chapterIndex)
        assertEquals(ChapterReorderResult.APPLIED, imports.reorderChapters(book.id, first.reversed(), listOf(first[1], first[0], first[2])))
        assertEquals(progress, dao.getPlaybackProgressSync(book.id))
        assertEquals(1, listening.getProgressSync(book.id)!!.currentChapterIndex)
        assertEquals(before[0].id, listening.getAnchoredProgress(book.id)!!.chapterId)
        assertEquals(before[0].id, listening.chapterIdForBookmark(bookmarks.first()))
        listening.updateProgress(book.id, 0, 23L)
        assertEquals(1, dao.getPlaybackProgressSync(book.id)!!.currentChapterIndex)
        assertEquals(0, listening.getProgressSync(book.id)!!.currentChapterIndex)
        listening.addBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 0, chapterTitle = before[1].title, timestampSeconds = 8L, note = "Новий"))
        assertEquals(1, dao.getBookmarksForBook(book.id).first().first { it.note == "Новий" }.chapterIndex)
        // A late player save captured the Chapter before either edit.
        listening.updateProgressForChapter(book.id, before[0].id, 31L)
        assertEquals(0, dao.getPlaybackProgressSync(book.id)!!.currentChapterIndex)
        assertEquals(1, listening.getProgressSync(book.id)!!.currentChapterIndex)
    }

    @Test
    fun `manual order moves all Edition sources including a partial alternate`() = runBlocking {
        val adapter = FakeAdapter(detailOf(listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3")))
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val edition = dao.getEditionForWork(book.id)!!
        dao.insertSources(listOf(SourceEntity(id = "alternate", bookId = book.id, editionId = edition.id, type = "youtube", url = "https://youtube.com/playlist?list=test")))
        val alternate = SourceTrackEntity(id = "alternate-1", sourceId = "alternate", trackIndex = 0, url = "https://cdn.test/alt.mp3", localFilePath = "/downloaded.mp3", contentHash = "hash", isDownloaded = true)
        dao.insertTracks(listOf(alternate))
        val ids = dao.getChaptersListForBook(book.id).map { it.id }
        assertEquals(ChapterReorderResult.APPLIED, imports.reorderChapters(book.id, ids, ids.reversed()))
        assertEquals(listOf(alternate.copy(trackIndex = 1)), dao.getTracksForSourceSync("alternate"))
        assertEquals(ChapterReorderResult.INVALID_ORDER, imports.reorderChapters(book.id, ids.reversed(), listOf(ids[0], ids[0])))
        assertEquals(ids.reversed(), dao.getChaptersListForBook(book.id).map { it.id })
    }

    @Test
    fun `a background track copy cannot restore indices read before manual reorder`() = runBlocking {
        val adapter = FakeAdapter(detailOf(listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3")))
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val staleTracks = dao.getTracksForBookSync(book.id)
        val ids = dao.getChaptersListForBook(book.id).map { it.id }
        assertEquals(ChapterReorderResult.APPLIED, imports.reorderChapters(book.id, ids, ids.reversed()))
        dao.insertTracks(staleTracks.map { it.copy(isDownloaded = true, localFilePath = "/downloads/${it.id}.mp3") })
        val current = dao.getTracksForBookSync(book.id)
        assertEquals(staleTracks.reversed().map { it.id }, current.map { it.id })
        assertEquals(staleTracks.reversed().map { it.url }, current.map { it.url })
        assertTrue(current.all { it.isDownloaded && it.localFilePath == "/downloads/${it.id}.mp3" })
    }

    @Test
    fun `heal follows the original provider chapter after manual reverse and publishes raw provider order`() = runBlocking {
        val old = listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3")
        val adapter = FakeAdapter(detailOf(old))
        val store = FakeProfileStore()
        val imports = imports(store, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val ids = dao.getChaptersListForBook(book.id).map { it.id }
        imports.reorderChapters(book.id, ids, ids.reversed())
        store.puts.clear()
        adapter.detail = detailOf(listOf(old[0], "https://cdn.test/new-b.mp3"))
        assertEquals("https://cdn.test/new-b.mp3", imports.refreshStreamUrl(book.id, 0, old[1]))
        assertEquals(listOf("https://cdn.test/new-b.mp3", old[0]), dao.getTracksForBookSync(book.id).map { it.url })
        assertEquals(listOf(old[0], "https://cdn.test/new-b.mp3"), store.puts.single().chapters.map { it.streamUrl })
        // A genuine provider reorder still fails after a listener correction.
        adapter.detail = detailOf(listOf("https://cdn.test/new-b.mp3", old[0]))
        assertNull(imports.refreshStreamUrl(book.id, 1, old[0]))
    }

    @Test
    fun `a reorder during the provider fetch never heals a stale chapter request`() = runBlocking {
        val old = listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3")
        val adapter = FakeAdapter(detailOf(old))
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val ids = dao.getChaptersListForBook(book.id).map { it.id }
        adapter.detail = detailOf(listOf("https://cdn.test/new-a.mp3", old[1]))
        adapter.beforeFetch = { imports.reorderChapters(book.id, ids, ids.reversed()) }
        assertNull(imports.refreshStreamUrl(book.id, 0, old[0]))
        assertEquals(old.reversed(), dao.getTracksForBookSync(book.id).map { it.url })
    }

    @Test
    fun `a new source attaches in the listener order without changing its original provider indices`() = runBlocking {
        val old = detailOf(listOf("https://cdn.test/a.mp3", "https://cdn.test/b.mp3")).copy(narrator = "Спільний диктор")
        val adapter = FakeAdapter(old)
        val imports = imports(null, listOf(adapter))
        val book = imports.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        val ids = dao.getChaptersListForBook(book.id).map { it.id }
        imports.reorderChapters(book.id, ids, ids.reversed())
        val detail = old.copy(url = "https://sluhay.com/kobzar", chapters = listOf(SourceChapter("Розділ 1", "https://cdn.test/alt-a.mp3", 100L), SourceChapter("Розділ 2", "https://cdn.test/alt-b.mp3", 200L)))
        val attached = imports.importBookFromSource("sluhay", detail)
        assertEquals(book.id, attached.id)
        val source = dao.getSourcesForBookSync(book.id).first { it.type == "sluhay" }
        assertEquals(listOf("https://cdn.test/alt-b.mp3", "https://cdn.test/alt-a.mp3"), dao.getTracksForSourceSync(source.id).map { it.url })
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
    fun `refresh never persists refused audio returned by an allowed source`() = runBlocking {
        val old = "https://arch.sound-books.net/kobzar/old.mp3"
        val adapter = FakeAdapter(detailOf(listOf(old)))
        val door = imports(null, listOf(adapter))
        val book = door.importFromSourceUrl("soundbooks", bookUrl, identity)!!
        for (blocked in listOf("https://reasd.org/notice/4read-notice.mp3", "https://4read.org/audio/book.mp3")) {
            adapter.detail = detailOf(listOf(blocked))
            assertNull(door.refreshStreamUrl(book.id, 0, old))
            assertEquals(old, tracksOf(book.id).single().url)
        }
    }

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
