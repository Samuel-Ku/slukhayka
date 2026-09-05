package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Spec-22 T5: sleep-timer boundary and math tests.
 *
 * The CountDownTimer itself ticks on the real main looper, so these tests
 * pin the *math* that decides the timer's behaviour — the fade-volume curve,
 * the end-of-chapter remaining-time computation (what makes the stop land on
 * the chapter boundary), the re-arm on chapter switches, and the
 * shake-threshold decision — rather than waiting for wall-clock ticks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerTest {

    private lateinit var context: Context
    private lateinit var listeningState: ListeningStateStore
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val book: AudiobookEntity = TestDataFactory.dataBooks()[0]
    private val chapters: List<ChapterEntity> = TestDataFactory.chaptersFor(book)

    private lateinit var playerManager: AudioPlayerManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listeningState = ListeningStateStore(FakeAudiobookDao())
        val fakeEngine = FakePlayerEngine()
        playerManager = AudioPlayerManager(
            context = context,
            listeningState = listeningState,
            chapterFetcher = { emptyList() },
            injectedPlayerFactory = { fakeEngine },
            widgetSyncEnabled = false
        )
    }

    // ------------------------------------------------------------------
    // Mode selection
    // ------------------------------------------------------------------

    @Test
    fun `positive minutes arms a plain timer`() = testScope.runTest {
        playerManager.setSleepTimer(15)
        val state = playerManager.playerState.value
        assertEquals(15, state.sleepTimerMinutes)
        assertEquals(15 * 60, state.sleepTimerRemainingSeconds)
        assertFalse(state.isSleepTimerEndOfChapter)
    }

    @Test
    fun `cancel clears the timer and any end-of-chapter mode`() = testScope.runTest {
        playerManager.setSleepTimer(-1)
        assertTrue(playerManager.playerState.value.isSleepTimerEndOfChapter)

        playerManager.setSleepTimer(0)
        val state = playerManager.playerState.value
        assertEquals(0, state.sleepTimerMinutes)
        assertEquals(0, state.sleepTimerRemainingSeconds)
        assertFalse(state.isSleepTimerEndOfChapter)
    }

    @Test
    fun `canonical extension adds exactly fifteen minutes to the honest remainder`() = testScope.runTest {
        playerManager.setSleepTimer(5)

        val result = playerManager.extendSleepTimerBy15Minutes()
        val state = playerManager.playerState.value

        assertEquals(1_200, result)
        assertEquals(1_200, state.sleepTimerRemainingSeconds)
        assertEquals(20, state.sleepTimerMinutes)
        assertFalse(state.isSleepTimerEndOfChapter)
    }

    @Test
    fun `shake path invokes the same extension command as the visible path`() = testScope.runTest {
        playerManager.setSleepTimer(5)
        val visibleResult = playerManager.extendSleepTimerBy15Minutes()

        playerManager.setSleepTimer(5)
        val shakeResult = playerManager.handleSleepTimerShake()

        assertEquals(visibleResult, shakeResult)
        assertEquals(1_200, playerManager.playerState.value.sleepTimerRemainingSeconds)
    }

    @Test
    fun `extension converts end-of-chapter mode into an exact timed remainder`() = testScope.runTest {
        playerManager.loadAndPlayBook(
            book = book,
            chapters = chapters,
            initialChapterIndex = 0,
            initialPositionSeconds = 300,
            autoPlay = false
        )
        playerManager.setSleepTimer(-1)
        val before = playerManager.playerState.value.sleepTimerRemainingSeconds

        val result = playerManager.extendSleepTimerBy15Minutes()

        assertEquals(before + 900, result)
        assertEquals(before + 900, playerManager.playerState.value.sleepTimerRemainingSeconds)
        assertFalse(playerManager.playerState.value.isSleepTimerEndOfChapter)
    }

    @Test
    fun `inactive timer cannot be extended`() = testScope.runTest {
        assertEquals(0, playerManager.extendSleepTimerBy15Minutes())
        assertEquals(0, playerManager.playerState.value.sleepTimerRemainingSeconds)
    }

    @Test
    fun `extension emits one announcement with the exact new remainder`() = testScope.runTest {
        val notices = mutableListOf<SleepTimerNotice>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            playerManager.sleepTimerNotices.collect(notices::add)
        }
        try {
            playerManager.setSleepTimer(5)
            playerManager.extendSleepTimerBy15Minutes()
            runCurrent()

            assertEquals(listOf(SleepTimerNotice.Extended(1_200)), notices)
        } finally {
            collection.cancel()
        }
    }

    @Test
    fun `fade window emits its warning only once`() = testScope.runTest {
        val notices = mutableListOf<SleepTimerNotice>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            playerManager.sleepTimerNotices.collect(notices::add)
        }
        try {
            val shortChapter = chapters.first().copy(durationSeconds = 31)
            playerManager.loadAndPlayBook(
                book = book,
                chapters = listOf(shortChapter),
                initialChapterIndex = 0,
                autoPlay = false
            )
            playerManager.setSleepTimer(-1)

            ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
            runCurrent()

            assertEquals(listOf(SleepTimerNotice.FadeWarning), notices)
        } finally {
            collection.cancel()
        }
    }

    @Test
    fun `end-of-chapter mode computes remaining from the current chapter`() = testScope.runTest {
        playerManager.loadAndPlayBook(
            book = book,
            chapters = chapters,
            initialChapterIndex = 0,
            initialPositionSeconds = 300L, // 5 minutes in
            autoPlay = false
        )
        playerManager.setSleepTimer(-1)

        val state = playerManager.playerState.value
        val expectedRemaining = chapters[0].durationSeconds - 300L
        assertEquals(-1, state.sleepTimerMinutes)
        assertTrue(state.isSleepTimerEndOfChapter)
        assertEquals(expectedRemaining, state.sleepTimerRemainingSeconds.toLong())
    }

    @Test
    fun `end-of-chapter re-arms to the new chapter on a manual switch`() = testScope.runTest {
        playerManager.loadAndPlayBook(
            book = book,
            chapters = chapters,
            playable = chapters.zip(TestDataFactory.tracksFor(book, "timer-fixture")) { chapter, track ->
                com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter(chapter, track)
            },
            initialChapterIndex = 0,
            autoPlay = false
        )
        playerManager.setSleepTimer(-1)
        assertEquals(chapters[0].durationSeconds.toLong(), playerManager.playerState.value.sleepTimerRemainingSeconds.toLong())

        playerManager.prepareChapter(1, startPositionMs = 0L, autoPlay = false)

        val state = playerManager.playerState.value
        assertTrue(state.isSleepTimerEndOfChapter)
        assertEquals(chapters[1].durationSeconds.toLong(), state.sleepTimerRemainingSeconds.toLong())
    }

    @Test
    fun `end-of-chapter remaining never drops below one second`() = testScope.runTest {
        playerManager.loadAndPlayBook(
            book = book,
            chapters = chapters,
            initialChapterIndex = 0,
            initialPositionSeconds = chapters[0].durationSeconds - 1L, // 1s from the end
            autoPlay = false
        )
        playerManager.setSleepTimer(-1)
        assertEquals(1L, playerManager.playerState.value.sleepTimerRemainingSeconds.toLong())
    }

    @Test
    fun `loading a new book clears the active sleep timer`() = testScope.runTest {
        playerManager.loadAndPlayBook(
            book = book,
            chapters = chapters,
            initialChapterIndex = 0,
            autoPlay = false
        )
        playerManager.setSleepTimer(30)
        assertTrue(playerManager.playerState.value.sleepTimerMinutes > 0)

        val other = TestDataFactory.dataBooks()[1]
        playerManager.loadAndPlayBook(
            book = other,
            chapters = TestDataFactory.chaptersFor(other),
            initialChapterIndex = 0,
            autoPlay = false
        )

        val state = playerManager.playerState.value
        assertEquals(0, state.sleepTimerMinutes)
        assertEquals(0, state.sleepTimerRemainingSeconds)
        assertFalse(state.isSleepTimerEndOfChapter)
    }

    // ------------------------------------------------------------------
    // Fade-volume math (pure function)
    // ------------------------------------------------------------------

    @Test
    fun `fade volume is 1_0 outside the 30s window`() {
        assertEquals(1.0f, sleepTimerFadeVolume(31), 0.0001f)
        assertEquals(1.0f, sleepTimerFadeVolume(0), 0.0001f)
        assertEquals(1.0f, sleepTimerFadeVolume(300), 0.0001f)
    }

    @Test
    fun `fade volume decreases linearly across the last 30s`() {
        assertEquals(1.0f, sleepTimerFadeVolume(30), 0.0001f)
        assertEquals(0.5f, sleepTimerFadeVolume(15), 0.0001f)
        assertEquals(1.0f / 3.0f, sleepTimerFadeVolume(10), 0.0001f)
        assertEquals(1.0f / 30.0f, sleepTimerFadeVolume(1), 0.0001f)
    }

    // ------------------------------------------------------------------
    // Shake-threshold math (pure function)
    // ------------------------------------------------------------------

    @Test
    fun `resting accelerometer does not count as a shake`() {
        assertFalse(shakeExceedsThreshold(0f, 0f, 0f))
        // Gravity alone (~9.81 m/s² on z) is ~1.0 g, well under 1.4 g.
        assertFalse(shakeExceedsThreshold(0f, 0f, 9.81f))
    }

    @Test
    fun `hard shake crosses the threshold`() {
        assertTrue(shakeExceedsThreshold(0f, 0f, 25f))
        assertTrue(shakeExceedsThreshold(0f, 20f, 0f))
        assertTrue(shakeExceedsThreshold(-22f, 0f, 0f))
    }

    @Test
    fun `exactly at the threshold does not cross it`() {
        // Net acceleration of exactly threshold m/s² is not *greater* than it.
        assertFalse(shakeExceedsThreshold(0f, 0f, 13.5f))
        assertTrue(shakeExceedsThreshold(0f, 0f, 13.51f))
    }

    @Test
    fun `combined axis shake crosses the threshold`() {
        // sqrt(9² + 9² + 9²) ≈ 15.6 m/s² > 13.5 — a moderate 3-axis shake.
        assertTrue(shakeExceedsThreshold(9f, 9f, 9f))
        // sqrt(7² + 7² + 7²) ≈ 12.1 m/s² < 13.5 — a weak one does not.
        assertFalse(shakeExceedsThreshold(7f, 7f, 7f))
    }

    // ------------------------------------------------------------------
    // ShakeDetector lifecycle (Robolectric has no accelerometer by default)
    // ------------------------------------------------------------------

    @Test
    fun `shake detector is a no-op without an accelerometer`() {
        var shakes = 0
        val detector = ShakeDetector(context, onShake = { shakes++ })
        detector.startListening() // no sensor -> no registration, no crash
        detector.stopListening()
        assertEquals(0, shakes)
    }

    @Test
    fun `fade curve is monotonic over its window`() {
        // Volumes strictly decrease as the remaining seconds shrink.
        var previous = 1.0f
        for (sec in 30 downTo 1) {
            val vol = sleepTimerFadeVolume(sec)
            assertTrue(vol <= previous + 0.0001f)
            assertTrue(abs(vol - previous) > 0.0f || sec == 30)
            previous = vol
        }
    }
}
