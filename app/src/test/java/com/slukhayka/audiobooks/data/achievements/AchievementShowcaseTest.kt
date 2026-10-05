package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #704 (T6) — «вітрина до 3 pinned id».
 *
 * The limit is a rule, so it is tested as one, without a database in the way.
 */
class AchievementShowcaseTest {

    @Test
    fun `an empty showcase takes the first pin`() {
        assertEquals(listOf("a"), AchievementShowcase.afterPin(emptyList(), "a"))
    }

    @Test
    fun `pins keep their order, oldest first`() {
        assertEquals(listOf("a", "b"), AchievementShowcase.afterPin(listOf("a"), "b"))
    }

    /** A FOURTH pin drops the OLDEST — the listener's newest choice survives. */
    @Test
    fun `a fourth pin evicts the oldest, not an arbitrary one`() {
        val after = AchievementShowcase.afterPin(listOf("a", "b", "c"), "d")

        assertEquals(listOf("b", "c", "d"), after)
        assertEquals(AchievementShowcase.MAX_PINNED, after.size)
    }

    /** Pinning something already there MOVES it, it does not duplicate it. */
    @Test
    fun `re-pinning an award moves it to the newest position`() {
        assertEquals(listOf("b", "c", "a"), AchievementShowcase.afterPin(listOf("a", "b", "c"), "a"))
    }

    @Test
    fun `the showcase never grows past three`() {
        var current = emptyList<String>()
        for ((index, id) in listOf("a", "b", "c", "d", "e", "f").withIndex()) {
            current = AchievementShowcase.afterPin(current, id)
            // It GROWS to three, then stops: asserting == 3 on the first pin
            // would be asserting the wrong thing.
            assertEquals(
                "після піна №${index + 1} розмір — мінімум із трьох",
                minOf(index + 1, AchievementShowcase.MAX_PINNED),
                current.size
            )
        }
        assertEquals(listOf("d", "e", "f"), current)
    }

    @Test
    fun `unpinning removes only that award`() {
        assertEquals(listOf("a", "c"), AchievementShowcase.afterUnpin(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `unpinning something absent changes nothing`() {
        assertEquals(listOf("a", "b"), AchievementShowcase.afterUnpin(listOf("a", "b"), "zzz"))
    }
}
