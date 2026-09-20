package com.slukhayka.audiobooks.ui.snapshots

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.ui.screens.SeriesIndexContent
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
 * spec-28 (#189) — snapshot pins for the «Серії» index screen: the browsable
 * grid of [com.slukhayka.audiobooks.ui.components.CycleCard]s and the
 * no-series placeholder. Pure `@Composable` inputs ([SeriesIndexContent] is
 * stateless) — no `MainViewModel`.
 *
 * spec-46 T14 (#575): the placeholder is a resource now, so the locale is
 * explicit (the app's default, uk-rUA). Before that this pin had NO locale
 * qualifier and passed only because the screen hardcoded the Ukrainian
 * literal — an English-resolved resource would have slipped through the pin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SeriesIndexSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val series = listOf(
        CatalogSeries(
            title = "Максим Темний",
            url = "https://4read.org/xfsearch/cikl/maksym-temnyj/",
            coverImageUrl = null
        ),
        CatalogSeries(
            title = "Перший закон",
            url = "https://4read.org/xfsearch/cikl/pervyj-zakon/",
            coverImageUrl = null
        ),
        CatalogSeries(
            title = "Відвага",
            url = "https://4read.org/xfsearch/cikl/vidvaha/",
            coverImageUrl = null
        ),
        CatalogSeries(
            title = "Сага про Дріззта До'Урдена",
            url = "https://4read.org/xfsearch/cikl/drizzt/",
            coverImageUrl = null
        )
    )

    @Test
    fun series_index_grid() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SeriesIndexSurface {
                    // v1.4 E6 (ADR-0033): the count rides the canonical
                    // scaffold's subtitle — compose the screen chrome.
                    IndexScreenScaffold(
                        title = "Серії",
                        onBackClick = {},
                        subtitle = "4 серії"
                    ) { padding ->
                        SeriesIndexContent(
                            series = series,
                            onSeriesClick = {},
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }

        // Self-verifying on top of the image: the count subtitle and one card.
        composeTestRule.onNodeWithText("4 серії").assertExists()
        composeTestRule.onNodeWithText("Максим Темний").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/series_index_grid.png"
        )
    }

    @Test
    fun series_index_empty_state() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SeriesIndexSurface {
                    // v1.4 E6 (ADR-0033): screen chrome — the honest «0 серій»
                    // subtitle stays visible above the placeholder.
                    IndexScreenScaffold(
                        title = "Серії",
                        onBackClick = {},
                        subtitle = "0 серій"
                    ) { padding ->
                        SeriesIndexContent(
                            series = emptyList(),
                            onSeriesClick = {},
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }

        // The no-series placeholder renders a sensible message, not a crash.
        composeTestRule.onNodeWithText(context.getString(R.string.series_index_empty))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/series_index_empty.png"
        )
    }

    @Test
    fun series_card_tap_forwards_the_series() {
        var opened: CatalogSeries? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SeriesIndexSurface {
                    SeriesIndexContent(series = series, onSeriesClick = { opened = it })
                }
            }
        }

        composeTestRule.onNodeWithText("Перший закон").performClick()
        assertEquals("Перший закон", opened?.title)
        assertEquals("https://4read.org/xfsearch/cikl/pervyj-zakon/", opened?.url)
    }
}

/** Same chrome as the other snapshot seams: scheme background, full size. */
@Composable
private fun SeriesIndexSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
