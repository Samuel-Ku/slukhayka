package com.slukhayka.audiobooks.data.reviews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-40 #279 — the pure combined-average rule, pinned in CI: formula,
 * honesty (a source without a rating is absent, empty is nothing, the count
 * is real) and edge cases. Prior art: SmartRewindTest, ResumeStartTest.
 */
class CombinedAverageTest {

    @Test
    fun `formula - flat arithmetic mean over sources and listeners`() {
        // Sources 4.0 + listeners 5 and 3 = (4+5+3)/3 — one vote each.
        val result = CombinedAverage.average(listOf(4.0), listOf(5, 3))

        assertEquals((4.0 + 5.0 + 3.0) / 3.0, result!!.value, 1e-12)
        assertEquals(3, result.count)
    }

    @Test
    fun `a source without a rating is absent - never a fabricated zero`() {
        val result = CombinedAverage.average(listOf(null, 4.0), listOf(4))

        // Only 4.0 and 4 take part; null contributes nothing to sum or count.
        assertEquals(4.0, result!!.value, 1e-12)
        assertEquals(2, result.count)
    }

    @Test
    fun `listeners only`() {
        val result = CombinedAverage.average(emptyList(), listOf(2, 4, 4, 5))

        assertEquals(15.0 / 4.0, result!!.value, 1e-12)
        assertEquals(4, result.count)
    }

    @Test
    fun `sources only`() {
        val result = CombinedAverage.average(listOf(4.9, 3.7), emptyList())

        assertEquals((4.9 + 3.7) / 2.0, result!!.value, 1e-12)
        assertEquals(2, result.count)
    }

    @Test
    fun `nobody rated - nothing, not zero`() {
        assertNull(CombinedAverage.average(emptyList(), emptyList()))
        // All-null sources are as absent as an empty list.
        assertNull(CombinedAverage.average(listOf(null, null), emptyList()))
    }

    @Test
    fun `count is honest - it matches the addends actually averaged`() {
        val result = CombinedAverage.average(listOf(null, 4.0, 2.0), listOf(5, 1))

        // 2 sources + 2 valid listeners = 4 votes, whatever the input sizes.
        assertEquals(4, result!!.count)
        assertEquals((4.0 + 2.0 + 5.0 + 1.0) / 4.0, result.value, 1e-12)
    }

    @Test
    fun `invalid listener ratings are skipped defensively`() {
        val result = CombinedAverage.average(emptyList(), listOf(0, 6, -1, 100, Int.MIN_VALUE, Int.MAX_VALUE, 4))

        assertEquals(4.0, result!!.value, 1e-12)
        assertEquals(1, result.count)

        // Only invalid listeners → no addends → nothing.
        assertNull(CombinedAverage.average(emptyList(), listOf(0, 6)))
    }

    @Test
    fun `the value stays raw - unrounded`() {
        val result = CombinedAverage.average(listOf(1.0, 2.0), emptyList())

        assertEquals(1.5, result!!.value, 1e-12)
        // A third that cannot round: (1+1+2)/3.
        val repeating = CombinedAverage.average(emptyList(), listOf(1, 1, 2))
        assertEquals(4.0 / 3.0, repeating!!.value, 1e-15)
        assertEquals(3, repeating.count)
    }
}
