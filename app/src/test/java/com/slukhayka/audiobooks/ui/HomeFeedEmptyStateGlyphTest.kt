package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.db.WorkFeedRow
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1624 (F17/F33) — the three terminal states of the «Огляд» feed are
 * ABSENCES, and the 2026 direction states an absence in type (Literata 20 +
 * Meta), not with a placeholder glyph.
 *
 * [homeFeedContent] is a `LazyListScope` emitter, so the states are rendered
 * through a tall LazyColumn (every block composes) and the icon seam
 * (`empty_state_icon`, added with the iconless `EmptyState` in #1616) is
 * asserted absent INSIDE the state, not merely absent from the tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w411dp-h4000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class HomeFeedEmptyStateGlyphTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun showFeed(filters: Set<String> = emptySet()) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val feedFlow = remember { MutableStateFlow(PagingData.empty<WorkFeedRow>()) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("home_feed")) {
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
                            feedGenreFilters = filters,
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
                            onSetFeedDurationFilters = {}
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertNoPlaceholderGlyph(stateTag: String) {
        composeTestRule.onNodeWithTag(stateTag).assertExists()
        composeTestRule
            .onNode(
                hasTestTag("empty_state_icon") and hasAnyAncestor(hasTestTag(stateTag))
            )
            .assertDoesNotExist()
    }

    @Test
    fun `the empty feed states an absence without a placeholder glyph`() {
        showFeed()

        assertNoPlaceholderGlyph("feed_empty")
    }

    @Test
    fun `the filtered-to-nothing feed states an absence without a placeholder glyph`() {
        showFeed(filters = setOf("science-fiction"))

        assertNoPlaceholderGlyph("feed_filters_empty")
    }

    @Test
    fun `every empty state on the feed surface is built without an icon argument`() {
        // #1638 — the guard now covers BOTH the feed's three terminal states
        // and the catalogue's first-run empty state (the last icon-bearing
        // empty state on «Огляд»), and reads balanced argument lists instead
        // of a 400-character window.
        val files = mapOf(
            "ui/screens/HomeFeedContent.kt" to 3,
            "ui/screens/HomeScreen.kt" to 1
        )
        files.forEach { (path, expectedCalls) ->
            val source = sourceFile(path).readText()
            assertEquals(
                "$path EmptyState call sites",
                expectedCalls,
                EmptyStateGlyphGuard.calls(source).size
            )
            assertTrue(
                "a $path empty state must not pass a placeholder glyph: " +
                    EmptyStateGlyphGuard.iconCalls(source),
                EmptyStateGlyphGuard.iconCalls(source).isEmpty()
            )
        }
    }

    @Test
    fun `the catalogue first-run empty state renders without a placeholder glyph`() {
        composeTestRule.setContent {
            com.slukhayka.audiobooks.ui.theme.PaperInkTheme(darkTheme = true) {
                com.slukhayka.audiobooks.ui.screens.EmptyCatalogState(
                    onRefreshClick = {},
                    onImportClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("catalog_empty_refresh").assertExists()
        composeTestRule.onNodeWithTag("empty_state_icon").assertDoesNotExist()
    }

    @Test
    fun `the shared guard catches named and positional icons but not iconContent`() {
        val iconless = """EmptyState(title = "T", body = "B") { Button({}) {} }"""
        val named = """EmptyState(icon = PaperInkIcons.Book, title = "T", body = "B")"""
        val positional = """EmptyState(PaperInkIcons.Book, title = "T", body = "B")"""
        val loading = """EmptyState(title = "T", body = "B", iconContent = { Spinner() })"""
        val nestedComma = """EmptyState(title = stringResource(R.string.t, 1), body = "B")"""

        assertEquals(1, EmptyStateGlyphGuard.calls(iconless).size)
        assertTrue(EmptyStateGlyphGuard.iconCalls(iconless).isEmpty())
        assertEquals(1, EmptyStateGlyphGuard.iconCalls(named).size)
        assertEquals(1, EmptyStateGlyphGuard.iconCalls(positional).size)
        assertTrue(
            "iconContent is the live-indicator seam, not a placeholder glyph",
            EmptyStateGlyphGuard.iconCalls(loading).isEmpty()
        )
        assertTrue(
            "a comma inside a nested call must not be mistaken for the icon slot",
            EmptyStateGlyphGuard.iconCalls(nestedComma).isEmpty()
        )
    }

    private fun sourceFile(relative: String): File {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
        return File(root, relative)
    }
}
