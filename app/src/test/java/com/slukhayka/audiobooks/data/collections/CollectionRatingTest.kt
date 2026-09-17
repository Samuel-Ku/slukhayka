package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec-51 (#694) — the pure rating arithmetic behind every collection card. */
class CollectionRatingTest {

    @Test
    fun `no votes means no average at all`() {
        assertNull(CollectionRating.average(0, 0))
        assertNull("a count without a sum is impossible", CollectionRating.average(9, 0))
    }

    @Test
    fun `a real average is the flat mean of real votes`() {
        assertEquals(4.5, CollectionRating.average(9, 2)!!, 0.0001)
        assertEquals(5.0, CollectionRating.average(5, 1)!!, 0.0001)
    }

    @Test
    fun `an impossible aggregate is not a number`() {
        assertNull(CollectionRating.average(0, 3))
        assertNull(CollectionRating.average(-4, 3))
    }

    @Test
    fun `a first vote adds one person`() {
        assertEquals(4 to 1, CollectionRating.applyVote(sum = 0, count = 0, previousStars = null, newStars = 4))
    }

    @Test
    fun `a re-vote replaces the previous stars, never adds a second person`() {
        // Two people voted 4 and 2 (sum 6); the 2-star voter changes to 4.
        val (sum, count) = CollectionRating.applyVote(sum = 6, count = 2, previousStars = 2, newStars = 4)
        assertEquals(8, sum)
        assertEquals(2, count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stars outside the range are refused`() {
        CollectionRating.applyVote(sum = 0, count = 0, previousStars = null, newStars = 6)
    }
}
