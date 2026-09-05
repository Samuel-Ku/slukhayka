package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Manager behavior for wayfinder #26 (per-book speed memory + global default)
 * and wayfinder #25 (smart rewind on resume + position-history undo). The wall
 * clock is injected so the rewind tiers are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeedAndRewindManagerTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var listeningState: ListeningStateStore

    private val book: AudiobookEntity = TestDataFactory.dataBooks()[STREAMING_BOOK_INDEX]
    private val chapters: List<ChapterEntity> = TestDataFactory.chaptersFor(book)
    // A playable fixture is required: missing audio correctly stops before engine creation.
    private val playable = chapters.zip(TestDataFactory.tracksFor(book, "speed-fixture")) { chapter, track ->
        com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter(chapter, track)
    }

    /** Injectable wall clock; tests advance it to simulate real time passing. */
    private var clockMs: Long = 1_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = TestDataFactory.dataBooks(),
            chapters = TestDataFactory.dataChapters()
        )
        listeningState = ListeningStateStore(dao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun managerTest(
        settings: PlaybackSettings? = null,
        body: suspend TestScope.(AudioPlayerManager, RecordingPlayerFactory) -> Unit
    ) = runTest(dispatcher) {
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context,
            listeningState,
            // ADR-0007: the fetcher yields chapter→track pairs (chapter rows
            // carry no stream URLs); these tests only assert positions/speeds.
            { dao.getChaptersListForBook(it).map { ch -> com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter(ch, null) } },
            injectedPlayerFactory = factory,
            now = { clockMs },
            settings = settings,
            widgetSyncEnabled = false
        )
        try {
            body(manager, factory)
        } finally {
            manager.release()
        }
    }

    // ---------------------------------------------------------------------
    // Wayfinder #26: per-book speed memory and the global default
    // ---------------------------------------------------------------------

    @Test
    fun `load applies the book's preferred speed`() = managerTest { manager, _ ->
        val fastBook = book.copy().also { it.preferredSpeed = 1.5f }
        manager.loadAndPlayBook(fastBook, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        assertEquals(1.5f, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
    }

    @Test
    fun `load uses 1x when neither book nor default has a speed`() = managerTest { manager, _ ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        assertEquals(1.0f, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
    }

    @Test
    fun `load uses the global default when the book has no saved speed`() = managerTest(
        settings = PlaybackSettings(context)
    ) { manager, _ ->
        manager.setDefaultSpeed(1.5f)
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        assertEquals(1.5f, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
    }

    // ---------------------------------------------------------------------
    // Wayfinder #25: smart rewind on resume
    // ---------------------------------------------------------------------

    @Test
    fun `resume after a long pause rewinds by the medium tier`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 30 * 60 * 1000L // 30-minute break
        manager.play()

        assertEquals(600_000L - SmartRewind.REWIND_MEDIUM_SECONDS * 1000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `explicit reload position does not inherit the previous pause rewind`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.pause()
        clockMs += 30 * 60 * 1000L

        manager.loadAndPlayBook(book, chapters, playable = playable, initialPositionSeconds = 42L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.play()

        assertEquals(42_000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `quick play pause toggle does not rewind`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 1_000L // one-second toggle
        manager.play()

        assertEquals(600_000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `an overnight pause rewinds the most`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 26 * 60 * 60 * 1000L // next day
        manager.play()

        assertEquals(600_000L - SmartRewind.REWIND_LONG_SECONDS * 1000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `resume at the very start does not rewind below zero`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 2L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 30 * 60 * 1000L
        manager.play()

        assertTrue("position must not go negative", manager.playerState.value.currentPositionMs >= 0L)
    }

    // ---------------------------------------------------------------------
    // Wayfinder #25: position-history undo after a big seek
    // ---------------------------------------------------------------------

    @Test
    fun `a big seek becomes undoable and undo restores the position`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.seekTo(600_000L) // a 9-minute jump

        assertTrue(manager.playerState.value.canUndoSeek)
        assertEquals(60_000L, manager.playerState.value.undoFromPositionMs)

        manager.undoLastSeek()

        assertEquals(60_000L, manager.playerState.value.currentPositionMs)
        assertFalse(manager.playerState.value.canUndoSeek)
    }

    @Test
    fun `a small seek is not undoable`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.seekTo(90_000L) // 30-second nudge

        assertFalse(manager.playerState.value.canUndoSeek)
    }

    @Test
    fun `loading a new book clears the undo state`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.seekTo(600_000L)
        assertTrue(manager.playerState.value.canUndoSeek)

        manager.loadAndPlayBook(book.copy().also { it.preferredSpeed = 1.25f }, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)

        assertFalse(manager.playerState.value.canUndoSeek)
    }

    private companion object {
        const val STREAMING_BOOK_INDEX = 1
        const val SPEED_TOLERANCE = 0.001f
    }
}
