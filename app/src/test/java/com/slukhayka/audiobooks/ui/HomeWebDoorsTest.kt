package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #741 — the 4read browser chrome is gone while the debug-only Sluhay exit
 * CTA survives:
 *
 * 1. Огляд renders no 4read row and no "4read" text anywhere, whatever the
 *    library/feed shape.
 * 2. The Sluhay door still renders when wired (debug builds only — the
 *    composition root passes null in release) and fires its action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeWebDoorsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `overview renders the wired sluhay door without any 4read chrome`() {
        composeTestRule.setContent { DoorsContent(sluhayWired = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_web_source_sluhay").assertExists()
        composeTestRule.onNodeWithText("Більше книг на Sluhay").assertExists()
        // #741: no 4read row, tag or word anywhere on the surface.
        composeTestRule.onAllNodesWithTag("open_web_source_4read").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("4read", substring = true).assertCountEquals(0)
    }

    @Test
    fun `release wiring keeps the sluhay door hidden too`() {
        composeTestRule.setContent { DoorsContent(sluhayWired = false) }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("open_web_source_sluhay").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("4read", substring = true).assertCountEquals(0)
    }

    @Test
    fun `tapping the sluhay overview row fires the wired door`() {
        var doorFired = false
        composeTestRule.setContent {
            DoorsContent(sluhayWired = true, onSluhay = { doorFired = true })
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_web_source_sluhay").performClick()
        composeTestRule.waitForIdle()
        assertTrue(doorFired)
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private val feedRows = listOf(
        WorkFeedRow(
            workId = "w1", mergeKey = "mk1", title = "Місто", author = "Валер'ян Підмогильний",
            coverImageUrl = null, addedAt = 1L, sourceCount = 1
        )
    )

    @Composable
    private fun DoorsContent(
        sluhayWired: Boolean,
        onSluhay: () -> Unit = {}
    ) {
        AudiobookTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val feedFlow = remember { MutableStateFlow(PagingData.from(feedRows)) }
                val feedItems = feedFlow.collectAsLazyPagingItems()
                LazyColumn {
                    homeFeedContent(
                        isCatalogLoading = false,
                        hasLibraryBooks = true,
                        sections = emptyList(),
                        genreFacetOptions = emptyList(),
                        collections = emptyList(),
                        newArrivals = emptyList(),
                        recommendedBooks = emptyList(),
                        personalCycles = emptyList(),
                        shortBooks = emptyList(),
                        longBooks = emptyList(),
                        workFeedItems = feedItems,
                        feedGenreFilters = emptySet(),
                        feedSortByTitle = false,
                        onRefreshCatalog = {},
                        onGoToLibrary = {},
                        onOpenTop100 = {},
                        onOpenPeople = {},
                        onOpenSeriesIndex = {},
                        onOpenCollectionsIndex = {},
                        onOpenSeries = { _, _ -> },
                        onOpenRecommendedBook = {},
                        onOpenWorkFeedRow = {},
                        onBookClick = {},
                        onSetFeedGenreFilters = {},
                        onSetFeedSortByTitle = {},
                        onOpenWebSource = if (sluhayWired) onSluhay else null
                    )
                }
            }
        }
    }
}
