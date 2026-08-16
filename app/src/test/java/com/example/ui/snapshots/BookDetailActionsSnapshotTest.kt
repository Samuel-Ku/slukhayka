package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.screens.BookUniverseLine
import com.example.ui.screens.FavoriteButton
import com.example.ui.screens.SeriesPill
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #40 decision 1 — the book page's new action blocks, pinned from fixture
 * data: the favourite toggle (both states + the tap) and the series pill
 * (with/without a volume number + the tap). Pure `@Composable` inputs —
 * no `MainViewModel`, no Room. The four play-button labels themselves are
 * pinned by the pure JVM `BookPlayButtonTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class BookDetailActionsSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Spec-25 (#171): the «Всесвіт» line under the series pill on the book
    // page — renders the resolved universe name, silently absent otherwise.
    @Test
    fun book_universe_line_under_the_series_pill() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SeriesPill(seriesTitle = "Епоха божевілля", seriesIndex = 1, onClick = {})
                        BookUniverseLine(universeName = "Перший закон")
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Всесвіт: «Перший закон»").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_universe_line.png"
        )
    }

    @Test
    fun series_pill_with_volume() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SeriesPill(seriesTitle = "Чаклун", seriesIndex = 2, onClick = {})
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_series_pill.png"
        )
    }

    @Test
    fun series_pill_without_volume() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SeriesPill(seriesTitle = "Чаклун", seriesIndex = 0, onClick = {})
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_series_pill_no_volume.png"
        )
    }

    @Test
    fun series_pill_tap_opens_the_series() {
        var clicked = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    SeriesPill(seriesTitle = "Чаклун", seriesIndex = 2, onClick = { clicked = true })
                }
            }
        }
        composeTestRule.onNodeWithTag("book_detail_series_pill").performClick()
        assertTrue("tapping the pill must fire the series navigation", clicked)
    }

    @Test
    fun favorite_button_filled() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    FavoriteButton(isFavorite = true, onToggle = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_favorite_filled.png"
        )
    }

    @Test
    fun favorite_button_outline() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    FavoriteButton(isFavorite = false, onToggle = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_favorite_outline.png"
        )
    }

    @Test
    fun favorite_button_tap_toggles() {
        var selected = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    FavoriteButton(isFavorite = selected, onToggle = { selected = !selected })
                }
            }
        }
        composeTestRule.onNodeWithTag("favorite_toggle_button").performClick()
        assertTrue("tapping the heart must flip the favourite state", selected)
    }
}