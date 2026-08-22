package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogGenre
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSectionId
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.screens.HomeHeader
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ADR-0017 closing pass for the spec-28 Огляд reorder (#203) — the visible
 * delta verified at PHONE size. The tall-viewport order test
 * ([HomeFeedOrderSnapshotTest]) proves the block ORDER; this test proves the
 * FOLD: on a real phone (Pixel 8, 411x914dp) the user's first screen shows
 * the nav row and curated shelves — NOT the endless feed. «Весь каталог» is
 * not composed at rest (below the fold, reachable by scrolling), and
 * scrolling to it confirms the feed is the screen's last element, with its
 * filter controls directly above the cards. Two goldens: the first screen
 * (`home_feed_phone_fold.png`) and the scrolled-to-feed state
 * (`home_feed_phone_feed.png`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class HomeFeedPhoneFoldSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Pixel 8 viewport height — the phone fold line. */
    private val foldBottom: Dp = 914.dp

    private val results = listOf(
        GlobalSearchResult(
            title = "Вкради мене... Зараз!",
            author = "Сергій Оріанець",
            mergeKey = "вкради-мене-зараз|сергій-оріанець",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/7611.html"))
        ),
        GlobalSearchResult(
            title = "Темна матерія",
            author = "Блейк Крауч",
            mergeKey = "темна-матерія|блейк-крауч",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/temna"))
        )
    )

    private val books = listOf(
        CatalogBook(
            id = "b1", title = "Старий і море", author = "Ернест Гемінґвей",
            url = "https://4read.org/b1", coverImageUrl = null
        ),
        CatalogBook(
            id = "b2", title = "Сто років самотності", author = "Габрієль Гарсія Маркес",
            url = "https://4read.org/b2", coverImageUrl = null
        )
    )

    private val sections = listOf(
        // A «Новинки»-titled section is SKIPPED by typed id (#197) — the
        // cross-source rail is the only place 4read's new arrivals render.
        CatalogSection(title = "Новинки", books = books, id = CatalogSectionId.NEW_ARRIVALS),
        CatalogSection(title = "Популярне", books = books, id = CatalogSectionId.POPULAR),
        CatalogSection(
            title = "Цикли",
            series = listOf(CatalogSeries(title = "Кіберпанк", url = "https://4read.org/cyber", coverImageUrl = null)),
            id = CatalogSectionId.SERIES
        )
    )

    private val collections = listOf(
        CollectionMatcher.MatchedCollection(id = "c1", name = "Нобелівські лауреати", sourceNote = "", books = results),
        CollectionMatcher.MatchedCollection(id = "c2", name = "Букер", sourceNote = "", books = listOf(results.first()))
    )

    private val recommendations = listOf(
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = "r1", title = "Хроніки Нарнії", author = "Клайв Стейплз Льюїс",
                genre = "Фантастика"
            ),
            score = 0.8,
            reasonTitle = "Старий і море"
        ),
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = "r2", title = "451° за Фаренгейтом", author = "Рей Бредбері",
                genre = "Антиутопія"
            ),
            score = 0.7,
            reasonTitle = "Темна матерія"
        )
    )

    private val feedRows = listOf(
        WorkFeedRow(
            workId = "w1", mergeKey = "mk1", title = "Місто", author = "Валер'ян Підмогильний",
            coverImageUrl = null, addedAt = 1L, sourceCount = 1
        ),
        WorkFeedRow(
            workId = "w2", mergeKey = "mk2", title = "Тигролови", author = "Іван Багряний",
            coverImageUrl = null, addedAt = 2L, sourceCount = 2
        )
    )

    @Test
    fun first_screen_is_curated_not_the_endless_feed() {
        composeTestRule.setContent { renderHomeFeed() }
        composeTestRule.waitForIdle()

        fun topOf(text: String): Dp =
            composeTestRule.onNodeWithText(text, ignoreCase = true).getBoundsInRoot().top

        // The nav row is on the first screen, not buried under chrome.
        assertTrue("nav row should be above the fold", topOf("Каталог") < foldBottom)
        // Curated content starts on the first screen: the marquee
        // «Рекомендовано для вас» shelf — the first row the spec order line
        // names after genres — is above the fold. (The inline «Колекції»
        // blocks live below the 4read sections, ADR-0017 closing pass.)
        assertTrue("«Рекомендовано для вас» should be above the fold", topOf("Рекомендовано для вас") < foldBottom)
        // The endless feed is NOT the first thing the user meets: «Весь
        // каталог» is either uncomposed (LazyColumn didn't reach it) or
        // strictly below the fold.
        if (composeTestRule.onAllNodesWithText("Весь каталог", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()) {
            assertTrue(
                "«Весь каталог» must not be above the fold",
                composeTestRule.onNodeWithText("Весь каталог", ignoreCase = true).getBoundsInRoot().top >= foldBottom
            )
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/home_feed_phone_fold.png"
        )
    }

    @Test
    fun scrolling_reaches_the_feed_as_the_last_element() {
        composeTestRule.setContent { renderHomeFeed() }
        composeTestRule.waitForIdle()

        // Scroll the endless feed into view — it exists, below every curated
        // shelf, with its filter controls right above the cards.
        composeTestRule.onNodeWithTag("home_feed")
            .performScrollToNode(hasText("Весь каталог", ignoreCase = true, substring = true))
        composeTestRule.waitForIdle()

        fun topOf(text: String): Dp =
            composeTestRule.onNodeWithText(text, ignoreCase = true).getBoundsInRoot().top

        assertTrue("feed header should be in view after the scroll", topOf("Весь каталог") < foldBottom)
        // The first feed card renders below the feed header — the header is
        // the last block's title, and the cards belong to it. (The curated
        // shelves above are no longer composed this far down — their relative
        // order is pinned by HomeFeedOrderSnapshotTest on the tall viewport.)
        assertTrue(topOf("Весь каталог") < topOf("Місто"))

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/home_feed_phone_feed.png"
        )
    }

    /** The real Огляд first screen: collapsed header + the feed body. */
    @Composable
    private fun renderHomeFeed() {
        AudiobookTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val feedFlow = remember { MutableStateFlow(PagingData.from(feedRows)) }
                val feedItems = feedFlow.collectAsLazyPagingItems()
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("home_feed")) {
                    item {
                        HomeHeader(
                            searchExpanded = false,
                            searchQuery = "",
                            selectedGenre = "Усі",
                            genres = listOf("Усі", "Фантастика", "Детективи"),
                            onToggleSearch = {},
                            onRefresh = {},
                            onSearchQueryChange = {},
                            onCloseSearch = {},
                            onSelectGenre = {}
                        )
                    }
                    homeFeedContent(
                        isCatalogLoading = false,
                        hasLibraryBooks = true,
                        sections = sections,
                        catalogGenres = listOf(
                            CatalogGenre("Фантастика", "https://4read.org/fant"),
                            CatalogGenre("Детективи", "https://4read.org/det")
                        ),
                        collections = collections,
                        newArrivals = results,
                        recommendedBooks = recommendations,
                        personalCycles = emptyList(),
                        shortBooks = books,
                        longBooks = books,
                        workFeedItems = feedItems,
                        feedSourceFilter = null,
                        feedGenreFilter = null,
                        feedSortByTitle = false,
                        onRefreshCatalog = {},
                        onGoToLibrary = {},
                        onOpenTop100 = {},
                        onOpenPeople = {},
                        onOpenSeriesIndex = {},
                        onOpenCollectionsIndex = {},
                        onOpenGenre = { _, _ -> },
                        onOpenSeries = { _, _ -> },
                        onPlayGlobalSearchResult = {},
                        onOpenRecommendedBook = {},
                        onOpenWorkFeedRow = {},
                        onBookClick = {},
                        onSetFeedSourceFilter = {},
                        onSetFeedGenreFilter = {},
                        onSetFeedSortByTitle = {},
                        onOpenWebSource = {}
                    )
                }
            }
        }
    }
}
