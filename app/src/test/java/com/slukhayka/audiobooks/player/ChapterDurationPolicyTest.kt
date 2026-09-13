package com.slukhayka.audiobooks.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #528 — a short interstitial must never overwrite a known duration. */
class ChapterDurationPolicyTest {

    @Test
    fun `a real measurement is accepted`() {
        assertTrue(ChapterDurationPolicy.shouldAccept(knownSeconds = 1701L, measuredSeconds = 1700L))
        assertTrue(ChapterDurationPolicy.shouldAccept(knownSeconds = 1701L, measuredSeconds = 1710L))
    }

    @Test
    fun `the 52-second interstitial is refused when the chapter is known`() {
        // The observed case: a 28-minute chapter, a 52-second stream read from it.
        assertFalse(ChapterDurationPolicy.shouldAccept(knownSeconds = 1701L, measuredSeconds = 52L))
        assertFalse(ChapterDurationPolicy.shouldAccept(knownSeconds = 1878L, measuredSeconds = 52L))
    }

    @Test
    fun `an unknown chapter accepts the first real measurement`() {
        assertTrue(ChapterDurationPolicy.shouldAccept(knownSeconds = 0L, measuredSeconds = 52L))
        assertTrue(ChapterDurationPolicy.shouldAccept(knownSeconds = 0L, measuredSeconds = 1701L))
    }

    @Test
    fun `nonsense measurements are refused`() {
        assertFalse(ChapterDurationPolicy.shouldAccept(knownSeconds = 1701L, measuredSeconds = 0L))
        assertFalse(ChapterDurationPolicy.shouldAccept(knownSeconds = 1701L, measuredSeconds = -5L))
    }
}
