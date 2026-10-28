package com.slukhayka.audiobooks.ui.screens

import kotlin.math.abs

/**
 * #1474 — the temporary «sum of one tap series» shown above the transport row.
 *
 * Pure rules, so the boundary honesty is unit-testable without Compose:
 *
 * - a series is a run of taps inside [SERIES_IDLE_MS] on the SAME chapter;
 *   the first tap after the pause (or on another chapter) starts a new sum;
 * - the sum is built from the ACTUAL position change of each tap (already
 *   clamped by the chapter start/end), so «+2:00» never promises more than the
 *   listener really shifted;
 * - the caller resets the series on a drag/chapter switch (the sum is about
 *   taps, not about a seek bar).
 */
object SeekSum {

    /** After this pause the next tap starts a new series. */
    const val SERIES_IDLE_MS = 2_500L

    /** The indicator disappears this long after the last tap. */
    const val VISIBLE_MS = 1_800L

    /**
     * The new sum for one tap.
     *
     * @param actualDeltaMs the real position shift of that tap (clamp applied)
     */
    fun accumulate(
        previousSumMs: Long,
        lastTapAtMs: Long,
        previousChapterIndex: Int,
        nowMs: Long,
        chapterIndex: Int,
        actualDeltaMs: Long
    ): Long {
        val sameSeries = lastTapAtMs > 0L &&
            nowMs - lastTapAtMs <= SERIES_IDLE_MS &&
            chapterIndex == previousChapterIndex
        return if (sameSeries) previousSumMs + actualDeltaMs else actualDeltaMs
    }

    /** «+1:30» / «−2:00» — minutes and seconds, the listener's own units. */
    fun format(sumMs: Long): String {
        val sign = if (sumMs < 0L) "\u2212" else "+"
        val totalSeconds = abs(sumMs) / 1_000L
        return "$sign${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }
}
