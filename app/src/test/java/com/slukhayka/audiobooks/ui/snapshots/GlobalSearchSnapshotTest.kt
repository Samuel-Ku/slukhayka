package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.screens.GlobalSearchResultCard
import com.slukhayka.audiobooks.ui.screens.SourceBadgePill
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the spec-10 T4 global-search result card: one Work with
 * a badge per matching source. Pure-`@Composable` inputs — no `MainViewModel`,
 * no network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GlobalSearchSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val singleSource = GlobalSearchResult(
        title = "Темна матерія",
        author = "Блейк Крауч",
        narrator = "",
        mergeKey = "темна матерія|блейк крауч",
        sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/x.html"))
    )

    private val multiSource = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = "Валерій Завалко",
        mergeKey = "кобзар|тарас шевченко|валерій завалко",
        sources = listOf(
            GlobalSearchSource("4read", "4read", "https://4read.org/kobzar.html"),
            GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/kobzar.html"),
            GlobalSearchSource("audiobookmp3", "audiobook-mp3", "https://audiobook-mp3.com/uk-audio-kobzar")
        )
    )

    // Spec-45 (#405) T7 (#495): the card's rendition language renders the
    // EN badge; unknown languages render nothing.
    @Test
    fun result_card_known_language_renders_badge() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    GlobalSearchResultCard(result = singleSource.copy(language = "en"), onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("EN").assertExists()
        composeTestRule.onNodeWithContentDescription("English").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_en_badge.png"
        )
    }

    @Test
    fun result_card_unknown_language_renders_no_badge() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    GlobalSearchResultCard(result = singleSource, onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("EN").assertDoesNotExist()
        composeTestRule.onNodeWithText("UA").assertDoesNotExist()
    }

    @Test
    fun result_card_single_source() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    GlobalSearchResultCard(result = singleSource, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_single.png"
        )
    }

    @Test
    fun result_card_multi_source_badges() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    GlobalSearchResultCard(result = multiSource, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_multi.png"
        )
    }

    @Test
    fun result_card_with_duration() {
        // Spec-30 T2 (#217): the card renders the resolved duration (from the
        // local database or the shared metadata cache) under the author.
        val withDuration = singleSource.copy(durationSeconds = 8_100L)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    GlobalSearchResultCard(result = withDuration, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_duration.png"
        )
    }

    @Test
    fun source_badge_pill() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    Column {
                        SourceBadgePill(label = "4read")
                        Spacer(modifier = Modifier.padding(4.dp))
                        SourceBadgePill(label = "Локальна")
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_source_badge.png"
        )
    }
}

/** Same chrome as the other snapshot tests: scheme background, full size. */
@Composable
private fun GlobalSearchSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
