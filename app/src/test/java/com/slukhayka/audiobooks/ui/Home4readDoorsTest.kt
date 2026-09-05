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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.source.fourReadSearchUrl
import com.slukhayka.audiobooks.ui.screens.OpenWebSourceRow
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-42 #440 — the two explicit 4read doors and the debug-gating
 * asymmetry (ADR-0027):
 *
 * 1. On Огляд the «Більше книг на 4read →» row renders when the door is
 *    wired (release AND debug), while the Sluhay row stays debug-only —
 *    the wiring-level pin for the build-flavour asymmetry (the composition
 *    root passes a null sluhay door in release, the 4read door always).
 * 2. The empty-search CTA («Шукати «запит» на 4read») and the
 *    «Нічого на 4read? Шукати в браузері» footer reuse [OpenWebSourceRow]
 *    with a custom prompt — pinned at the row seam: the prompt carries the
 *    user's query, the tags stay distinct, and tapping fires exactly the
 *    prefilled transition ([fourReadSearchUrl]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Home4readDoorsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ------------------------------------------------------------------
    // Door 1: the Огляд row + the debug/release asymmetry
    // ------------------------------------------------------------------

    @Test
    fun `overview shows both rows when both doors are wired`() {
        composeTestRule.setContent { DoorsContent(sluhayWired = true, fourReadWired = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_web_source_4read").assertExists()
        composeTestRule.onNodeWithText("Більше книг на 4read").assertExists()
        composeTestRule.onNodeWithTag("open_web_source_sluhay").assertExists()
        composeTestRule.onNodeWithText("Більше книг на Sluhay").assertExists()
    }

    @Test
    fun `release wiring - sluhay door absent while the 4read door stays`() {
        // The composition root wires onOpenWebSource only in debug builds;
        // the release shape is a null sluhay door with the 4read door wired.
        composeTestRule.setContent { DoorsContent(sluhayWired = false, fourReadWired = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_web_source_4read").assertExists()
        composeTestRule.onAllNodesWithTag("open_web_source_sluhay").assertCountEquals(0)
    }

    @Test
    fun `tapping the 4read overview row fires the wired door`() {
        var doorFired = false
        composeTestRule.setContent {
            DoorsContent(
                sluhayWired = true,
                fourReadWired = true,
                on4read = { doorFired = true }
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_web_source_4read").performClick()
        composeTestRule.waitForIdle()
        assertTrue(doorFired)
    }

    // ------------------------------------------------------------------
    // Doors 2+3: the search CTAs reuse the row seam with a custom prompt
    // ------------------------------------------------------------------

    @Test
    fun `the empty-search CTA carries the query in its prompt and opens the prefilled url`() {
        var requestedUrl = ""
        composeTestRule.setContent {
            TestTheme {
                OpenWebSourceRow(
                    displayName = "4read",
                    onClick = { requestedUrl = fourReadSearchUrl("Сни") },
                    text = "Шукати «Сни» на 4read",
                    testTag = "open_4read_search_empty"
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Шукати «Сни» на 4read").assertExists()
        composeTestRule.onNodeWithTag("open_4read_search_empty").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "https://4read.org/index.php?do=search&subaction=search&story=%D0%A1%D0%BD%D0%B8",
            requestedUrl
        )
    }

    @Test
    fun `the no-4read footer carries its own prompt and tag`() {
        composeTestRule.setContent {
            TestTheme {
                OpenWebSourceRow(
                    displayName = "4read",
                    onClick = {},
                    text = "Нічого на 4read? Шукати в браузері",
                    testTag = "open_4read_search_footer"
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Нічого на 4read? Шукати в браузері").assertExists()
        composeTestRule.onNodeWithTag("open_4read_search_footer").assertExists()
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
    private fun TestTheme(content: @Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }

    @Composable
    private fun DoorsContent(
        sluhayWired: Boolean,
        fourReadWired: Boolean,
        on4read: () -> Unit = {}
    ) {
        TestTheme {
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
                    onOpenWebSource = if (sluhayWired) ({}) else null,
                    onOpenWebSource4read = if (fourReadWired) (on4read) else null
                )
            }
        }
    }
}
