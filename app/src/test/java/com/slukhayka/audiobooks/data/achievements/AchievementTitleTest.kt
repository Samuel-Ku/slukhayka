package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #704 (T6) — the title ladder, and the rule that shapes it.
 *
 * The spec asks for «титул, що зростає за СИНЕРГІЮ годин, книг, серій і
 * курації» and forbids a component for «час у застосунку». The formula is a
 * MINIMUM across the four, so these tests pin both halves: every rung needs all
 * four ingredients, and piling on one ingredient buys nothing.
 */
class AchievementTitleTest {

    private val HOUR = 3_600_000L

    @Test
    fun `a listener who has done nothing is simply a listener`() {
        assertEquals(ListenerTitle.LISTENER, AchievementTitle.of(AchievementProgress()))
    }

    /**
     * THE rule: 10 000 hours with no books, no series and no reviews must not
     * lift the title. A sum-based ladder would hand out the top rung here —
     * which is exactly the "time spent" measure the spec rules out.
     */
    @Test
    fun `hours alone cannot buy a higher title`() {
        val onlyHours = AchievementProgress(verifiedListeningMillis = 10_000 * HOUR)

        assertEquals(ListenerTitle.LISTENER, AchievementTitle.of(onlyHours))
    }

    /** And the mirror: everything BUT hours is also stuck at the first rung. */
    @Test
    fun `every other ingredient alone cannot buy a higher title either`() {
        val noHours = AchievementProgress(
            completedBooks = 5_000, seriesInLibrary = 500, acceptedReviews = 500
        )

        assertEquals(ListenerTitle.LISTENER, AchievementTitle.of(noHours))
    }

    /** The weakest component decides — three at rung 1 and one at rung 0 is rung 0. */
    @Test
    fun `the weakest ingredient decides the rung`() {
        val threeAtOne = AchievementProgress(
            verifiedListeningMillis = 10 * HOUR, completedBooks = 5, seriesInLibrary = 2,
            acceptedReviews = 0
        )

        assertEquals(ListenerTitle.LISTENER, AchievementTitle.of(threeAtOne))
    }

    /** All four at rung 1 gives exactly «Мандрівник сторінками», not more. */
    @Test
    fun `all four at the first rung gives the second title`() {
        val allOne = AchievementProgress(
            verifiedListeningMillis = 10 * HOUR, completedBooks = 5, seriesInLibrary = 2,
            acceptedReviews = 1
        )

        assertEquals(ListenerTitle.PAGE_TRAVELLER, AchievementTitle.of(allOne))
    }

    /**
     * Each rung is reached EXACTLY at its threshold, checked on all four
     * ingredients at once — an off-by-one in any single ladder would show here.
     */
    @Test
    fun `every rung opens at exactly its thresholds`() {
        val rungs = listOf(
            Triple(10L, 5L, 2L) to 1,
            Triple(50L, 25L, 5L) to 2,
            Triple(200L, 100L, 15L) to 3,
            Triple(500L, 250L, 40L) to 4
        )
        val reviews = listOf(1L, 5L, 20L, 50L)

        for ((index, pair) in rungs.withIndex()) {
            val (hours, books, series) = pair.first
            val (_, expectedRung) = pair
            val snapshot = AchievementProgress(
                verifiedListeningMillis = hours * HOUR, completedBooks = books,
                seriesInLibrary = series, acceptedReviews = reviews[index]
            )
            assertEquals(
                "поріг $expectedRung мусить відкриватись рівно на своїх значеннях",
                ListenerTitle.entries[expectedRung],
                AchievementTitle.of(snapshot)
            )

            // One book short of the rung must fall back to the previous one.
            val short = snapshot.copy(completedBooks = books - 1)
            assertEquals(
                "на одну книгу менше — попередній титул",
                ListenerTitle.entries[expectedRung - 1],
                AchievementTitle.of(short)
            )
        }
    }

    /** The top rung is reachable and is the ceiling. */
    @Test
    fun `the ladder ends at the voice of the library`() {
        val top = AchievementProgress(
            verifiedListeningMillis = 5_000 * HOUR, completedBooks = 5_000,
            seriesInLibrary = 500, acceptedReviews = 500
        )

        assertEquals(ListenerTitle.LIBRARY_VOICE, AchievementTitle.of(top))
    }
}
