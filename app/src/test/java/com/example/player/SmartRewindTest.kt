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
}
