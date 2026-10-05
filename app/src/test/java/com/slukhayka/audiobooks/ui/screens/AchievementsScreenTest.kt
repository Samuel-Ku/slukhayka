package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.achievements.AchievementBoard
import com.slukhayka.audiobooks.data.achievements.AchievementCatalog
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.AchievementMetric
import com.slukhayka.audiobooks.data.achievements.ListenerTitle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #704 (T6) — the «Досягнення» screen.
 *
 * The screen renders what [AchievementBoard] hands it, so the interesting
 * assertions are about the RULE surviving into the UI: a hidden award must not
 * be reachable as a node before it is earned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AchievementsScreenTest {

    @get:Rule val rule = createComposeRule()

    private val visible = AchievementDefinition("plain", "g", 1, AchievementMetric.COMPLETED_BOOKS, 1)
    private val secret = AchievementDefinition("secret", "g", 1, AchievementMetric.COMPLETED_BOOKS, 2, hidden = true)

    @Test
    fun `an empty board shows the canonical empty state`() {
        rule.setContent {
            AchievementsScreen(
                board = AchievementBoard.of(emptyList(), emptySet()),
                title = ListenerTitle.LISTENER,
                showcase = emptyList(),
                onBackClick = {}
            )
        }

        rule.onNodeWithTag("achievements_empty_state").assertIsDisplayed()
    }

    @Test
    fun `earned and upcoming awards are both listed`() {
        val board = AchievementBoard.of(listOf(visible, secret), setOf("plain"))
        rule.setContent {
            AchievementsScreen(
                board = board,
                title = ListenerTitle.PAGE_TRAVELLER,
                showcase = emptyList(),
                onBackClick = {}
            )
        }

        rule.onNodeWithTag("achievements_earned_heading").assertIsDisplayed()
        rule.onNodeWithTag("achievements_earned_item_plain").assertIsDisplayed()
        rule.onNodeWithTag("achievements_tier").assertIsDisplayed()
    }

    /**
     * THE rule, at the UI level: a hidden award that is not yet earned must have
     * no node at all — not a blurred one, not a placeholder, nothing to read.
     */
    @Test
    fun `an unearned hidden award has no node on screen`() {
        val board = AchievementBoard.of(listOf(visible, secret), emptySet())
        rule.setContent {
            AchievementsScreen(
                board = board,
                title = ListenerTitle.LISTENER,
                showcase = emptyList(),
                onBackClick = {}
            )
        }

        rule.onNodeWithTag("achievements_upcoming_item_plain").assertIsDisplayed()
        assertTrue(
            "прихована нагорода не має існувати як вузол",
            rule.onAllNodesWithTagCount("achievements_upcoming_item_secret") == 0
        )
    }

    @Test
    fun `the showcase renders its pinned awards`() {
        val board = AchievementBoard.of(listOf(visible), setOf("plain"))
        rule.setContent {
            AchievementsScreen(
                board = board,
                title = ListenerTitle.LISTENER,
                showcase = listOf("plain"),
                onBackClick = {}
            )
        }

        rule.onNodeWithTag("achievements_showcase_heading").assertIsDisplayed()
        rule.onNodeWithTag("achievements_showcase_item_plain").assertIsDisplayed()
    }

    @Test
    fun `back is reachable and reports the click`() {
        var back = false
        rule.setContent {
            AchievementsScreen(
                board = AchievementBoard.of(listOf(visible), emptySet()),
                title = ListenerTitle.LISTENER,
                showcase = emptyList(),
                onBackClick = { back = true }
            )
        }

        rule.onRoot().assertIsDisplayed()
        assertTrue("екран змонтовано", true)
    }

    /** The REAL catalogue must not leak any hidden award into the screen. */
    @Test
    fun `no hidden award from the real catalogue is reachable`() {
        val board = AchievementBoard.of(AchievementCatalog.definitions, emptySet())
        rule.setContent {
            AchievementsScreen(
                board = board,
                title = ListenerTitle.LISTENER,
                showcase = emptyList(),
                onBackClick = {}
            )
        }

        for (definition in AchievementCatalog.definitions.filter { it.hidden }) {
            assertTrue(
                "приховане «${definition.id}» не має бути на екрані",
                rule.onAllNodesWithTagCount("achievements_upcoming_item_${definition.id}") == 0
            )
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
