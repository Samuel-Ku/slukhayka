package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.screens.searchResultsContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #737 / ADR-0041 — the search surface's section order and honest empty
 * states, pinned through the stateless [searchResultsContent] emitter: the
 * listener's own library always sits above the live Source Catalog, a
 * source-less local section says so without shouting, and the big
 * «Нічого не знайдено» state appears exactly once — never stacked.
 */
@RunWith(RobolectricTestRunner::class)
// uk-rUA + a tall viewport so both sections compose and are measurable.
@Config(qualifiers = "uk-rUA-w411dp-h4000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class SearchResultsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun localBook(id: String, title: String, author: String) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = ""
    )

    private val localBook = localBook("local-1", "Темна матерія", "Блейк Крауч")

    private val sourceHit = GlobalSearchResult(
        title = "Пані Боварі",
        author = "Гюстав Флобер",
        mergeKey = "пані боварі|гюстав флобер",
        sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/bovari"))
    )

    private fun setSearchContent(
        localBooks: List<AudiobookEntity>,
        globalResults: List<GlobalSearchResult>,
        liveSearchActive: Boolean = true,
        isGlobalLoading: Boolean = false,
        globalError: Boolean = false
    ) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        searchResultsContent(
                            localBooks = localBooks,
                            globalResults = globalResults,
                            liveSearchActive = liveSearchActive,
                            isGlobalLoading = isGlobalLoading,
                            globalError = globalError,
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

    private fun topOfTag(tag: String): Dp =
        compose.onNodeWithTag(tag).getBoundsInRoot().top

    @Test
    fun libraryMatchesRenderAboveLiveSourceResults() {
        setSearchContent(localBooks = listOf(localBook), globalResults = listOf(sourceHit))
        compose.waitForIdle()

        assertTrue(topOfTag("search_library_header") < topOfTag("search_sources_header"))
        assertTrue(topOfTag("book_item_local-1") < topOfTag("global_search_result_${sourceHit.key}"))
    }

    @Test
    fun anEmptyLibrarySectionSaysSoQuietlyWhileLiveResultsAreOnScreen() {
        setSearchContent(localBooks = emptyList(), globalResults = listOf(sourceHit))
        compose.waitForIdle()

        compose.onNodeWithTag("search_library_empty").assertExists()
        compose.onNodeWithTag("search_sources_header").assertExists()
        // The big "nothing found" state must not appear when a live hit does.
        assertEquals(0, compose.onAllNodesWithText("Нічого не знайдено").fetchSemanticsNodes().size)
    }

    @Test
    fun theBigEmptyStateAppearsExactlyOnceWhenNothingMatchesAnywhere() {
        setSearchContent(localBooks = emptyList(), globalResults = emptyList())
        compose.waitForIdle()

        assertEquals(1, compose.onAllNodesWithText("Нічого не знайдено").fetchSemanticsNodes().size)
        // Both honest section headers remain, but the live status is folded
        // into the single empty state instead of stacking a second message.
        compose.onNodeWithTag("search_library_header").assertExists()
        compose.onNodeWithTag("search_sources_header").assertExists()
        compose.onNodeWithTag("global_search_status").assertDoesNotExist()
    }

    @Test
    fun aLiveSearchInFlightShowsItsLoadingStatus_notTheEmptyState() {
        setSearchContent(localBooks = emptyList(), globalResults = emptyList(), isGlobalLoading = true)
        compose.waitForIdle()

        compose.onNodeWithTag("global_search_status").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Нічого не знайдено").fetchSemanticsNodes().size)
    }

    @Test
    fun aShortQueryWithoutLiveSearchDoesNotClaimSourcesWereSearched() {
        setSearchContent(localBooks = emptyList(), globalResults = emptyList(), liveSearchActive = false)
        compose.waitForIdle()

        // No live section at all, and one honest empty state for the library.
        compose.onNodeWithTag("search_sources_header").assertDoesNotExist()
        assertEquals(1, compose.onAllNodesWithText("Нічого не знайдено").fetchSemanticsNodes().size)
    }
}
