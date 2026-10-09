package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #704 (T6) / #703 (T5) — the screen's rule, tested as data.
 *
 * The spec is explicit twice: «приховані не розкриваються до здобуття» (T6) and
 * «приховані нагороди не показуються в списку майбутніх до здобуття» (T5). A
 * hidden award that leaks into the list would spoil it, so that is the case
 * these tests exist for.
 */
class AchievementBoardTest {

    private val visible = AchievementDefinition("plain", "g", 1, AchievementMetric.COMPLETED_BOOKS, 1)
    private val secret = AchievementDefinition("secret", "g", 1, AchievementMetric.COMPLETED_BOOKS, 2, hidden = true)

    @Test
    fun `nothing earned shows the visible ladder and withholds the hidden one`() {
        val board = AchievementBoard.of(listOf(visible, secret), emptySet())

        assertEquals(listOf("plain"), board.upcoming.map { it.id })
        assertTrue("нічого не здобуто", board.earned.isEmpty())
        assertFalse("приховане не має протікати у список майбутніх",
            board.upcoming.any { it.hidden })
    }

    /** Once earned, a hidden award is no longer a secret — it shows like any other. */
    @Test
    fun `an earned hidden award appears among the earned`() {
        val board = AchievementBoard.of(listOf(visible, secret), setOf("secret"))

        assertEquals(listOf("secret"), board.earned.map { it.id })
        assertEquals(listOf("plain"), board.upcoming.map { it.id })
    }

    @Test
    fun `an earned visible award leaves the upcoming list`() {
        val board = AchievementBoard.of(listOf(visible, secret), setOf("plain"))

        assertEquals(listOf("plain"), board.earned.map { it.id })
        assertTrue("у майбутніх не лишається нічого видимого", board.upcoming.isEmpty())
    }

    /**
     * The REAL catalogue, not a two-item fixture: every hidden award it defines
     * must stay out of `upcoming`, and every visible one must be in it.
     */
    @Test
    fun `the real catalogue withholds every hidden award`() {
        val board = AchievementBoard.of(AchievementCatalog.definitions, emptySet())

        assertTrue("жодне приховане не протікає",
            board.upcoming.none { it.hidden })
        assertEquals(
            "у майбутніх рівно стільки, скільки видимих у каталозі",
            AchievementCatalog.definitions.count { !it.hidden },
            board.upcoming.size
        )
        assertTrue("приховані в каталозі таки є — інакше тест нічого не перевіряє",
            AchievementCatalog.definitions.any { it.hidden })
    }

    @Test
    fun `an unknown earned id changes nothing`() {
        val board = AchievementBoard.of(listOf(visible), setOf("never_defined"))

        assertTrue(board.earned.isEmpty())
        assertEquals(listOf("plain"), board.upcoming.map { it.id })
    }

    @Test
    fun `an empty catalogue is an empty board`() {
        assertTrue(AchievementBoard.of(emptyList(), emptySet()).isEmpty)
    }
}
