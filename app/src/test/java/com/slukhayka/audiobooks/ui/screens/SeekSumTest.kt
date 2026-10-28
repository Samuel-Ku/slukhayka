package com.slukhayka.audiobooks.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1474 — the tap-series sum: honest accumulation, reset rules, display. */
class SeekSumTest {

    private val idle = SeekSum.SERIES_IDLE_MS

    @Test
    fun `a first tap starts the series with its actual shift`() {
        assertEquals(
            30_000L,
            SeekSum.accumulate(
                previousSumMs = 0L,
                lastTapAtMs = 0L,
                previousChapterIndex = -1,
                nowMs = 1_000L,
                chapterIndex = 3,
                actualDeltaMs = 30_000L
            )
        )
    }

    @Test
    fun `taps within the idle window accumulate`() {
        assertEquals(
            90_000L,
            SeekSum.accumulate(
                previousSumMs = 60_000L,
                lastTapAtMs = 1_000L,
                previousChapterIndex = 3,
                nowMs = 1_000L + idle,
                chapterIndex = 3,
                actualDeltaMs = 30_000L
            )
        )
    }

    @Test
    fun `a tap after the idle window starts a new series`() {
        assertEquals(
            30_000L,
            SeekSum.accumulate(
                previousSumMs = 60_000L,
                lastTapAtMs = 1_000L,
                previousChapterIndex = 3,
                nowMs = 2_000L + idle,
                chapterIndex = 3,
                actualDeltaMs = 30_000L
            )
        )
    }

    @Test
    fun `a tap on another chapter starts a new series`() {
        assertEquals(
            -15_000L,
            SeekSum.accumulate(
                previousSumMs = 60_000L,
                lastTapAtMs = 1_000L,
                previousChapterIndex = 3,
                nowMs = 1_500L,
                chapterIndex = 4,
                actualDeltaMs = -15_000L
            )
        )
    }

    @Test
    fun `a clamped tap adds only what really moved`() {
        // At the very start of a chapter a «−15 s» tap really shifts 0 s.
        assertEquals(
            0L,
            SeekSum.accumulate(
                previousSumMs = 0L,
                lastTapAtMs = 0L,
                previousChapterIndex = -1,
                nowMs = 1_000L,
                chapterIndex = 2,
                actualDeltaMs = 0L
            )
        )
        // And back-to-back mixed taps keep the honest running sum.
        assertEquals(
            45_000L,
            SeekSum.accumulate(
                previousSumMs = 60_000L,
                lastTapAtMs = 1_000L,
                previousChapterIndex = 2,
                nowMs = 2_000L,
                chapterIndex = 2,
                actualDeltaMs = -15_000L
            )
        )
    }

    @Test
    fun `the display is minutes and seconds with an honest sign`() {
        assertEquals("+1:30", SeekSum.format(90_000L))
        assertEquals("\u22122:00", SeekSum.format(-120_000L))
        assertEquals("+0:05", SeekSum.format(5_000L))
        assertEquals("+0:00", SeekSum.format(0L))
        assertEquals("+12:05", SeekSum.format(725_000L))
    }
}
