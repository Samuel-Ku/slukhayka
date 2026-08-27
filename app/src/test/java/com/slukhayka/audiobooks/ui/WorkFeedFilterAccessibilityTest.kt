package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.screens.HomeModalUnderlay
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilterSheet
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilters
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkFeedFilterAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filterSheetHidesTheWholeHomeUnderlayAndRestoresTheExactTrigger() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                var visible by remember { mutableStateOf(false) }
                val trigger = remember { FocusRequester() }

                RestoreFocusAfterModal(
                    modalVisible = visible,
                    returnFocusRequester = trigger
                )
                HomeModalUnderlay(modalVisible = visible) {
                    Column {
                        Box(Modifier.semantics { contentDescription = "Home body" })
                        WorkFeedFilters(
                            selectedGenreIds = emptySet(),
                            sortByTitle = false,
                            genres = genres,
                            onGenresChange = {},
                            onSortChange = {},
                            onOpenFilters = { visible = true },
                            filterTriggerModifier = Modifier.focusRequester(trigger)
                        )
                        Box(Modifier.semantics { contentDescription = "Home snackbar" })
                    }
                }
                if (visible) {
                    WorkFeedFilterSheet(
                        selectedGenreIds = emptySet(),
                        genres = genres,
                        onGenresChange = {},
                        onDismiss = { visible = false }
                    )
                }
            }
        }

        compose.onNodeWithTag("feed_filters").performClick()
        compose.onNodeWithTag("home_modal_underlay", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        compose.onNodeWithTag("work_feed_filter_heading", useUnmergedTree = true)
            .assertIsFocused()
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        compose.onNodeWithTag("work_feed_filter_sheet", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Фільтри каталогу"
                )
            )

        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("feed_filters").assertIsFocused()
    }

    @Test
    fun filterSheetControlsRemainReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Column {
                        WorkFeedFilters(
                            selectedGenreIds = setOf("fantasy"),
                            sortByTitle = false,
                            genres = genres,
                            onGenresChange = {},
                            onSortChange = {},
                            onOpenFilters = {}
                        )
                        WorkFeedFilterSheet(
                            selectedGenreIds = setOf("fantasy"),
                            genres = genres,
                            onGenresChange = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("feed_sort").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("feed_filters").assertHeightIsAtLeast(48.dp)
        listOf("feed_genre_all", "feed_genre_fantasy", "feed_genre_detective").forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("feed_filter_reset")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("feed_filter_done")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    private val genres = listOf(
        GenreFacetOption("fantasy", "Фентезі", 1),
        GenreFacetOption("detective", "Детективи", 1)
    )
}
