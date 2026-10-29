package com.slukhayka.audiobooks.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1573 — contract tests for the sleep-timer seam.
 *
 * The ticker and the shake source are manual fakes, so every behaviour is
 * driven by a tick we choose, not by wall-clock or a Robolectric looper:
 * fade values per second, finish effects (auto-bookmark, pause, TIMER_STOP,
 * volume restore), +15 min extension, end-of-chapter arming and its re-arm on
 * a manual chapter switch.
 */
class SleepTimerModuleTest {

    private class FakeTicker : SleepTicker {
        var totalMs: Long = -1L
        var running: Boolean = false
        private var onTick: (Int) -> Unit = {}
        private var onFinish: () -> Unit = {}

        override fun start(totalMs: Long, onTick: (Int) -> Unit, onFinish: () -> Unit) {
            this.totalMs = totalMs
            this.onTick = onTick
            this.onFinish = onFinish
            running = true
        }

        override fun cancel() {
            running = false
        }

        fun tick(remainingSec: Int) = onTick(remainingSec)
        fun finish() = onFinish()
    }

    private class FakeShake : ShakeSource {
        var started: Boolean = false
        var shake: () -> Unit = {}

        override fun start(onShake: () -> Unit) {
            started = true
            shake = onShake
        }

        override fun stop() {
            started = false
        }
    }

    private class FakeEffects : SleepTimerEffects {
        val states = mutableListOf<SleepTimerSnapshot>()
        val volumes = mutableListOf<Float>()
        val notices = mutableListOf<SleepTimerNotice>()
        val bookmarks = mutableListOf<SleepTimerStopContext>()
        val stops = mutableListOf<SleepTimerStopContext>()
        val events = mutableListOf<String>()
        var pauses: Int = 0
        var arms: Int = 0
        var context = SleepTimerStopContext(
            bookId = "book-1",
            chapterIndex = 2,
            chapterTitle = "Розділ 3",
            positionSeconds = 42L,
            chapterId = "chapter-3"
        )

        override fun onState(snapshot: SleepTimerSnapshot) {
            states += snapshot
            events += "state"
        }

        override fun setFadeVolume(volume: Float) {
            volumes += volume
            events += "fade:$volume"
        }

        override fun restoreVolume() {
            volumes += 1.0f
            events += "restore"
        }

        override fun restoreVolumeAfterFinish() {
            volumes += 1.0f
            events += "finish-restore"
        }

        override fun pause() {
            pauses++
            events += "pause"
        }

        override fun writeAutoBookmark(context: SleepTimerStopContext) {
            bookmarks += context
            events += "bookmark"
        }

        override fun recordTimerStop(context: SleepTimerStopContext) {
            stops += context
            events += "stop-event"
        }

        override fun emitNotice(notice: SleepTimerNotice) {
            notices += notice
        }

        override fun onEndOfChapterArmed() {
            arms++
        }

        override fun stopContext(): SleepTimerStopContext = context
    }

    private val ticker = FakeTicker()
    private val shake = FakeShake()
    private val effects = FakeEffects()
    private val module = SleepTimerModule(ticker, shake, effects)

    @After
    fun tearDown() {
        module.release()
    }

    // --- arming ------------------------------------------------------------

    @Test
    fun `positive minutes arm the countdown and publish the whole minutes`() {
        module.start(15)

        assertEquals(15 * 60_000L, ticker.totalMs)
        assertTrue(ticker.running)
        assertEquals(SleepTimerSnapshot(minutes = 15, remainingSeconds = 15 * 60), module.snapshot)
        assertEquals(module.snapshot, effects.states.last())
        assertTrue(effects.volumes.isEmpty())
        assertEquals(0, effects.arms)
    }

    @Test
    fun `end of chapter arms with the honest remainder and counts the arm once`() {
        module.startEndOfChapter(remainingMs = 300_000L)

        assertEquals(SleepTimerSnapshot(minutes = -1, remainingSeconds = 300, isEndOfChapter = true), module.snapshot)
        assertEquals(300_000L, ticker.totalMs)
        assertEquals(1, effects.arms)
    }

    // --- fade / warning / shake --------------------------------------------

    @Test
    fun `the 30-second fade window publishes volumes and warns exactly once`() {
        module.start(5)

        ticker.tick(31)
        assertTrue("outside the window the volume is untouched", effects.volumes.isEmpty())
        assertFalse(shake.started)

        ticker.tick(30)
        assertTrue("30 s is still full volume", effects.volumes.isEmpty())
        assertFalse(shake.started)

        ticker.tick(15)
        assertEquals(listOf(0.5f), effects.volumes)
        assertEquals(listOf<SleepTimerNotice>(SleepTimerNotice.FadeWarning), effects.notices)
        assertTrue("shake is armed inside the fade window", shake.started)

        ticker.tick(10)
        assertEquals(listOf(0.5f, 10f / 30f), effects.volumes)
        assertEquals("the fade warning is one-shot", 1, effects.notices.count { it is SleepTimerNotice.FadeWarning })
    }

