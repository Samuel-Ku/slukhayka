package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.screens.searchResultsContent
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
 * Snapshot tests for the spec-10 T4 global-search result row: one Work with a
 * badge per matching source.
 *
 * #567: the search surface renders the canonical `BookRow` inline at the
 * `searchResultsContent` call site — the named `GlobalSearchResultCard`
 * wrapper is gone — so these snapshots drive that seam with the uk-rUA
 * qualifier the fixtures live in. Pure inputs: no `MainViewModel`, no network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
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

    /** The live «Усі джерела» lane, rendered through the real search emitter. */
    private fun setSearchContent(result: GlobalSearchResult) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        searchResultsContent(
                            localBooks = emptyList(),
                            globalResults = listOf(result),
                            liveSearchActive = true,
                            isGlobalLoading = false,
                            globalError = false,
                            onOpenLocalBook = {},
                            onPlayLocalBook = {},
                            onOpenGlobalResult = {},
                            catalogCardActionState = CatalogCardActionState.Idle,
                            onOpenCatalogBrowser = {},
                            onPreflightGlobalResult = {}
                        )
                    }
                }
            }
        }
    }

    // Spec-45 (#405) T7 (#495): the row's rendition language renders the
    // EN badge; unknown languages render nothing.
    @Test
    fun result_card_known_language_renders_badge() {
        setSearchContent(singleSource.copy(language = "en"))

        composeTestRule.onNodeWithText("EN").assertExists()
        composeTestRule.onNodeWithContentDescription("English").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_en_badge.png"
        )
    }

    @Test
    fun result_card_unknown_language_renders_no_badge() {
        setSearchContent(singleSource)

        composeTestRule.onNodeWithText("EN").assertDoesNotExist()
        composeTestRule.onNodeWithText("UA").assertDoesNotExist()
    }

    @Test
    fun result_card_single_source() {
        setSearchContent(singleSource)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_single.png"
        )
    }

    @Test
    fun result_card_multi_source_badges() {
        setSearchContent(multiSource)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_multi.png"
        )
    }

    @Test
    fun result_card_with_duration() {
        // Spec-30 T2 (#217): the row renders the resolved duration (from the
        // local database or the shared metadata cache) under the author.
        setSearchContent(singleSource.copy(durationSeconds = 8_100L))

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
                        MetadataChip(source = "4read")
                        Spacer(modifier = Modifier.padding(4.dp))
                        MetadataChip(source = "Локальна")
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
