package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
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
@Config(sdk = [36])
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
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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

    @Test
    fun anEmptyFeedShowsTheCanonicalEmptyState() {
        setEmptyFeed()

        compose.onNodeWithTag("feed_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_empty_refresh").assertIsDisplayed()
        compose.onNodeWithTag("feed_filters_empty").assertDoesNotExist()
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

        compose.onNodeWithTag("feed_filters_empty").assertIsDisplayed()
        compose.onNodeWithTag("feed_empty").assertDoesNotExist()
        compose.onNodeWithTag("feed_filters_reset").performClick()

        assertEquals(emptySet<String>(), genres)
        assertEquals(emptySet<String>(), durations)
        assertEquals(false, shared)
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
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
        compose.waitUntil(timeoutMillis = 5_000) {
            source.attempts >= 1 &&
                compose.onAllNodesWithTag("feed_refresh_error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("feed_refresh_retry").assertIsDisplayed()

        compose.onNodeWithTag("feed_refresh_retry").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { source.attempts >= 2 }
        assertTrue("retry must ask the paging source again", source.attempts >= 2)
    }

    private class FailingPagingSource : PagingSource<Int, WorkFeedRow>() {
        var attempts = 0

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkFeedRow> {
            attempts += 1
            return LoadResult.Error(IOException("feed refresh failed"))
        }

        override fun getRefreshKey(state: PagingState<Int, WorkFeedRow>): Int? = null
    }
}
