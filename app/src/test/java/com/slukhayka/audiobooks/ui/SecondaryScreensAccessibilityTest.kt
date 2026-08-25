package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.universe.SeriesRef
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.screens.BookListScreen
import com.slukhayka.audiobooks.ui.screens.CollectionsIndexContent
import com.slukhayka.audiobooks.ui.screens.PersonRow
import com.slukhayka.audiobooks.ui.screens.SeriesIndexContent
import com.slukhayka.audiobooks.ui.screens.SeriesUniverseHeader
import com.slukhayka.audiobooks.ui.screens.Top100Row
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecondaryScreensAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "secondary-accessibility",
        title = "Дуже довга назва аудіокниги для вузького екрана",
        author = "Леся Українка",
        narrator = "Тестовий виконавець",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Класика",
        sourceUrl = "https://example.invalid/secondary",
        totalDurationSeconds = 3_900
    )

    @Test
    fun pushedScaffoldNamesPaneFocusesHeadingAndProvidesUkrainianBackAction() {
        var backClicked = false
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        IndexScreenScaffold(
                            title = "Колекції",
                            onBackClick = { backClicked = true }
                        ) { padding ->
                            Box(Modifier.padding(padding))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("secondary_screen_pane", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Колекції"
                )
            )
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("secondary_screen_heading", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Назад")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(true, backClicked)
    }

    @Test
    fun genericBookListExposesErrorAsOnePoliteState() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookListScreen(
                            title = "Фентезі",
                            countLabel = null,
                            emptyMessage = "У цьому жанрі поки немає книг.",
                            isLoading = false,
                            books = emptyList(),
                            onBackClick = {},
                            onBookClick = {},
                            onPlayClick = {},
                            testTag = "genre_contract",
                            errorMessage = "Не вдалося завантажити книги жанру."
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Не вдалося завантажити книги жанру.")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    androidx.compose.ui.semantics.LiveRegionMode.Polite
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Помилка"
                )
            )
        composeTestRule.onNodeWithText("У цьому жанрі поки немає книг.")
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("secondary_screen_heading", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun genericBookListKeepsSuccessfulEmptySeparateFromFailure() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookListScreen(
                            title = "Книги авторки",
                            countLabel = null,
                            emptyMessage = "Для цієї людини поки немає книг.",
                            isLoading = false,
                            books = emptyList(),
                            onBackClick = {},
                            onBookClick = {},
                            onPlayClick = {},
                            testTag = "person_books_empty",
                            errorMessage = null
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Для цієї людини поки немає книг.")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    androidx.compose.ui.semantics.LiveRegionMode.Polite
                )
            )
        composeTestRule.onNodeWithText("Не вдалося завантажити книги.")
            .assertDoesNotExist()
    }

    @Test
    fun genericBookListNamesItsLoadingState() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookListScreen(
                            title = "Книги авторки",
                            countLabel = null,
                            emptyMessage = "Книг поки немає.",
                            isLoading = true,
                            books = emptyList(),
                            onBackClick = {},
                            onBookClick = {},
                            onPlayClick = {},
                            testTag = "person_books_loading"
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Завантаження вмісту")
            .assert(
                SemanticsMatcher.keyIsDefined(
                    SemanticsProperties.ProgressBarRangeInfo
                )
            )
    }

    @Test
    fun personRowIsOneContextualCardAtTwoHundredPercentText() {
        var clicked = false
        val person = CatalogPerson(
            name = "Олександр Довженко",
            path = "/xfsearch/avtor/dovzhenko/",
            bookCount = 12
        )
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        PersonRow(person = person, onClick = { clicked = true })
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("person_${person.path.hashCode()}")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains(person.name)
            .assertTextContains("12 книг")
            .performClick()
        composeTestRule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(1)
        assertEquals(true, clicked)
    }

    @Test
    fun rankedBookCardKeepsASeparateContextualPlayActionAndDecorativeCover() {
        var opened = false
        var played = false
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        Top100Row(
                            rank = 1,
                            book = book,
                            onClick = { opened = true },
                            onPlayClick = { played = true }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("top100_rank_1")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("1")
            .assertTextContains(book.title)
            .assertTextContains(book.author)
            .performClick()
        composeTestRule.onNodeWithContentDescription("Відтворити «${book.title}»")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(2)
        composeTestRule.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
        assertEquals(true, opened)
        assertEquals(true, played)
    }

    @Test
    fun seriesUniverseHeadingAndNavigationTargetsReflowAtTwoHundredPercentText() {
        val context = SeriesUniverseContext(
            universeName = "Земномор'я",
            seriesTitle = "Гробниці Атуану",
            position = 2,
            totalInUniverse = 3,
            precedes = SeriesRef("Чарівник Земномор'я", null),
            follows = SeriesRef("Останній берег", null)
        )
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        SeriesUniverseHeader(context = context, onOpenSeries = {})
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Всесвіт: «Земномор'я»")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithText("Передує: «Чарівник Земномор'я»")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Продовжує: «Останній берег»")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun seriesIndexContentRemainsReachableInBoundedTwoHundredPercentViewport() {
        val series = CatalogSeries(
            title = "Надзвичайно довга назва циклу",
            url = "https://example.invalid/series",
            coverImageUrl = null
        )
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        SeriesIndexContent(series = listOf(series), onSeriesClick = {})
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("catalog_series_${series.url.hashCode()}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("1 серій").assertIsDisplayed()
    }

    @Test
    fun collectionsContentRemainsReachableInBoundedTwoHundredPercentViewport() {
        val result = GlobalSearchResult(
            title = "Старий і море",
            author = "Ернест Гемінґвей",
            mergeKey = "старий-і-море|ернест-гемінґвей",
            coverImageUrl = null,
            sources = listOf(
                GlobalSearchSource(
                    sourceId = "test",
                    sourceName = "Тест",
                    url = "https://example.invalid/book"
                )
            )
        )
        val collection = CollectionMatcher.MatchedCollection(
            id = "test-collection",
            name = "Нобелівські лауреати",
            sourceNote = "fixture",
            books = listOf(result)
        )
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        CollectionsIndexContent(
                            collections = listOf(collection),
                            onBookClick = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Нобелівські лауреати", ignoreCase = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithTag("collection_book_${result.key.hashCode()}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }
}
