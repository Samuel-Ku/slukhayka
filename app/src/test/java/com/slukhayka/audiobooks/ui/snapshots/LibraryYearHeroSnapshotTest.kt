package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.data.entries.YearlyReadingGoal
import com.slukhayka.audiobooks.ui.screens.LibraryYearHero
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F28 of the 2026-10-28 device audit (issue #1625) — «Мій рік у книжках» was
 * the last amber M3 card in «Медіатека»: `primaryContainer` + `displaySmall`.
 * The direction prints a statistic on an INK plate instead (the same language
 * as the achievements hero), so this pins both app themes and the two numbers
 * the card is allowed to show.
 *
 * The token side (plate colours, text contrast on the plate) is in
 * `PaperInkTokensTest`; this file pins what a reader actually gets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryYearHeroSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Two formats on purpose: the domain counts finished PASSES, and the card
    // must show one honest total rather than a per-format split.
    private val goal = YearlyReadingGoal(
        year = 2026,
        finishedByFormat = mapOf(ReadingFormat.AUDIO to 9, ReadingFormat.PAPER to 3)
    )

    @Test
    fun paper_theme_prints_the_year_on_an_ink_plate() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = false) {
                HeroSurface {
                    LibraryYearHero(goal = goal)
                }
            }
        }

        composeTestRule.onNodeWithTag("library_year_hero").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_year_hero_title")
            .assertTextContains("2026", substring = true)
        composeTestRule.onNodeWithTag("library_year_hero_count").assertTextEquals("12")
        composeTestRule.onNodeWithTag("library_year_hero_units").assertIsDisplayed()

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_year_hero_paper.png"
        )
    }

    @Test
    fun ink_theme_keeps_the_plate_raised() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = true) {
                HeroSurface {
                    LibraryYearHero(goal = goal)
                }
            }
        }

        composeTestRule.onNodeWithTag("library_year_hero").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_year_hero_count").assertTextEquals("12")

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_year_hero_ink.png"
        )
    }
}

/** The page the plate is printed on — the same page background, not a tonal test bed. */
@Composable
private fun HeroSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.padding(PaperInkDimens.SpacePage)) {
            content()
        }
    }
}
