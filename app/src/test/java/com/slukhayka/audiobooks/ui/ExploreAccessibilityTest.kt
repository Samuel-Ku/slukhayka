package com.slukhayka.audiobooks.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.screens.AudiobookListItem
import com.slukhayka.audiobooks.ui.screens.CatalogRowHeader
import com.slukhayka.audiobooks.ui.screens.GlobalSearchResultCard
import com.slukhayka.audiobooks.ui.screens.GlobalSearchStatus
import com.slukhayka.audiobooks.ui.screens.HomeHeader
import com.slukhayka.audiobooks.ui.screens.RecommendedBookCard
import com.slukhayka.audiobooks.ui.screens.UnifiedCatalogCard
import com.slukhayka.audiobooks.ui.screens.WorkFeedCard
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilters
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
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
                    selectedGenre = "Фантастика",
                    genres = listOf("Усі", "Фантастика"),
                    onToggleSearch = {},
                    onRefresh = {},
                    onSearchQueryChange = {},
                    onCloseSearch = {},
                    onSelectGenre = {}
                )
            }
        }

        compose.onNodeWithTag("home_search_input")
            .assertTextContains("Пошук книги або автора")
            .assertIsDisplayed()
        compose.onNodeWithTag("home_genre_chip_Фантастика")
            .assertIsSelected()
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
                    sourceFilter = "4read",
                    genreFilter = "Фантастика",
                    sortByTitle = true,
                    genres = listOf("Фантастика"),
                    onSourceChange = {},
                    onGenreChange = {},
                    onSortToggle = {}
                )
            }
        }

        compose.onNodeWithTag("feed_sort_title").assertIsSelected()
        compose.onNodeWithTag("feed_source_4read").assertIsSelected()
        compose.onNodeWithTag("feed_genre_Фантастика").assertIsSelected()
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
                        selectedGenre = "Усі",
                        genres = listOf("Усі", "Фантастика"),
                        onToggleSearch = {},
                        onRefresh = {},
                        onSearchQueryChange = {},
                        onCloseSearch = {},
                        onSelectGenre = {}
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
}
