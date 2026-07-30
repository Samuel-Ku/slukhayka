package com.example.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.repository.AudiobookRepository
import com.example.testing.FakeAudiobookDao
import com.example.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [AudioPlayerManager] driven by [FakePlayerEngine]
 * (GitHub issues #4 and #6).
 *
 * No ExoPlayer, no audio device, no network, no wall clock: the player is
 * injected through the [PlayerFactory] seam, the data comes from
 * [TestDataFactory], and the 45s prepare timeout is advanced virtually via the
 * injected [StandardTestDispatcher].
 *
 * Robolectric is still required because `AudioPlayerManager` touches `Uri`,
 * `Log` and `Build.VERSION` -- but nothing here reaches a real media pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioPlayerManagerTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var repository: AudiobookRepository

    private val book: AudiobookEntity = TestDataFactory.dataBooks()[STREAMING_BOOK_INDEX]
    private val chapters: List<ChapterEntity> = TestDataFactory.chaptersFor(book)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = TestDataFactory.dataBooks(),
            chapters = TestDataFactory.dataChapters()
        )
        // autoSyncOnInit = false keeps the 4read catalogue fetch out of the test.
        repository = AudiobookRepository(dao, context, autoSyncOnInit = false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------
    // Ticket #4 representative cases
    // ---------------------------------------------------------------------

    @Test
    fun `prepareChapter prepares the engine and notifies ready`() = playerTest { manager, factory ->
        // Arrange
        val resolvedDurationMs = 90_000L

        // Act
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = 0, autoPlay = false)
        val engine = factory.current

        // Assert -- buffering while the engine has not reported READY yet
        assertEquals(1, factory.engines.size)
        assertEquals(1, engine.prepareCount)
        assertEquals(chapters[0].streamUrl, engine.lastMediaItemUri)
        assertTrue(manager.playerState.value.isBuffering)

        // Act -- the stream becomes playable
        engine.simulateReady(resolvedDurationMs)

        // Assert
        val state = manager.playerState.value
        assertFalse(state.isBuffering)
        assertEquals("4read Direct Stream", state.audioEngineMode)
        assertEquals(resolvedDurationMs, state.durationMs)
        assertEquals(chapters[0].streamUrl, state.currentStreamUrl)
        assertFalse("autoPlay was false", state.isPlaying)
        assertEquals(0, engine.playCount)
    }

    @Test
    fun `playback timeout flips audioEngineMode to the error state`() = playerTest { manager, factory ->
        // Arrange -- a stream that buffers forever and never reaches READY
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current
        engine.simulateTimeout()
        assertTrue(manager.playerState.value.isBuffering)

        // Act -- burn through the 45s prepare timeout in virtual time
        advanceTimeBy(FakePlayerEngine.PREPARE_TIMEOUT_MS + 1L)

        // Assert
        val state = manager.playerState.value
        assertEquals("Playback error", state.audioEngineMode)
        assertFalse(state.isBuffering)
        assertFalse(state.isPlaying)
        assertEquals("", state.currentStreamUrl)
        assertTrue("user-facing error must be set", state.lastErrorMsg.isNotBlank())
        assertTrue("the timed-out engine must be released", engine.isReleased)
    }

    @Test
    fun `chapter completion advances to the next chapter`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = 0, autoPlay = true)
        val firstEngine = factory.current
        firstEngine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
        assertEquals(0, manager.playerState.value.currentChapterIndex)

        // Act
        firstEngine.simulateEnded()

        // Assert
        val state = manager.playerState.value
        assertEquals(1, state.currentChapterIndex)
        assertEquals(chapters[1].streamUrl, state.currentStreamUrl)
        assertEquals(2, factory.engines.size)
        assertTrue("the finished engine must be released", firstEngine.isReleased)
        assertEquals(1, factory.current.prepareCount)
        assertEquals(chapters[1].streamUrl, factory.current.lastMediaItemUri)
    }

    @Test
    fun `pause then play resumes from the same position`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current
        engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
        assertTrue(manager.playerState.value.isPlaying)
        assertEquals(1, engine.playCount)

        val resumePositionMs = 60_000L
        manager.seekTo(resumePositionMs)

        // Act
        manager.pause()
        val pausedState = manager.playerState.value

        manager.play()
        val resumedState = manager.playerState.value

        // Assert
        assertFalse(pausedState.isPlaying)
        assertEquals(1, engine.pauseCount)
        assertTrue(resumedState.isPlaying)
        assertEquals(2, engine.playCount)
        assertEquals(resumePositionMs, resumedState.currentPositionMs)
        assertEquals(resumePositionMs, engine.currentPosition)
        assertTrue(engine.seekTargetsMs.contains(resumePositionMs))
    }

    // ---------------------------------------------------------------------
    // Regression guards for the Phase 2.5 hotfix (audit CR-002 / SF-003)
    // ---------------------------------------------------------------------

    @Test
    fun `player error reports the failure instead of fabricating audio`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current

        // Act
        engine.simulateNetworkError()

        // Assert -- no silent switch to an unrelated sample stream
        val state = manager.playerState.value
        assertEquals("Playback error", state.audioEngineMode)
        assertEquals("", state.currentStreamUrl)
        assertFalse(state.isPlaying)
        assertTrue(state.lastErrorMsg.isNotBlank())
        assertTrue(engine.isReleased)
        assertEquals("no replacement engine may be built", 1, factory.engines.size)
    }

    @Test
    fun `completion on the final chapter stops instead of wrapping around`() = playerTest { manager, factory ->
        // Arrange
        val lastIndex = chapters.lastIndex
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = lastIndex, autoPlay = true)
        val engine = factory.current
        val durationMs = chapters[lastIndex].durationSeconds * MILLIS_PER_SECOND
        engine.simulateReady(durationMs)

        // Act
        engine.simulateEnded()

        // Assert
        val state = manager.playerState.value
        assertEquals(lastIndex, state.currentChapterIndex)
        assertFalse(state.isPlaying)
        assertEquals(durationMs, state.currentPositionMs)
        assertEquals("no chapter after the last one", 1, factory.engines.size)
    }

    @Test
    fun `playback speed reaches the engine`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, initialChapterIndex = 0, autoPlay = false)
        val engine = factory.current
        engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)

        // Act
        manager.setPlaybackSpeed(FASTER_SPEED)

        // Assert
        assertEquals(FASTER_SPEED, engine.appliedSpeed, SPEED_TOLERANCE)
        assertEquals(FASTER_SPEED, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
        assertNotEquals(1.0f, engine.appliedSpeed)
    }

    /**
     * Builds a manager wired to a [RecordingPlayerFactory], runs [body] on the
     * shared test scheduler, and always releases the manager so its
     * progress-tracker loop cannot outlive the test.
     */
    private fun playerTest(
        body: suspend TestScope.(AudioPlayerManager, RecordingPlayerFactory) -> Unit
    ) = runTest(dispatcher) {
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(context, repository, factory)
        try {
            body(manager, factory)
        } finally {
            manager.release()
        }
    }

    private companion object {
        /** Fixture book that is not marked downloaded, so playback streams. */
        const val STREAMING_BOOK_INDEX = 1
        const val MILLIS_PER_SECOND = 1_000L
        const val FASTER_SPEED = 1.5f
        const val SPEED_TOLERANCE = 0.001f
    }
}
