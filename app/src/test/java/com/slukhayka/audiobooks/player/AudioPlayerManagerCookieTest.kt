package com.slukhayka.audiobooks.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.source.SourceCookieProvider
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1722 — regression witness for the ANR path:
 * `PlayerScreen onClick → MainViewModel → togglePlayPause → play →
 * prepareChapter → CookieManager.getCookie`.
 *
 * The cookie is resolved once on IO ([StreamCookieCache.refresh]); the play
 * and auto-advance paths run on the application looper and must only read the
 * cached header. A provider call made from the main thread is recorded and
 * fails the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioPlayerManagerCookieTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var listeningState: ListeningStateStore
    private lateinit var book: AudiobookEntity
    private lateinit var chapters: List<ChapterEntity>
    private lateinit var playable: List<SourceCatalog.PlayableChapter>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = TestDataFactory.dataBooks(),
            chapters = TestDataFactory.dataChapters()
        )
        listeningState = ListeningStateStore(dao, dispatcher)
        // The streaming fixture book; its tracks live on fixtures.4read.org,
        // so headersFor() is allowed to attach the 4read Cookie header.
        book = TestDataFactory.dataBooks()[1]
        chapters = TestDataFactory.chaptersFor(book)
        playable = chapters.mapIndexed { index, chapter ->
            SourceCatalog.PlayableChapter(
                chapter = chapter,
                track = TestDataFactory.tracksFor(book, "4read")[index]
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `prepareChapter and play never read the WebView jar on main`() = runTest(dispatcher) {
        val provider = RecordingCookieProvider("cf_clearance=abc")
        val cache = StreamCookieCache(provider, "https://4read.org/", Dispatchers.IO)
        // The one Binder read, exactly as production does it: on IO.
        runBlocking(Dispatchers.IO) { cache.refresh() }
        assertEquals(1, provider.calls.size)

        val (manager, factory) = newManager(cachedCookie = cache::cachedHeader)
        try {
            manager.loadAndPlayBook(
                book,
                chapters,
                playable = playable,
                initialChapterIndex = 0,
                autoPlay = false
            )
            assertEquals("cf_clearance=abc", manager.lastAppliedStreamHeaders["Cookie"])

            // A chapter switch is the auto-advance shape: on the application
            // looper, reading only the cached header.
            manager.prepareChapter(1, autoPlay = false)
            assertEquals("cf_clearance=abc", manager.lastAppliedStreamHeaders["Cookie"])

            // The error/IDLE shape in which play() re-prepares the chapter.
            factory.current.simulateNetworkError()
            manager.play()
            assertEquals("cf_clearance=abc", manager.lastAppliedStreamHeaders["Cookie"])

            assertEquals(
                "the play/auto-advance path must not re-enter the cookie provider",
                1,
                provider.calls.size
            )
            assertEquals(
                "no cookie read may happen on the main thread",
                0,
                provider.mainThreadCalls.get()
            )
        } finally {
            manager.release()
        }
    }

    @Test
    fun `an explicitly resolved header wins and bypasses the cookie provider`() = runTest(dispatcher) {
        // The "old wiring" shape: the cached-value lambda would hit the jar if
        // it were consulted. A ready header must make that consultation
        // unnecessary.
        val provider = RecordingCookieProvider("cf_cached=1")
        val (manager, _) = newManager(
            cachedCookie = { provider.cookieFor("https://4read.org/") }
        )
        try {
            manager.loadAndPlayBook(
                book,
                chapters,
                playable = playable,
                initialChapterIndex = 0,
                autoPlay = false,
                cookieHeader = "cf_explicit=2"
            )

            manager.prepareChapter(1, autoPlay = false, cookieHeader = "cf_explicit=2")

            assertEquals("cf_explicit=2", manager.lastAppliedStreamHeaders["Cookie"])
            assertEquals(
                "a ready header must bypass the provider entirely",
                0,
                provider.calls.size
            )
        } finally {
            manager.release()
        }
    }

    private fun newManager(
        cachedCookie: () -> String
    ): Pair<AudioPlayerManager, RecordingPlayerFactory> {
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context,
            listeningState,
            { bookId ->
                dao.getChaptersListForBook(bookId)
                    .map { chapter -> SourceCatalog.PlayableChapter(chapter, null) }
            },
            injectedPlayerFactory = factory,
            now = { System.currentTimeMillis() },
            ioDispatcher = dispatcher,
            widgetSyncEnabled = false,
            // The cache read is production's cookieProvider; the refresher is
            // deliberately null so this test only observes synchronous calls.
            cookieProvider = cachedCookie
        )
        return manager to factory
    }

    private class RecordingCookieProvider(private val cookie: String) : SourceCookieProvider {
        val calls = CopyOnWriteArrayList<Thread>()
        val mainThreadCalls = AtomicInteger()

        override fun cookieFor(url: String): String {
            calls += Thread.currentThread()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mainThreadCalls.incrementAndGet()
            }
            return cookie
        }
    }
}
