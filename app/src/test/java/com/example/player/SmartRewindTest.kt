package com.example.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tier boundaries of the smart rewind (wayfinder #25). */
class SmartRewindTest {

    @Test
    fun `sub-second toggle rewinds nothing`() {
        assertEquals(0L, SmartRewind.computeRewindSeconds(500L))
        assertEquals(0L, SmartRewind.computeRewindSeconds(1_999L))
    }

    @Test
    fun `short pause rewinds a couple of seconds`() {
        assertEquals(SmartRewind.REWIND_SHORT_SECONDS, SmartRewind.computeRewindSeconds(2_000L))
        assertEquals(SmartRewind.REWIND_SHORT_SECONDS, SmartRewind.computeRewindSeconds(9 * 60 * 1000L))
    }

    @Test
    fun `a break of hours rewinds the medium amount`() {
        assertEquals(SmartRewind.REWIND_MEDIUM_SECONDS, SmartRewind.computeRewindSeconds(10 * 60 * 1000L))
        assertEquals(SmartRewind.REWIND_MEDIUM_SECONDS, SmartRewind.computeRewindSeconds(6 * 60 * 60 * 1000L))
    }

    @Test
    fun `an overnight gap rewinds the most`() {
        assertEquals(SmartRewind.REWIND_LONG_SECONDS, SmartRewind.computeRewindSeconds(24 * 60 * 60 * 1000L))
        assertEquals(SmartRewind.REWIND_LONG_SECONDS, SmartRewind.computeRewindSeconds(72 * 60 * 60 * 1000L))
    }

    @Test
    fun `negative or zero pause is treated as no pause`() {
        assertEquals(0L, SmartRewind.computeRewindSeconds(0L))
        assertEquals(0L, SmartRewind.computeRewindSeconds(-1L))
    }

    // --- ADR-0003: the ONE rewind rule (position + pause duration) --------

    /**
     * Table-driven boundary matrix of [SmartRewind.rewoundPositionMs]: every
     * case below pins the unified rule that serves BOTH resume paths
     * (in-session live engine and across-restart persisted Listening State).
     * A short pause leaves the position untouched; a position smaller than
     * the rewind rewinds to zero (clamp-at-zero — the former restart path
     * skipped the rewind there); a future-dated pause (clock skew) rewinds
     * nothing.
     */
    @Test
    fun `rewound position matrix`() {
        val cases = listOf(
            // short pause → no rewind, position untouched
            Triple(10_000L, 1_000L, 10_000L),
            Triple(120_000L, 1_999L, 120_000L),
            // short pause exactly at the boundary → 3 s rewind
            Triple(10_000L, 2_000L, 7_000L),
            // medium pause (hours) → 12 s rewind
            Triple(100_000L, 30 * 60 * 1000L, 100_000L - 12_000L),
            // long pause (overnight) → 25 s rewind
            Triple(200_000L, 25 * 60 * 60 * 1000L, 200_000L - 25_000L),
            // position smaller than the rewind → 0 (unified clamp-at-zero)
            Triple(2_000L, 60 * 60 * 1000L, 0L),
            Triple(0L, 60 * 60 * 1000L, 0L),
            // position exactly the rewind → 0
            Triple(12_000L, 30 * 60 * 1000L, 0L),
            // future-dated pause (negative duration — clock skew) → no rewind
            Triple(50_000L, -1_000L, 50_000L),
            Triple(50_000L, 0L, 50_000L)
        )
        cases.forEach { (positionMs, pauseMs, expected) ->
            assertEquals(
                "rewoundPositionMs(position=$positionMs, pause=$pauseMs)",
                expected,
                SmartRewind.rewoundPositionMs(positionMs, pauseMs)
            )
        }
    }
}
