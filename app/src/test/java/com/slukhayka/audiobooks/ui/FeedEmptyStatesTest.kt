package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1509: the endless feed's terminal states. Before this, an empty pager and a
 * failed first page both looked like "nothing under the toolbar", and a filter
 * that matched nothing was indistinguishable from an empty catalogue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class FeedEmptyStatesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setEmptyFeed(
        genreFilters: Set<String> = emptySet(),
        durationFilters: Set<String> = emptySet(),
        sharedLibraryOnly: Boolean = false,
        onGenres: (Set<String>) -> Unit = {},
        onDurations: (Set<String>) -> Unit = {},
        onShared: (Boolean) -> Unit = {}
    ) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val feedFlow = remember { MutableStateFlow(PagingData.empty<WorkFeedRow>()) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("feed_test_list")) {
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
                            feedGenreFilters = genreFilters,
                            feedSortByTitle = false,
                            feedDurationFilters = durationFilters,
                            feedSharedLibraryOnly = sharedLibraryOnly,
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
                            onSetFeedGenreFilters = onGenres,
                            onSetFeedSortByTitle = {},
                            onSetFeedDurationFilters = onDurations,
                            onSetFeedSharedLibraryOnly = onShared
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("feed_test_list")
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun anEmptyFeedShowsTheCanonicalEmptyState() {
        setEmptyFeed()

        scrollTo("feed_empty")
        compose.onNodeWithTag("feed_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_empty_refresh").assertIsDisplayed()
        compose.onNodeWithTag("feed_filters_empty").assertDoesNotExist()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.feed_empty_title)).assertIsDisplayed()
    }

    @Test
    fun aGenreFilterAloneIsEnoughToSayNothingWasFound() {
        var genres: Set<String>? = null
        setEmptyFeed(genreFilters = setOf("genre-1"), onGenres = { genres = it })

        scrollTo("feed_filters_empty")
        compose.onNodeWithTag("feed_filters_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_filters_reset").performClick()
        assertEquals(emptySet<String>(), genres)
    }

    @Test
    fun aDurationFilterAloneIsEnoughToSayNothingWasFound() {
        setEmptyFeed(durationFilters = setOf("short"))

        scrollTo("feed_filters_empty")
        compose.onNodeWithTag("feed_filters_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_empty").assertDoesNotExist()
    }

    @Test
    fun theSharedLibraryToggleAloneIsEnoughToSayNothingWasFound() {
        var shared: Boolean? = null
        setEmptyFeed(sharedLibraryOnly = true, onShared = { shared = it })

        scrollTo("feed_filters_empty")
        compose.onNodeWithTag("feed_filters_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_filters_reset").performClick()
        assertEquals(false, shared)
    }

    @Test
    fun aFilterThatMatchesNothingSaysSoAndOffersReset() {
        var genres: Set<String>? = null
        var durations: Set<String>? = null
        var shared: Boolean? = null
        setEmptyFeed(
            genreFilters = setOf("genre-1"),
            durationFilters = setOf("short"),
            sharedLibraryOnly = true,
            onGenres = { genres = it },
            onDurations = { durations = it },
            onShared = { shared = it }
        )

        scrollTo("feed_filters_empty")
        compose.onNodeWithTag("feed_filters_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_empty").assertDoesNotExist()
        compose.onNodeWithTag("feed_filters_reset").performClick()

        assertEquals(emptySet<String>(), genres)
        assertEquals(emptySet<String>(), durations)
        assertEquals(false, shared)
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.feed_filters_empty_title)).assertIsDisplayed()
    }

    @Test
    fun aFailedFirstPageShowsTheErrorStateAndRetryRetries() {
        val source = FailingPagingSource()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val pager = remember {
                        Pager(PagingConfig(pageSize = 10, initialLoadSize = 10)) { source }
                    }
                    val feedItems = pager.flow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("feed_test_list")) {
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
                            onSetFeedSortByTitle = {}
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { source.attempts >= 1 }
        compose.waitForIdle()
        scrollTo("feed_refresh_error")
        compose.onNodeWithTag("feed_refresh_retry").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.feed_refresh_error_title)).assertIsDisplayed()

        compose.onNodeWithTag("feed_refresh_retry").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { source.attempts >= 2 }
        assertTrue("retry must ask the paging source again", source.attempts >= 2)
    }

    @Test
    fun aRefreshFailureWithShownRowsKeepsThemAndRetries() {
        val source = RowsThenFailPagingSource()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val pager = remember {
                        Pager(PagingConfig(pageSize = 10, initialLoadSize = 10)) { source }
                    }
                    val feedItems = pager.flow.collectAsLazyPagingItems()
                    var refreshStarted by remember { mutableStateOf(false) }
                    LaunchedEffect(feedItems.itemCount) {
                        if (feedItems.itemCount > 0 && !refreshStarted) {
                            refreshStarted = true
                            feedItems.refresh()
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("feed_test_list")) {
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
                            onSetFeedSortByTitle = {}
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            source.attempts >= 2 && compose.onAllNodesWithTag("feed_refresh_error_banner")
                .fetchSemanticsNodes().isNotEmpty()
        }
        scrollTo("feed_refresh_error_banner")
        compose.onNodeWithTag("feed_refresh_error_banner").assertIsDisplayed()
        compose.onNodeWithTag("feed_refresh_error_banner_retry").assertIsDisplayed()
        // The already loaded row was not hidden by the failed refresh.
        compose.onNodeWithText("Книга").assertIsDisplayed()

        compose.onNodeWithTag("feed_refresh_error_banner_retry").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { source.attempts >= 3 }
    }

    private class FailingPagingSource : PagingSource<Int, WorkFeedRow>() {
        var attempts = 0

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkFeedRow> {
            attempts += 1
            return LoadResult.Error(IOException("feed refresh failed"))
        }

        override fun getRefreshKey(state: PagingState<Int, WorkFeedRow>): Int? = null
    }

    /**
     * First load succeeds with one row; the refresh (second load) fails, so the
     * banner renders ABOVE rows that must stay visible, and its retry asks the
     * source again (third load).
     */
    private class RowsThenFailPagingSource : PagingSource<Int, WorkFeedRow>() {
        var attempts = 0

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkFeedRow> {
            attempts += 1
            if (attempts == 1) {
                return LoadResult.Page(
                    data = listOf(
                        WorkFeedRow(
                            workId = "w1",
                            mergeKey = "mk1",
                            title = "Книга",
                            author = "Автор",
                            coverImageUrl = null,
                            addedAt = 1L,
                            sourceCount = 1
                        )
                    ),
                    prevKey = null,
                    nextKey = null
                )
            }
            return LoadResult.Error(IOException("feed refresh failed"))
        }

        override fun getRefreshKey(state: PagingState<Int, WorkFeedRow>): Int? = null
    }
}
