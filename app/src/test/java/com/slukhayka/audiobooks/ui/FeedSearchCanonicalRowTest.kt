package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.catalog.CatalogBrowserFocusReturn
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardFailure
import com.slukhayka.audiobooks.ui.catalog.CatalogCardTarget
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.screens.searchResultsContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 #567 (v1.4 C3, ADR-0033) — search and the Work feed render the ONE
 * canonical [com.slukhayka.audiobooks.ui.components.BookRow] and keep the
 * action-state contract that used to live inside two named wrappers
 * (`GlobalSearchResultCard`, `WorkFeedCard`).
 *
 * The wrappers are gone, so these tests drive the real call sites —
 * [searchResultsContent] and [homeFeedContent] — instead of a component that
 * only the tests still used: the row a listener sees is the row built here.
 * The uk-rUA qualifier is explicit so the chrome assertions resolve the
 * Ukrainian resources the fixtures live in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class FeedSearchCanonicalRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val result = GlobalSearchResult(
        title = "Пані Боварі",
        author = "Гюстав Флобер",
        mergeKey = "пані боварі|гюстав флобер",
        sources = listOf(
            GlobalSearchSource("soundbooks", "Sound-Books", "https://example.invalid/bovari")
        )
    )

    private fun feedRow(
        workId: String = "work-1",
        title: String = "Темна матерія",
        author: String = "Блейк Крауч",
        languages: String = "",
        durationSeconds: Long? = null,
        durationMaxSeconds: Long? = null
    ) = WorkFeedRow(
        workId = workId,
        mergeKey = "$title|$author".lowercase(),
        title = title,
        author = author,
        coverImageUrl = null,
        addedAt = 1L,
        sourceCount = 1,
        languages = languages,
        durationSeconds = durationSeconds,
        durationMaxSeconds = durationMaxSeconds
    )

    private fun sourceFor(cardKey: String) = SourceEntity(
        id = "4read-edition",
        bookId = cardKey,
        editionId = "edition",
        type = "4read",
        url = "https://example.invalid/$cardKey"
    )

    private fun setSearchContent(
        results: List<GlobalSearchResult>,
        actionState: CatalogCardActionState = CatalogCardActionState.Idle,
        onOpen: (GlobalSearchResult) -> Unit = {},
        onOpenBrowser: () -> Unit = {},
        onPreflight: (GlobalSearchResult) -> Unit = {}
    ) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LazyColumn(Modifier.fillMaxSize().testTag("search_results")) {
                        searchResultsContent(
                            localBooks = emptyList(),
                            globalResults = results,
                            liveSearchActive = true,
                            isGlobalLoading = false,
                            globalError = false,
                            onOpenLocalBook = {},
                            onPlayLocalBook = {},
                            onOpenGlobalResult = onOpen,
                            catalogCardActionState = actionState,
                            onOpenCatalogBrowser = onOpenBrowser,
                            onPreflightGlobalResult = onPreflight
                        )
                    }
                }
            }
        }
    }

    private fun setFeedContent(
        rows: List<WorkFeedRow>,
        actionState: CatalogCardActionState = CatalogCardActionState.Idle,
        onOpen: (WorkFeedRow) -> Unit = {},
        onPlay: (WorkFeedRow) -> Unit = {},
        onCancel: () -> Unit = {},
        onOpenBrowser: () -> Unit = {},
        onPreflight: (WorkFeedRow) -> Unit = {}
    ) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val feedFlow = remember { MutableStateFlow(PagingData.from(rows)) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(Modifier.fillMaxSize().testTag("home_feed")) {
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
                            onOpenWorkFeedRow = onOpen,
                            onBookClick = {},
                            onSetFeedGenreFilters = {},
                            onSetFeedSortByTitle = {},
                            onPlayWorkFeedRow = onPlay,
                            catalogCardActionState = actionState,
                            onCancelCatalogCardAction = onCancel,
                            onOpenCatalogBrowser = onOpenBrowser,
                            onPreflightWorkFeedRow = onPreflight
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- search

    @Test
    fun searchRendersOneCanonicalRowPerSourceResult() {
        setSearchContent(listOf(result))
        compose.waitForIdle()

        compose.onNodeWithTag("global_search_result_${result.key}")
            .assertIsDisplayed()
            .assertTextContains(result.title)
            .assertTextContains(result.author)
        // One merged row node: no duplicate cover label, no stray play action.
        compose.onNodeWithContentDescription(result.title, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Відтворити", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun searchPreflightsEveryRenderedResult() {
        val preflighted = mutableListOf<String>()
        setSearchContent(listOf(result), onPreflight = { preflighted += it.key })
        compose.waitForIdle()

        assertEquals(listOf(result.key), preflighted)
    }

    @Test
    fun searchDurationAndLanguageBadgesRideTheCanonicalRow() {
        setSearchContent(
            listOf(result.copy(language = "en", durationSeconds = 8_100L))
        )
        compose.waitForIdle()

        compose.onNodeWithText("2:15:00").assertExists()
        compose.onNodeWithText("EN").assertExists()
    }

    @Test
    fun searchCheckingStateRendersUnderTheCanonicalRow() {
        val target = CatalogCardTarget(result.key, result.title, cardKey = result.key)
        setSearchContent(
            listOf(result),
            actionState = CatalogCardActionState.Checking(target, CatalogCardAction.PLAY)
        )
        compose.waitForIdle()

        compose.onNodeWithText("Перевіряємо…").assertExists()
    }

    @Test
    fun searchBrowserRequiredOffersTheWebsiteAction() {
        var opens = 0
        setSearchContent(
            listOf(result),
            actionState = CatalogCardActionState.BrowserRequired(
                CatalogCardTarget(result.key, result.title, cardKey = result.key),
                CatalogCardAction.PLAY,
                sourceFor(result.key)
            ),
            onOpenBrowser = { opens++ }
        )
        compose.waitForIdle()

        compose.onNodeWithTag("catalog_card_open_browser_${result.key}")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, opens)
    }

    @Test
    fun searchBrowserFallbackRestoresFocusToItsWebsiteAction() {
        CatalogBrowserFocusReturn.remember(result.key)
        CatalogBrowserFocusReturn.publishAfterBrowserClose()
        setSearchContent(
            listOf(result),
            actionState = CatalogCardActionState.BrowserRequired(
                CatalogCardTarget(result.key, result.title, cardKey = result.key),
                CatalogCardAction.PLAY,
                sourceFor(result.key)
            )
        )
        compose.waitForIdle()

        compose.onNodeWithTag("catalog_card_open_browser_${result.key}").assertIsFocused()
        CatalogBrowserFocusReturn.consume(result.key)
    }

    /**
     * A11y: the search result row stays reachable and one contextual target at
     * 200% font scale — the canonical row is taller than the old card, so the
     * surface scrolls rather than squeezing the row out of the viewport.
     */
    @Test
    fun searchRowRemainsReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LazyColumn(Modifier.fillMaxSize().testTag("search_results")) {
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
        compose.waitForIdle()

        compose.onNodeWithTag("search_results")
            .performScrollToNode(hasTestTag("global_search_result_${result.key}"))
        compose.onNodeWithTag("global_search_result_${result.key}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(24.dp)
    }

    // ------------------------------------------------------------------ feed
    @Test
    fun feedRendersOneCanonicalRowPerWork() {
        setFeedContent(listOf(feedRow()))
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_work-1"))
        compose.onNodeWithTag("work_feed_work-1")
            .assertIsDisplayed()
            .assertTextContains("Темна матерія")
            .assertTextContains("Блейк Крауч")
        compose.onNodeWithContentDescription("Темна матерія", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Відтворити", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun feedPreflightsEveryRenderedWork() {
        val preflighted = mutableListOf<String>()
        setFeedContent(listOf(feedRow()), onPreflight = { preflighted += it.workId })
        compose.waitForIdle()

        assertEquals(listOf("work-1"), preflighted)
    }

    @Test
    fun feedDurationAndLanguageBadgesRideTheCanonicalRow() {
        setFeedContent(
            listOf(feedRow(languages = "en,uk", durationSeconds = 60_061L))
        )
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_work-1"))
        compose.onNodeWithText("16:41:01").assertExists()
        compose.onNodeWithText("EN").assertExists()
        compose.onNodeWithText("UA").assertExists()
    }

    @Test
    fun feedOpensAndPlaysThroughSeparateAccessibleActions() {
        var opened = false
        var played = false
        setFeedContent(
            listOf(feedRow()),
            onOpen = { opened = true },
            onPlay = { played = true }
        )
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_work-1"))
        compose.onNodeWithContentDescription("Відкрити книгу: Темна матерія").performClick()
        assertTrue(opened)
        assertFalse(played)

        compose.onNodeWithContentDescription("Слухати: Темна матерія").performClick()
        assertTrue(played)
    }

    @Test
    fun feedCheckingStateShowsItsStatusUnderTheRowAndStaysCancellable() {
        val row = feedRow()
        var cancelled = false
        setFeedContent(
            listOf(row),
            actionState = CatalogCardActionState.Checking(
                CatalogCardTarget(row.workId, row.title, row.author),
                CatalogCardAction.PLAY
            ),
            onCancel = { cancelled = true }
        )
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_${row.workId}"))
        compose.onNodeWithText("Перевіряємо…").assertExists()
        compose.onNodeWithContentDescription("Скасувати").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun feedTerminalErrorShowsTheHonestStatusUnderTheRow() {
        val row = feedRow()
        setFeedContent(
            listOf(row),
            actionState = CatalogCardActionState.Failed(
                CatalogCardTarget(row.workId, row.title, row.author),
                CatalogCardAction.OPEN,
                CatalogCardFailure.EMPTY_SOURCES
            )
        )
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_${row.workId}"))
        compose.onNodeWithText("Не вдалося відкрити книгу. Спробуйте ще раз.").assertExists()
    }

    @Test
    fun feedBrowserRequiredOffersTheWebsiteAction() {
        var opens = 0
        val row = feedRow()
        setFeedContent(
            listOf(row),
            actionState = CatalogCardActionState.BrowserRequired(
                CatalogCardTarget(row.workId, row.title, row.author),
                CatalogCardAction.PLAY,
                sourceFor(row.workId)
            ),
            onOpenBrowser = { opens++ }
        )
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_${row.workId}"))
        compose.onNodeWithTag("catalog_card_open_browser_${row.workId}")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, opens)
    }

    @Test
    fun feedBrowserFallbackRestoresFocusToItsWebsiteAction() {
        val row = feedRow()
        CatalogBrowserFocusReturn.remember(row.workId)
        CatalogBrowserFocusReturn.publishAfterBrowserClose()
        setFeedContent(
            listOf(row),
            actionState = CatalogCardActionState.BrowserRequired(
                CatalogCardTarget(row.workId, row.title, row.author),
                CatalogCardAction.PLAY,
                sourceFor(row.workId)
            )
        )
        compose.waitForIdle()

        compose.onNodeWithTag("home_feed")
            .performScrollToNode(hasTestTag("work_feed_${row.workId}"))
        compose.onNodeWithTag("catalog_card_open_browser_${row.workId}").assertIsFocused()
        CatalogBrowserFocusReturn.consume(row.workId)
    }
}
