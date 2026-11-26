package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryContinueCard
import com.slukhayka.audiobooks.ui.screens.LibraryTabsWithSearch
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "uk")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryUxTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ownLibrarySearchNamesItsScopeWithoutEllipsis() {
        compose.setContent {
            AudiobookTheme {
                PaperInkThemedContent {
                    Box(Modifier.width(411.dp)) {
                        LibraryTabsWithSearch(true, false, {}, {}, {}, "", {})
                    }
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("У моїх книгах").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(results)
        }
        assertFalse(results.single().isLineEllipsized(0))
    }

    @Test
    fun resumeCardLeavesRoomForTheLibraryAndKeepsTwoDistinctActions() {
        val books = TestDataFactory.dataBooks().take(1)
        val book = buildLibraryBooks(
            books,
            TestDataFactory.seedPlaybackProgress(books, chapterIndex = 1, positionSeconds = 300L),
            TestDataFactory.dataChapters(books).groupBy { it.bookId }
        ).first()
        var opened = 0
        var resumed = 0
        compose.setContent {
            AudiobookTheme {
                PaperInkThemedContent {
                    Box(Modifier.width(360.dp)) {
                        LibraryContinueCard(book, { opened++ }, { resumed++ })
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("library_continue_card").getUnclippedBoundsInRoot()
        assertTrue("resume should fit into 220 dp, was ${card.bottom - card.top}", card.bottom - card.top <= 220.dp)
        compose.onNodeWithTag("library_book_item_${book.book.id}").performClick()
        compose.onNodeWithTag("library_continue_play").performClick()
        assertEquals(1, opened)
        assertEquals(1, resumed)
    }

    @Test
    fun largeTextKeepsResumeProgressAndPlayVisible() {
        val books = TestDataFactory.dataBooks().take(1)
        val book = buildLibraryBooks(
            books,
            TestDataFactory.seedPlaybackProgress(books, chapterIndex = 1, positionSeconds = 300L),
            TestDataFactory.dataChapters(books).groupBy { it.bookId }
        ).first()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme {
                    PaperInkThemedContent {
                        Box(Modifier.width(360.dp)) { LibraryContinueCard(book, {}, {}) }
                    }
                }
            }
        }
        compose.onNodeWithText("Прослухано 45%", useUnmergedTree = true).assertIsDisplayed()
        val play = compose.onNodeWithTag("library_continue_play").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(play.bottom - play.top >= 48.dp)
    }
}
