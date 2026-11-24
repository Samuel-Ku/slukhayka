package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1750 / #1752 — the cast/player hand-off seam.
 *
 * There is no Cast device in CI and [CastPlaybackController] still owns the
 * Google Cast types, so these tests drive the manager side of the seam:
 * [AudioPlayerManager.activateCastTakeover] is the exact call the controller
 * makes in `beginCasting`, and [AudioPlayerManager.finishCastHandback] is the
 * exact call it makes in `endCasting`. The fake [CastEngineHook] and
 * [FakePlayerEngine] replace the receiver and ExoPlayer respectively.
 *
 * This proves the local-engine invariants, not a live receiver session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CastTakeoverSeamTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cast activation stops the local engine and late READY ENDED cannot revive or advance it`() =
        runTest(dispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val book = TestDataFactory.dataBooks().first()
            val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }
            val playable = playable(book, chapters)
            val factory = RecordingPlayerFactory()
            val manager = AudioPlayerManager(
                context,
                ListeningStateStore(
                    FakeAudiobookDao(TestDataFactory.dataBooks(), TestDataFactory.dataChapters()),
                    dispatcher
                ),
                { playable },
                injectedPlayerFactory = factory,
                ioDispatcher = dispatcher,
                widgetSyncEnabled = false
            )
            try {
                manager.loadAndPlayBook(book, chapters, playable, autoPlay = true)
                runCurrent()
                val engine = factory.current
                engine.simulateReady(chapters.first().durationSeconds * 1000L)
                runCurrent()
                assertTrue("precondition: the phone engine is really playing", engine.isPlaying)

                val receiver = FakeCastHook()
                manager.attachCastHook(receiver)
                manager.activateCastTakeover()

                assertFalse("the takeover must pause the local engine", engine.isPlaying)
                assertFalse("the phone must not claim playback after takeover", manager.playerState.value.isPlaying)
                val playsBefore = engine.playCount

                // A stale local READY arrives after the CastPlayer became active.
                engine.simulateReady(chapters.first().durationSeconds * 1000L)
                runCurrent()
                assertFalse("a late READY must not resurrect the local engine", engine.isPlaying)
                assertEquals("a late READY must not call play()", playsBefore, engine.playCount)

                // A stale local ENDED must not advance the shared chapter index.
                val chapterBefore = manager.playerState.value.currentChapterIndex
                engine.simulateEnded()
                runCurrent()
                assertEquals(
                    "a late local ENDED must not advance the chapter",
                    chapterBefore,
                    manager.playerState.value.currentChapterIndex
                )
                assertEquals("the receiver received no local advance", 0, receiver.prepareCalls)
            } finally {
                manager.release()
            }
        }

    @Test
    fun `progress tracker ignores the local engine while the receiver is active`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val book = TestDataFactory.dataBooks().first()
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }
        val playable = playable(book, chapters)
        val dao = FakeAudiobookDao(TestDataFactory.dataBooks(), TestDataFactory.dataChapters())
        val listening = ListeningStateStore(dao, dispatcher)
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context,
            listening,
            { playable },
            injectedPlayerFactory = factory,
            ioDispatcher = dispatcher,
            widgetSyncEnabled = false
        )
        try {
            manager.loadAndPlayBook(book, chapters, playable, autoPlay = false)
            runCurrent()
            val engine = factory.current
            engine.simulateReady(chapters.first().durationSeconds * 1000L)
            runCurrent()

            manager.attachCastHook(FakeCastHook())
            manager.activateCastTakeover()
            manager.mirrorCastState {
                it.copy(
                    isPlaying = true,
                    isBuffering = false,
                    currentPositionMs = 5_000L,
                    durationMs = chapters.first().durationSeconds * 1000L
                )
            }

            // The receiver says 5 s; a stale/local engine claims 125 s. A
            // tracker that reads the local engine would persist 125 s.
            engine.simulatePlayback(125_000L)
            advanceTimeBy(1_001L)
            runCurrent()

            assertEquals(5_000L, manager.playerState.value.currentPositionMs)
            assertEquals(
                "the database must follow the receiver mirror, not the stopped local engine",
                5L,
                listening.getProgressSync(book.id)?.currentPositionSeconds
            )
        } finally {
            manager.release()
        }
    }

    @Test
    fun `cast hand-back outcomes are distinguishable and survive the local prepare`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val book = TestDataFactory.dataBooks().first()
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }
        val playable = playable(book, chapters)
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context,
            ListeningStateStore(
                FakeAudiobookDao(TestDataFactory.dataBooks(), TestDataFactory.dataChapters()),
                dispatcher
            ),
            { playable },
            injectedPlayerFactory = factory,
            ioDispatcher = dispatcher,
            widgetSyncEnabled = false
        )
        try {
            manager.loadAndPlayBook(book, chapters, playable, autoPlay = false)
            runCurrent()
            manager.mirrorCastState {
                it.copy(lastErrorMsg = "stale cast failure", errorKind = PlaybackErrorKind.TRANSIENT)
            }

            manager.finishCastHandback(CastSessionOutcome.LOST, 1, 42_000L, 1.25f)
            runCurrent()
            val lost = manager.playerState.value
            assertEquals(CastSessionOutcome.LOST, lost.castSessionOutcome)
            assertEquals(
                "prepareChapter clears lastErrorMsg; the cast outcome must be written after it",
                "",
                lost.lastErrorMsg
            )
            assertEquals(1, lost.currentChapterIndex)
            assertEquals(42_000L, lost.currentPositionMs)

            manager.finishCastHandback(CastSessionOutcome.ENDED, 0, 0L, 1.0f)
            runCurrent()
            val ended = manager.playerState.value
            assertEquals(CastSessionOutcome.ENDED, ended.castSessionOutcome)
            assertNotEquals(
                "a deliberate end must not read as a loss",
                CastSessionOutcome.LOST,
                ended.castSessionOutcome
            )

            manager.play()
            assertNull("resuming is the listener acknowledging the hand-back", manager.playerState.value.castSessionOutcome)
        } finally {
            manager.release()
        }
    }

    @Test
    fun `cast session error code separates deliberate disconnect from loss`() {
        assertEquals(CastSessionOutcome.ENDED, castSessionOutcomeFor(0))
        assertEquals(CastSessionOutcome.LOST, castSessionOutcomeFor(13))
    }

    private fun playable(book: com.slukhayka.audiobooks.data.db.AudiobookEntity,
        chapters: List<com.slukhayka.audiobooks.data.db.ChapterEntity>): List<SourceCatalog.PlayableChapter> {
        val tracks = TestDataFactory.tracksFor(book, "4read")
        return chapters.mapIndexed { index, chapter ->
            SourceCatalog.PlayableChapter(chapter, tracks[index])
        }
    }

    private class FakeCastHook : CastEngineHook {
        override val isActive: Boolean = true
        var prepareCalls: Int = 0
            private set

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun prepareChapter(chapterIndex: Int, startPositionMs: Long, autoPlay: Boolean) {
            prepareCalls++
        }
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
    }
}
