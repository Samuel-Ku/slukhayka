package com.slukhayka.audiobooks.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.screens.AudiobookListItem
import com.slukhayka.audiobooks.ui.screens.CatalogSeriesCard
import com.slukhayka.audiobooks.ui.screens.CatalogRowHeader
import com.slukhayka.audiobooks.ui.screens.CollectionBookCard
import com.slukhayka.audiobooks.ui.screens.GlobalSearchResultCard
import com.slukhayka.audiobooks.ui.screens.GlobalSearchStatus
import com.slukhayka.audiobooks.ui.screens.HomeHeader
import com.slukhayka.audiobooks.ui.screens.NewArrivalsRail
import com.slukhayka.audiobooks.ui.screens.RecommendedBookCard
import com.slukhayka.audiobooks.ui.screens.RecommendationDisclosureDialog
import com.slukhayka.audiobooks.ui.screens.UnifiedCatalogCard
import com.slukhayka.audiobooks.ui.screens.WorkFeedCard
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilters
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExploreAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val result = GlobalSearchResult(
        title = "Темна матерія",
        author = "Блейк Крауч",
        mergeKey = "темна матерія|блейк крауч",
        coverImageUrl = null,
        sources = listOf(
            GlobalSearchSource("soundbooks", "Sound-Books", "https://example.invalid/temna")
        )
    )

    @Test
    fun searchFieldHasAStableUkrainianLabelAndFilterExposesSelection() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                HomeHeader(
                    searchExpanded = true,
                    searchQuery = "",
                    onToggleSearch = {},
                    onRefresh = {},
                    onSearchQueryChange = {},
                    onCloseSearch = {}
                )
            }
        }

        compose.onNodeWithTag("home_search_input")
            .assertTextContains("Пошук книги або автора")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрити пошук")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun catalogueSectionTitleIsAHeading() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogRowHeader("Новинки")
            }
        }

        compose.onNodeWithText("НОВИНКИ")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun globalSearchCardIsOneWorkNodeWithoutDuplicateCoverOrPlayLabel() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchResultCard(result = result, onClick = {})
            }
        }

        compose.onNodeWithTag("global_search_result_${result.key}")
            .assertTextContains(result.title)
            .assertTextContains(result.author)
        compose.onNodeWithContentDescription(result.title, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Відтворити", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun browserRequiredCardOffersAWorkingWebsiteAction() {
        var opens = 0
        val target = CatalogCardTarget(
            workId = result.key,
            title = result.title,
            cardKey = result.key
        )
        val source = SourceEntity(
            id = "4read-edition",
            bookId = result.key,
            editionId = "edition",
            type = "4read",
            url = "https://example.invalid/session"
        )
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchResultCard(
                    result = result,
                    onClick = {},
                    actionState = CatalogCardActionState.BrowserRequired(
                        target = target,
                        action = CatalogCardAction.PLAY,
                        source = source
                    ),
                    onOpenBrowser = { opens++ }
                )
            }
        }

        compose.onNodeWithTag("catalog_card_open_browser_${result.key}")
            .assertIsDisplayed()
            .performClick()
        org.junit.Assert.assertEquals(1, opens)
    }

    @Test
    fun newArrivalsPreflightsOnlyTheLazyViewportAndItsBuffer() {
        val results = (0 until 50).map { index ->
            result.copy(
                title = "${result.title} $index",
                mergeKey = "${result.mergeKey}|$index",
                sources = result.sources.map { it.copy(url = "https://example.invalid/$index") }
            )
        }
        var preflightCount = 0
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(320.dp).height(300.dp)) {
                    NewArrivalsRail(
                        results = results,
                        onBookClick = {},
                        onPreflight = { preflightCount++ }
                    )
                }
            }
        }

        compose.waitForIdle()
        org.junit.Assert.assertTrue(preflightCount in 1 until results.size)
    }

    @Test
    fun catalogueCardKeepsDownloadAsASeparateContextualAction() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                UnifiedCatalogCard(
                    result = result,
                    onClick = {},
                    isDownloaded = false,
                    onDownload = {}
                )
            }
        }

        compose.onNodeWithTag("unified_catalog_${result.key}")
            .assertTextContains(result.title)
            .assertTextContains(result.author)
        compose.onNodeWithContentDescription("Завантажити: ${result.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(result.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun localSearchCardIsOneWorkNodeWithASeparateContextualPlayAction() {
        val book = TestDataFactory.dataBooks().first()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                AudiobookListItem(book = book, onClick = {}, onPlayClick = {})
            }
        }

        compose.onNodeWithTag("book_item_${book.id}")
            .assertTextContains(book.title)
            .assertTextContains(book.author)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Доступно офлайн"
                )
            )
        compose.onNodeWithContentDescription("Відтворити: ${book.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun localSearchCardAlwaysAnnouncesWhenAConnectionIsRequired() {
        val streamingBook = TestDataFactory.dataBooks().first().copy(isDownloaded = false)
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                AudiobookListItem(book = streamingBook, onClick = {}, onPlayClick = {})
            }
        }

        compose.onNodeWithTag("book_item_${streamingBook.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Для прослуховування потрібне підключення до інтернету"
                )
            )
    }

    @Test
    fun failedGlobalSearchIsAVisiblePoliteStatusInsteadOfAnEmptyResult() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchStatus(
                    isLoading = false,
                    hasError = true,
                    resultsEmpty = true
                )
            }
        }

        compose.onNodeWithTag("global_search_status")
            .assertTextContains("Не вдалося виконати пошук. Перевірте з’єднання й спробуйте ще раз.")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
        compose.onNodeWithText("В інших джерелах нічого не знайдено")
            .assertDoesNotExist()
    }

    @Test
    fun globalSearchLoadingHasOneReadableStatus() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchStatus(
                    isLoading = true,
                    hasError = false,
                    resultsEmpty = true
                )
            }
        }

        compose.onNodeWithTag("global_search_status")
            .assertTextContains("Шукаємо в усіх джерелах")
    }

    @Test
    fun globalSearchEmptyStateIsHonestAndReadable() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchStatus(
                    isLoading = false,
                    hasError = false,
                    resultsEmpty = true
                )
            }
        }

        compose.onNodeWithTag("global_search_status")
            .assertTextContains("В інших джерелах нічого не знайдено")
    }

    @Test
    fun recommendationMenuNamesItsWorkAndMeetsTheTouchTarget() {
        val recommendation = RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = "dark-matter",
                title = result.title,
                author = result.author
            ),
            score = 0.8,
            reasonTitle = "Рекурсія"
        )
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                RecommendedBookCard(rec = recommendation, onClick = {})
            }
        }

        compose.onNodeWithContentDescription("Дії з рекомендацією: ${result.title}")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("Не рекомендувати…").performClick()
        compose.onNodeWithText("Не рекомендувати: ${result.title}")
            .assertIsDisplayed()
        compose.onNodeWithText("Менше схожих на: ${result.title}")
            .assertIsDisplayed()
        compose.onNodeWithText("Не рекомендувати автора: ${result.author}")
            .assertIsDisplayed()
    }

    @Test
    fun workFeedCardIsOneWorkNodeAndItsCoverAndArrowAreDecorative() {
        val row = WorkFeedRow(
            workId = "dark-matter",
            mergeKey = result.mergeKey,
            title = result.title,
            author = result.author,
            coverImageUrl = null,
            addedAt = 1L,
            sourceCount = 2
        )
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                WorkFeedCard(row = row, onClick = {})
            }
        }

        compose.onNodeWithTag("work_feed_${row.workId}")
            .assertTextContains(row.title)
            .assertTextContains(row.author)
        compose.onNodeWithContentDescription(row.title, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Відтворити", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun workFeedFiltersExposeTheirSelectedState() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                WorkFeedFilters(
                    selectedGenreIds = setOf("fantasy"),
                    sortByTitle = true,
                    genres = listOf(GenreFacetOption("fantasy", "Фантастика", 1)),
                    onGenresChange = {},
                    onSortChange = {}
                )
            }
        }

        compose.onNodeWithTag("feed_filters").assertIsSelected().performClick()
        compose.onNodeWithTag("feed_genre_fantasy").assertIsSelected()
    }

    @Test
    fun searchControlsRemainReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    HomeHeader(
                        searchExpanded = true,
                        searchQuery = "",
                        onToggleSearch = {},
                        onRefresh = {},
                        onSearchQueryChange = {},
                        onCloseSearch = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("home_search_input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрити пошук")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun globalSearchWorkRemainsReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    GlobalSearchResultCard(result = result, onClick = {})
                }
            }
        }

        compose.onNodeWithTag("global_search_result_${result.key}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun sharedHomeCardsStayOneContextualTargetAtTwoHundredPercentFontScale() {
        val book = TestDataFactory.dataBooks().first()
        val series = CatalogSeries(
            title = "Темна вежа",
            url = "https://example.invalid/series/dark-tower",
            coverImageUrl = null
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        androidx.compose.foundation.layout.Column {
                            AudiobookListItem(book = book, onClick = {}, onPlayClick = {})
                            CatalogSeriesCard(series = series, onClick = {})
                            CollectionBookCard(result = result, onClick = {})
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("book_item_${book.id}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher("opens the named Work") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label ==
                        "Відкрити книгу: ${book.title}"
                }
            )
        compose.onNodeWithTag("catalog_series_${series.url.hashCode()}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher("opens the named Series") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label ==
                        "Відкрити серію: ${series.title}"
                }
            )
        compose.onNodeWithTag("collection_book_${result.key.hashCode()}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher("opens the named collection Work") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label ==
                        "Відкрити книгу: ${result.title}"
                }
            )
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(series.title, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(result.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun recommendationDisclosureOwnsTheModalFocusRoundTrip() {
        var accepted = 0
        var declined = 0
        compose.setContent {
            var visible by remember { mutableStateOf(false) }
            val triggerFocusRequester = remember { FocusRequester() }
            AudiobookTheme(darkTheme = true) {
                Box {
                    Column(
                        Modifier
                            .testTag("recommendation_disclosure_background")
                            .accessibilityModalBackground(visible)
                    ) {
                        Button(
                            onClick = { visible = true },
                            modifier = Modifier
                                .focusRequester(triggerFocusRequester)
                                .testTag("recommendation_disclosure_trigger")
                        ) {
                            Text("Докладніше")
                        }
                    }
                    RecommendationDisclosureDialog(
                        visible = visible,
                        returnFocusRequester = triggerFocusRequester,
                        onAgree = {
                            accepted++
                            visible = false
                        },
                        onDecline = {
                            declined++
                            visible = false
                        },
                        onDismiss = { visible = false }
                    )
                }
            }
        }

        compose.onNodeWithTag("recommendation_disclosure_trigger")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performClick()
        compose.onNodeWithTag("recommendation_disclosure_heading")
            .assertIsFocused()
        compose.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Спільне покращення рекомендацій"
            ),
            useUnmergedTree = true
        ).assertCountEquals(1)
        compose.onNodeWithTag("recommendation_disclosure_background", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
        compose.onNodeWithTag("recommendation_disclosure_decline")
            .performClick()
        compose.onNodeWithTag("recommendation_disclosure_trigger")
            .assertIsFocused()

        compose.onNodeWithTag("recommendation_disclosure_trigger")
            .performClick()
        compose.onNodeWithTag("recommendation_disclosure_heading")
            .assertIsFocused()
        compose.onNodeWithTag("recommendation_disclosure_agree")
            .performClick()
        compose.onNodeWithTag("recommendation_disclosure_trigger")
            .assertIsFocused()

        org.junit.Assert.assertEquals(1, declined)
        org.junit.Assert.assertEquals(1, accepted)
    }
}