    @Test
    fun `a shake inside the fade window extends by fifteen minutes and restores volume`() {
        module.start(5)
        ticker.tick(15)
        assertTrue(shake.started)

        shake.shake()

        // The canonical extension adds 15 min to the HONEST remainder (15 s
        // left at the shake), exactly like the visible +15 button.
        assertEquals(SleepTimerSnapshot(minutes = 16, remainingSeconds = 915), module.snapshot)
        assertEquals(915_000L, ticker.totalMs)
        assertEquals(listOf(0.5f, 1.0f), effects.volumes)
        assertTrue(effects.notices.contains(SleepTimerNotice.Extended(915)))
        assertFalse("extending leaves the fade window for now", shake.started)
    }

    @Test
    fun `arming another timer retires an already armed shake`() {
        module.start(5)
        ticker.tick(15)
        assertTrue(shake.started)

        module.start(10)

        assertFalse("a stale fade/shake must not survive a new arm", shake.started)
        assertEquals(10 * 60_000L, ticker.totalMs)
        assertEquals(SleepTimerSnapshot(minutes = 10, remainingSeconds = 600), module.snapshot)
    }

    // --- finish ------------------------------------------------------------

    @Test
    fun `finish writes the auto-bookmark, pauses, records TIMER_STOP and restores volume`() {
        module.start(5)
        ticker.tick(300)
        effects.events.clear()

        ticker.finish()

        assertEquals(listOf(effects.context), effects.bookmarks)
        assertEquals(1, effects.pauses)
        assertEquals(listOf(effects.context), effects.stops)
        assertEquals(1.0f, effects.volumes.last())
        assertEquals(SleepTimerSnapshot(), module.snapshot)
        assertFalse(shake.started)
        // The finish ORDER is the contract too: the bookmark names the moment,
        // then the pause, then the discrete TIMER_STOP, then volume and state.
        assertEquals(
            listOf("bookmark", "pause", "stop-event", "finish-restore", "state"),
            effects.events
        )
    }

    @Test
    fun `a finished timer cannot be extended`() {
        module.start(1)
        ticker.finish()

        assertEquals(0, module.extendBy15Minutes())
        assertEquals(SleepTimerSnapshot(), module.snapshot)
    }

    // --- extension ---------------------------------------------------------

    @Test
    fun `the canonical extension preserves the exact remainder`() {
        module.start(5)

        val extended = module.extendBy15Minutes()

        assertEquals(1_200, extended)
        assertEquals(SleepTimerSnapshot(minutes = 20, remainingSeconds = 1_200), module.snapshot)
        assertEquals(1_200_000L, ticker.totalMs)
        assertEquals(1.0f, effects.volumes.last())
        assertTrue(effects.notices.contains(SleepTimerNotice.Extended(1_200)))
    }

    // --- end-of-chapter re-arm ---------------------------------------------

    @Test
    fun `end of chapter re-arms on a chapter switch without counting a second arm`() {
        module.startEndOfChapter(remainingMs = 300_000L)

        module.onChapterSwitch(chapterDurationSeconds = 600L, startPositionMs = 0L)

        assertEquals(SleepTimerSnapshot(minutes = -1, remainingSeconds = 600, isEndOfChapter = true), module.snapshot)
        assertEquals(600_000L, ticker.totalMs)
        assertEquals("a re-arm is not a second request for the mode", 1, effects.arms)
    }

    @Test
    fun `a plain timed timer ignores chapter switches`() {
        module.start(10)
        val before = module.snapshot

        module.onChapterSwitch(chapterDurationSeconds = 600L, startPositionMs = 0L)

        assertEquals(before, module.snapshot)
        assertEquals(10 * 60_000L, ticker.totalMs)
    }

    // --- cancel / clear ----------------------------------------------------

    @Test
    fun `clear cancels the countdown, forgets the state and restores volume`() {
        module.start(5)
        assertTrue(ticker.running)

        module.clear()

        assertFalse(ticker.running)
        assertEquals(SleepTimerSnapshot(), module.snapshot)
        assertEquals(1.0f, effects.volumes.last())
    }

    @Test
    fun `cancel stops the countdown without touching the published state`() {
        module.start(5)
        val before = module.snapshot

        module.cancel()

        assertFalse(ticker.running)
        assertEquals(before, module.snapshot)
    }
}
