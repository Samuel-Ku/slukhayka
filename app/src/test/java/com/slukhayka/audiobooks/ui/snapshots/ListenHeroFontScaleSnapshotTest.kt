package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1675 — the ink-theme witness for the large-text hero at 360 dp / 200 %:
 * the card grows, the meta lines run under the cover and the play button is
 * in the flow. Recorded by the `record-snapshots` workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w360dp-h800dp-xhdpi", sdk = [36])
class ListenHeroFontScaleSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0]
    private val progress = TestDataFactory.seedPlaybackProgress(
        listOf(book),
        chapterIndex = 2,
        positionSeconds = 420L,
    )[0]

    @Test
    fun hero_at_two_hundred_percent_text() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    // NOT an M3 Surface: it propagates min constraints, so the
                    // hero would stretch to the full 773 dp viewport and the
                    // golden would pin that artifact instead of the card's own
                    // content height (the production screen puts it in a
                    // LazyColumn item, where min height is 0).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        ListenHeroCard(
                            book = book,
                            progress = progress,
                            cumulativePositionSeconds = 600L + 660L + 420L,
                            onResumeClick = {},
                            onBookClick = {},
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("hero_narrator", useUnmergedTree = true).assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_hero_font_200.png"
        )
    }
}
