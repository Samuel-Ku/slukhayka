package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.GlobalBookmarkItem
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.screens.LibraryFilterSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryImportSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryEmptyState
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixtureBook = run {
        val books = TestDataFactory.dataBooks()
        val chapters = TestDataFactory.dataChapters(books)
        buildLibraryBooks(
            books = books,
            progressList = TestDataFactory.seedPlaybackProgress(
                books,
                chapterIndex = 1,
                positionSeconds = 300L
            ),
            chaptersByBook = chapters.groupBy { it.bookId }
        ).first()
    }

    @Test
    fun libraryEntryIsOneContextualActionWithHonestState() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryBookCard(book = fixtureBook, grid = false, onClick = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Нейромант, Вільям Гібсон")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Нейромант, Вільям Гібсон")
                )
            )
        composeTestRule.onNodeWithTag("library_book_item_${fixtureBook.book.id}")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Прослухано 45 відсотків, залишилося 18 хв. " +
                        "Завантажено для прослуховування без інтернету. Джерело: 4read"
                )
            )
    }

    @Test
    fun statusControlsExposeSelection() {
        var selectedFilter: LibraryFilter? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LibraryStatusRow(
                        selected = LibraryFilter.LISTENING,
                        onSelect = { selectedFilter = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("library_status_listening")
            .assertIsSelected()
            .performClick()
        assertEquals(LibraryFilter.LISTENING, selectedFilter)
    }

    @Test
    fun sortControlsExposeSelectionWithoutDuplicateRadioNodes() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryFilterSheetContent(
                    filter = LibraryFilter.LOCAL,
                    sort = LibrarySort.TITLE,
                    gridMode = false,
                    onFilterChange = {},
                    onSortChange = {},
                    onGridModeChange = {}
                )
            }
        }

        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            useUnmergedTree = true
        ).assertCountEquals(LibrarySort.entries.size)
        composeTestRule.onNodeWithTag("library_sort_title").assertIsSelected()
    }

    @Test
    fun sheetsExposePaneTitlesAndHeadings() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryImportSheetContent(onImportFile = {}, onImportFolder = {})
            }
        }

        composeTestRule.onNodeWithTag("library_import_sheet_content")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        composeTestRule.onNodeWithText("Додати аудіо")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun emptyLibraryExplainsTheStateAndOffersTwoNextActions() {
        var imported = false
        var browsed = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryEmptyState(
                    onImportClick = { imported = true },
                    onBrowseClick = { browsed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Медіатека порожня").assertIsDisplayed()
        composeTestRule.onNodeWithText("Додайте власні аудіокниги з пристрою або знайдіть нові в каталозі.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_empty_import")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithTag("library_empty_browse")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(true, imported)
        assertEquals(true, browsed)
    }

    @Test
    fun bookmarkActionsNameBookChapterAndPosition() {
        val bookmark = TestDataFactory.seedBookmarks().first()
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalBookmarkItem(
                    bookmark = bookmark,
                    bookTitle = "Нейромант",
                    onJumpClick = {},
                    onDeleteClick = {}
                )
            }
        }

        val time = MainViewModel.formatTime(bookmark.timestampSeconds)
        composeTestRule.onNodeWithTag("bookmark_jump_${bookmark.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Перейти до закладки у книзі Нейромант, ${bookmark.chapterTitle}, $time")
                )
            )
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("bookmark_delete_${bookmark.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Видалити закладку з книги Нейромант, ${bookmark.chapterTitle}, $time")
                )
            )
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun twoHundredPercentFontKeepsLibraryCardReachable() {
        val density = composeTestRule.density.density
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(411.dp, 720.dp)) {
                        LibraryBookCard(book = fixtureBook, grid = false, onClick = {})
                    }
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Нейромант, Вільям Гібсон")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }
}
