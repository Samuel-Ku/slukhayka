package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.universe.SeriesRef
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.screens.BookListScreen
import com.slukhayka.audiobooks.ui.screens.CollectionsIndexContent
import com.slukhayka.audiobooks.ui.screens.PersonRow
import com.slukhayka.audiobooks.ui.screens.PeopleContent
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
            bookCount = 12,
            role = PersonRole.AUTHOR
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
    fun peopleOwnerReturnsFocusToThePersonThatOpenedTheirBooks() {
        val people = (1..20).map { index ->
            CatalogPerson(
                name = "Людина $index",
                path = "/xfsearch/avtor/person-$index/",
                bookCount = index,
                role = PersonRole.AUTHOR
            )
        }
        val origin = people.last()

        composeTestRule.setContent {
            var childOpen by remember { mutableStateOf(false) }
            var returnPath by remember { mutableStateOf<String?>(null) }
            val listState = rememberLazyListState()
            AudiobookTheme(darkTheme = true) {
                if (childOpen) {
                    Button(
                        onClick = { childOpen = false },
                        modifier = Modifier.testTag("person_books_back")
                    ) {
                        Text("Назад")
                    }
                } else {
                    IndexScreenScaffold(title = "Автори", onBackClick = {}) { padding ->
                        PeopleContent(
                            people = people,
                            isLoading = false,
                            loadFailed = false,
                            peopleCountLabel = "20 авторів",
                            onPersonClick = { person ->
                                returnPath = person.path
                                childOpen = true
                            },
                            restoreFocusPersonPath = returnPath,
                            onPersonFocusRestored = { restoredPath ->
                                if (returnPath == restoredPath) returnPath = null
                            },
                            listState = listState,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("people_screen")
            .performScrollToNode(hasTestTag("person_${origin.path.hashCode()}"))
        composeTestRule.onNodeWithTag("person_${origin.path.hashCode()}").performClick()
        composeTestRule.onNodeWithTag("person_books_back").performClick()
        composeTestRule.onNodeWithTag("person_${origin.path.hashCode()}")
            .assertIsDisplayed()
            .assertIsFocused()
    }

    @Test
    fun sharedSecondaryBookListReturnsFocusToTheBookThatOpenedDetails() {
        val books = (1..20).map { index ->
            book.copy(id = "secondary-$index", title = "Книга $index")
        }
        val origin = books.last()

        composeTestRule.setContent {
            var detailOpen by remember { mutableStateOf(false) }
            var returnBookId by remember { mutableStateOf<String?>(null) }
            val listState = rememberLazyListState()
            AudiobookTheme(darkTheme = true) {
                if (detailOpen) {
                    Button(
                        onClick = { detailOpen = false },
                        modifier = Modifier.testTag("book_detail_back")
                    ) {
                        Text("Назад")
                    }
                } else {
                    BookListScreen(
                        title = "Фентезі",
                        countLabel = "20 книг",
                        emptyMessage = "Книг немає",
                        isLoading = false,
                        books = books,
                        onBackClick = {},
                        onBookClick = { bookId ->
                            returnBookId = bookId
                            detailOpen = true
                        },
                        onPlayClick = {},
                        testTag = "secondary_book_list",
                        restoreFocusBookId = returnBookId,
                        onBookFocusRestored = { restoredId ->
                            if (returnBookId == restoredId) returnBookId = null
                        },
                        listState = listState
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("secondary_book_list")
            .performScrollToNode(hasTestTag("book_item_${origin.id}"))
        composeTestRule.onNodeWithTag("book_item_${origin.id}").performClick()
        composeTestRule.onNodeWithTag("book_detail_back").performClick()
        composeTestRule.onNodeWithTag("book_item_${origin.id}")
            .assertIsDisplayed()
            .assertIsFocused()
    }

    @Test
    fun secondaryBookListDoesNotConsumeReturnTokenWhenOriginIsUnavailable() {
        var consumed: String? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookListScreen(
                    title = "Фентезі",
                    countLabel = "1 книга",
                    emptyMessage = "Книг немає",
                    isLoading = false,
                    books = listOf(book),
                    onBackClick = {},
                    onBookClick = {},
                    onPlayClick = {},
                    testTag = "secondary_book_list_missing_origin",
                    restoreFocusBookId = "missing-book",
                    onBookFocusRestored = { consumed = it }
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(null, consumed)
        composeTestRule.onNodeWithTag("secondary_screen_heading", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun seriesIndexOwnerReturnsFocusToTheSeriesThatOpenedItsPage() {
        val series = (1..20).map { index ->
            CatalogSeries(
                title = "Серія $index",
                url = "https://example.invalid/series-$index",
                coverImageUrl = null
            )
        }
        val origin = series.last()

        composeTestRule.setContent {
            var childOpen by remember { mutableStateOf(false) }
            var returnUrl by remember { mutableStateOf<String?>(null) }
            val gridState = rememberLazyGridState()
            AudiobookTheme(darkTheme = true) {
                if (childOpen) {
                    Button(
                        onClick = { childOpen = false },
                        modifier = Modifier.testTag("series_page_back")
                    ) {
                        Text("Назад")
                    }
                } else {
                    IndexScreenScaffold(title = "Серії", onBackClick = {}) { padding ->
                        SeriesIndexContent(
                            series = series,
                            onSeriesClick = { selected ->
                                returnUrl = selected.url
                                childOpen = true
                            },
                            restoreFocusSeriesUrl = returnUrl,
                            onSeriesFocusRestored = { restoredUrl ->
                                if (returnUrl == restoredUrl) returnUrl = null
                            },
                            gridState = gridState,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("series_index_screen")
            .performScrollToNode(hasTestTag("catalog_series_${origin.url.hashCode()}"))
        composeTestRule.onNodeWithTag("catalog_series_${origin.url.hashCode()}").performClick()
        composeTestRule.onNodeWithTag("series_page_back").performClick()
        composeTestRule.onNodeWithTag("catalog_series_${origin.url.hashCode()}")
            .assertIsDisplayed()
            .assertIsFocused()
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
