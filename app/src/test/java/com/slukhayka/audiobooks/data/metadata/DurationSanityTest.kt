package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #528 — the acceptance rule for a SHARED duration.
 *
 * [DurationSanity.isPlausible] bounds a value only from above, so the
 * 52-second interstitial one listener measured for a 28-minute book passes it
 * and, through `mergeEditionFacet`'s COALESCE, replaces the true value for
 * everyone. [DurationSanity.mayReplace] is the missing half: a shared value
 * may FILL what we do not know, but may never collapse what we already hold.
 */
class DurationSanityTest {

    @Test
    fun `a shared ad-length value never replaces a measured duration`() {
        // The live case: «Про горобця» — 1701 s measured, 52 s offered.
        assertFalse(DurationSanity.mayReplace(local = 1_701L, shared = 52L))
    }

    @Test
    fun `a shared value fills an unknown local duration`() {
        assertTrue(DurationSanity.mayReplace(local = 0L, shared = 1_701L))
        assertTrue(DurationSanity.mayReplace(local = 0L, shared = 3_188L))
    }

    @Test
    fun `an interstitial is refused even into an unknown local duration`() {
        // 52 s cleared the old `> 0` and could become a book's only duration,
        // so the shared seam refuses it outright rather than only on collapse.
        assertFalse(DurationSanity.mayReplace(local = 0L, shared = 52L))
        assertFalse(DurationSanity.mayReplace(local = 0L, shared = DurationSanity.MIN_SHARED_SECONDS - 1))
        assertTrue(DurationSanity.mayReplace(local = 0L, shared = DurationSanity.MIN_SHARED_SECONDS))
    }

    @Test
    fun `a short local duration stays local truth`() {
        // The floor gates the shared seam only: a genuinely short local
        // recording is still plausible to us and is never published.
        assertTrue(DurationSanity.isPlausible(200L))
        assertFalse(DurationSanity.isShareable(200L))
    }

    @Test
    fun `a longer shared value is accepted`() {
        assertTrue(DurationSanity.mayReplace(local = 1_701L, shared = 3_188L))
        assertTrue(DurationSanity.mayReplace(local = 1_701L, shared = 1_701L))
    }

    @Test
    fun `a shared value at the shrink limit is still accepted`() {
        // Exactly half is the boundary of "a real duration this book could be".
        assertTrue(DurationSanity.mayReplace(local = 3_600L, shared = 1_800L))
        assertFalse(DurationSanity.mayReplace(local = 3_600L, shared = 1_799L))
    }

    @Test
    fun `an implausible shared value is refused at any local duration`() {
        assertFalse(DurationSanity.mayReplace(local = 0L, shared = 0L))
        assertFalse(DurationSanity.mayReplace(local = 0L, shared = -5L))
        assertFalse(
            DurationSanity.mayReplace(local = 1_701L, shared = DurationSanity.MAX_PLAUSIBLE_SECONDS + 1L)
        )
    }
}
