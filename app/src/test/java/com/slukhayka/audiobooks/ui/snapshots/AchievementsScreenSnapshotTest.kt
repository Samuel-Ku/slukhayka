package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.achievements.AchievementBoard
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.AchievementMetric
import com.slukhayka.audiobooks.data.achievements.ListenerTitle
import com.slukhayka.audiobooks.ui.screens.AchievementsScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #704 (T6) — snapshots of the «Досягнення» screen.
 *
 * The ticket asks for «Compose/a11y/снапшот-патерни». The first two are covered
 * by [com.slukhayka.audiobooks.ui.screens.AchievementsScreenTest]; this is the
 * third, and it catches what assertions cannot: a section that renders but looks
 * wrong, or a pin control that collides with its own label.
 *
 * A FIXTURE catalogue, not the real one: the real one has 30+ awards and would
 * make a snapshot that changes every time an award is added — a golden that
 * churns teaches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AchievementsScreenSnapshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    // REAL ids from the catalogue. My first fixture invented them
    // (`five_books`, `twenty_five_books`) and the very first snapshot showed
    // why that is wrong: the screen fell back to the generic «Нова нагорода»
    // for both, so the golden proved nothing about how a real award reads.
    private val earnedA = AchievementDefinition("first_book", "books", 1, AchievementMetric.COMPLETED_BOOKS, 1)
    private val earnedB = AchievementDefinition("first_completion", "books", 2, AchievementMetric.COMPLETED_BOOKS, 5)
    private val upcoming = AchievementDefinition("books_25", "books", 3, AchievementMetric.COMPLETED_BOOKS, 25)
    private val secret = AchievementDefinition("night_watch", "hidden", 1, AchievementMetric.NIGHT_COMPLETIONS, 3, hidden = true)

    private val catalogue = listOf(earnedA, earnedB, upcoming, secret)

    @Test
    fun achievements_empty() {
        composeTestRule.setContent {
            AudiobookTheme {
                AchievementsScreen(
                    board = AchievementBoard.of(emptyList(), emptySet()),
                    title = ListenerTitle.LISTENER,
                    showcase = emptyList(),
                    onBackClick = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/achievements_empty.png")
    }

    /** The ordinary state: something earned, one pinned, more ahead. */
    @Test
    fun achievements_with_showcase() {
        composeTestRule.setContent {
            AudiobookTheme {
                AchievementsScreen(
                    board = AchievementBoard.of(catalogue, setOf("first_book", "first_completion")),
                    title = ListenerTitle.PAGE_TRAVELLER,
                    showcase = listOf("first_completion"),
                    onBackClick = {},
                    onTogglePin = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/achievements_with_showcase.png")
    }

    /**
     * Nothing earned yet: the visible ladder ahead, and the hidden award that
     * must NOT be in the picture.
     */
    @Test
    fun achievements_nothing_earned() {
        composeTestRule.setContent {
            AudiobookTheme {
                AchievementsScreen(
                    board = AchievementBoard.of(catalogue, emptySet()),
                    title = ListenerTitle.LISTENER,
                    showcase = emptyList(),
                    onBackClick = {},
                    onTogglePin = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/achievements_nothing_earned.png")
    }

    /**
     * #705 (T7) — the same showcase, but with a curator profile behind it.
     *
     * The other showcase snapshot has no profile, so it renders the honest
     * «спершу опублікуйте добірку» line and never shows the action at all. That
     * is the state a listener WITHOUT a profile meets; this is the one everybody
     * else meets, and it is the one that has to look right.
     */
    @Test
    fun achievements_showcase_publishable() {
        composeTestRule.setContent {
            AudiobookTheme {
                AchievementsScreen(
                    board = AchievementBoard.of(catalogue, setOf("first_book", "first_completion")),
                    title = ListenerTitle.PAGE_TRAVELLER,
                    showcase = listOf("first_completion"),
                    onBackClick = {},
                    onTogglePin = {},
                    showcasePublishable = true,
                    onPublishShowcase = {}
                )
            }
        }

        composeTestRule.onRoot()
            .captureRoboImage("src/test/snapshots/achievements_showcase_publishable.png")
    }
}
