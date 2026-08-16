package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.universe.SeriesRef
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.ui.screens.SeriesUniverseHeader
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spec-25 (#171) — the series screen's universe header block: the universe
 * name, the series' position inside it and the tappable
 * «Передує/Продовжує» chips. Pure `@Composable` inputs — no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SeriesUniverseHeaderSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(context: SeriesUniverseContext) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        UniverseHeader(context)
                    }
                }
            }
        }
    }

    @Composable
    private fun UniverseHeader(context: SeriesUniverseContext) {
        SeriesUniverseHeader(
            context = context,
            onOpenSeries = {}
        )
    }

    @Test
    fun universe_header_middle_series_shows_name_position_and_both_chips() {
        val context = SeriesUniverseContext(
            universeName = "Перший закон",
            seriesTitle = "Епоха божевілля",
            position = 2,
            totalInUniverse = 3,
            precedes = SeriesRef("Перший закон", "https://4read.org/xfsearch/cikl/pervyj-zakon/"),
            follows = SeriesRef("Відвага", "https://4read.org/xfsearch/cikl/vidvaha/")
        )
        setContent(context)

        // Self-verifying on top of the image.
        composeTestRule.onNodeWithText("Всесвіт: «Перший закон»").assertExists()
        composeTestRule.onNodeWithText("Цикл 2 з 3").assertExists()
        composeTestRule.onNodeWithText("Передує: «Перший закон»").assertExists()
        composeTestRule.onNodeWithText("Продовжує: «Відвага»").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/series_universe_header_middle.png"
        )
    }

    @Test
    fun universe_header_first_series_shows_no_precedes_chip() {
        val context = SeriesUniverseContext(
            universeName = "Перший закон",
            seriesTitle = "Перший закон",
            position = 1,
            totalInUniverse = 2,
            precedes = null,
            follows = SeriesRef("Епоха божевілля", "https://4read.org/xfsearch/cikl/epoha-bozhevillja/")
        )
        setContent(context)

        composeTestRule.onNodeWithText("Продовжує: «Епоха божевілля»").assertExists()
        composeTestRule.onNodeWithText("Передує: ", substring = true).assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/series_universe_header_first.png"
        )
    }

    @Test
    fun universe_header_single_series_universe_hides_position_and_chips() {
        val context = SeriesUniverseContext(
            universeName = "Гіперіон",
            seriesTitle = "Гіперіон",
            position = 1,
            totalInUniverse = 1,
            precedes = null,
            follows = null
        )
        setContent(context)

        composeTestRule.onNodeWithText("Всесвіт: «Гіперіон»").assertExists()
        composeTestRule.onNodeWithText("Цикл 1 з 1").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/series_universe_header_single.png"
        )
    }

    @Test
    fun universe_header_chip_tap_forwards_the_series() {
        var opened: SeriesRef? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    SeriesUniverseHeader(
                        context = SeriesUniverseContext(
                            universeName = "Перший закон",
                            seriesTitle = "Епоха божевілля",
                            position = 2,
                            totalInUniverse = 2,
                            precedes = SeriesRef("Перший закон", "https://4read.org/xfsearch/cikl/pervyj-zakon/"),
                            follows = null
                        ),
                        onOpenSeries = { opened = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Передує: «Перший закон»").performClick()
        assertEquals("Перший закон", opened?.title)
    }
}
