package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #430 — high-level 4read recovery coordinator, JVM seam.
 *
 * One scenario supplies captured page data, ordered observed audio candidates,
 * an existing or absent Edition, a playback verdict, a clean-request verdict
 * and a [SharedBookMetaStore] fake; assertions cover the resulting Library
 * rows, Source Tracks, resume command, publication decision and visible outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserRecoveryCoordinatorTest {

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

    private fun fakeAdapter(htmlToDetail: Map<String, SourceBookDetail?>): SourceAdapter =
        object : SourceAdapter {
            override val sourceId: String = "4read"
            override suspend fun search(query: String): List<SourceBook> = emptyList()
            override suspend fun fetchBookPage(url: String): SourceBookDetail = throw IllegalStateException("not used")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
            override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = htmlToDetail[html]
        }

    private fun detail(
        title: String = "Кобзар",
        author: String = "Тарас Шевченко",
        narrator: String = "Іван Петров",
        url: String = "https://4read.org/kobzar.html",
        chapters: List<Pair<String, String>>
    ) = SourceBookDetail(
        title = title,
        author = author,
        narrator = narrator,
        url = url,
        chapters = chapters.map { (t, u) -> SourceChapter(t, u) }
    )

    private class FakeProfileStore : SharedBookMetaStore {
        val puts = mutableListOf<Pair<String, BookProfile>>()
        var shouldFailPut = false
        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: com.slukhayka.audiobooks.data.metadata.DurationProvenance) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): com.slukhayka.audiobooks.data.metadata.SharedProfileEntry? = null
        override suspend fun putProfile(sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance) {
            if (shouldFailPut) throw IllegalStateException("firestore denied")
            puts += sourceId to profile
        }
        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: com.slukhayka.audiobooks.data.metadata.CoverProvenance) = Unit
    }

    private suspend fun seedBook(detail: SourceBookDetail): String {
        val adapter = fakeAdapter(mapOf("seed" to detail))
        val imports = LibraryImport(dao, context, listOf(adapter))
        val book = imports.importWebSourcePage("4read", detail.url, "seed")
            ?: error("seed failed")
        // Bypass writeBackProfile gate for seed (4read stays local) — seed directly via importBookFromSource with writeBack false is already done;
        // For test, we want the book persisted regardless of profile, so seed via direct import.
        return book.id
    }

    @Test
    fun `new import success closes browser and starts at chapter 0`() = runBlocking {
        val newDetail = detail(
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("html" to newDetail))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true }
        )

        val outcome = coordinator.recover(
            bookId = null,
            sourceId = "4read",
            url = "https://4read.org/kobzar.html",
            html = "html",
            capturedAudioUrls = emptyList()
        )

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        val succ = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertTrue(succ.shouldCloseBrowser)
        assertEquals(0, succ.resumeChapterIndex)
        assertEquals(0L, succ.resumePositionMs)
        assertTrue(succ.isNewImport)
        assertNotNull(dao.getAudiobookById(succ.book.id))
    }

    @Test
    fun `recovery success preserves chapter and position and closes browser`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        // Seed listening progress at chapter 1, position 30s
        val edition = dao.getEditionForWork(bookId)!!
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = edition.id, bookId = bookId, currentChapterIndex = 1, currentPositionSeconds = 30L, lastListenedAt = System.currentTimeMillis()
            )
        )

        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, url -> url.contains("new2.mp3") }
        )

        val outcome = coordinator.recover(
            bookId = bookId,
            sourceId = "4read",
            url = "https://4read.org/kobzar.html",
            html = "cap",
            requestedChapterIndex = 1,
            requestedPositionMs = 30_000L
        )

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        val succ = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertTrue(succ.shouldCloseBrowser)
        assertEquals(1, succ.resumeChapterIndex)
        assertEquals(30_000L, succ.resumePositionMs)
        // Tracks updated
        val tracks = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/new1.mp3", tracks[0].url)
    }

    @Test
    fun `http prefix alone is not success - needs player verdict`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> false } // Player says dead
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        val fail = outcome as BrowserRecoveryCoordinator.Outcome.Failure
        assertFalse(fail.shouldCloseBrowser)
        assertTrue(fail.message.contains("Аудіо"))
        // Tracks must be rolled back on verifier failure — dead URL must not persist.
        val tracks = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/old1.mp3", tracks[0].url)
    }

    @Test
    fun `empty or challenge page keeps browser open with honest message`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val emptyDetail = detail(chapters = emptyList())
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("empty" to emptyDetail))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "empty")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        assertFalse((outcome as BrowserRecoveryCoordinator.Outcome.Failure).shouldCloseBrowser)
    }

    @Test
    fun `dead candidate via 403 is failure browser stays open`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        // Captured html yields playable URLs but verifier says 403
        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> false }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
    }

    @Test
    fun `new import survives recovery verdict failure and stays persisted in Room`() = runBlocking {
        // Regression (#430, hypothesis 3): a book imported via "Додати цю книгу"
        // (new import, bookId == null) must NOT be deleted from the DB when the
        // Player verdict fails. The import already committed a row; deletion here
        // is unrecoverable data loss after force-stop.
        val newDetail = detail(
            title = "Сни",
            chapters = listOf(
                "Глава 1" to "https://s1.reasd.org/sni/new1.mp3",
                "Глава 2" to "https://s1.reasd.org/sni/new2.mp3"
            )
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("html" to newDetail))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> false } // dead link / 403
        )

        val outcome = coordinator.recover(
            bookId = null,
            sourceId = "4read",
            url = "https://4read.org/sni.html",
            html = "html",
            capturedAudioUrls = emptyList()
        )

        // Verdict failed — visible outcome is a failure, browser stays open.
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        // But the just-imported book must remain persisted in Room.
        val persisted = dao.getAllAudiobooksOnce()
        assertEquals(1, persisted.size)
        assertEquals("Сни", persisted.first().title)
    }

    @Test
    fun `search fallback url is generated when exact source url missing`() = runBlocking {
        val original = detail(title = "Кобзар", author = "Тарас Шевченко", chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        // Simulate missing exact URL by passing blank url and checking helper
        val searchUrl = BrowserRecoveryCoordinator.searchUrlFor("Кобзар")
        assertTrue(searchUrl.startsWith("https://4read.org/index.php?do=search"))
        assertTrue(searchUrl.contains("Кобзар") || searchUrl.contains("%D0%9A"))

        val entryUrl = BrowserRecoveryCoordinator.recoveryEntryUrl(dao, bookId)
        // Should be exact source URL when present
        assertEquals("https://4read.org/kobzar.html", entryUrl)

        // Delete the source to simulate missing URL
        dao.deleteSourcesForBook(bookId)
        val fallback = BrowserRecoveryCoordinator.recoveryEntryUrl(dao, bookId)
        assertTrue(fallback.contains("search"))
    }

    @Test
    fun `shareable candidate is published only after clean probe success`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val store = FakeProfileStore()
        var cleanProbeCalled = false
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            profileStore = store,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            cleanProbe = BrowserRecoveryCoordinator.CleanProbe {
                cleanProbeCalled = true
                true
            }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        assertTrue(cleanProbeCalled)
        assertEquals(1, store.puts.size)
    }

    @Test
    fun `cookie-bound candidate stays local when clean probe fails`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val store = FakeProfileStore()
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            profileStore = store,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            cleanProbe = BrowserRecoveryCoordinator.CleanProbe { false } // clean probe fails → cookie-bound
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        // Local success, but not published
        assertEquals(0, store.puts.size)
        assertFalse((outcome as BrowserRecoveryCoordinator.Outcome.Success).publishedProfile)
    }

    @Test
    fun `unavailable firebase with failed put stays success and silent`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val store = FakeProfileStore().apply { shouldFailPut = true }
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            profileStore = store,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            cleanProbe = BrowserRecoveryCoordinator.CleanProbe { true }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        // Local success despite failed put
        assertFalse((outcome as BrowserRecoveryCoordinator.Outcome.Success).publishedProfile)
    }
}
