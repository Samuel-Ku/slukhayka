package com.slukhayka.audiobooks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-52 US28 / #1174 — the «Покинуто» badge in the Медіатека.
 *
 * The AC asks for a VISIBLE badge and for the a11y contract, so both halves are
 * pinned: the chip really lands on the card, and the mark is SPOKEN. The card
 * clears its descendants' semantics (`clearAndSetSemantics`), so the visible
 * chip is queried in the UNMERGED tree while its label is read off the card's
 * own state description — which is exactly what TalkBack announces.
 *
 * A finished book never shows the mark, even while its stored pass still says
 * ABANDONED: completion wins over the mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryAbandonedBadgeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val started: LibraryBook = run {
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

    private val badgeTag: String get() = "library_abandoned_badge_${started.book.id}"

    private fun card(book: LibraryBook, grid: Boolean = false, abandoned: Boolean = true) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LibraryBookCard(book = book, grid = grid, abandoned = abandoned, onClick = {})
                }
            }
        }
    }

    /** What TalkBack reads for this book: the card's own (cleared) state. */
    private fun spokenState(bookId: String): String = composeTestRule
        .onNodeWithTag("library_book_item_$bookId")
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.StateDescription)
        .orEmpty()

    @Test
    fun `an abandoned book wears the mark and says it to TalkBack`() {
        card(started)

        composeTestRule.onNodeWithTag(badgeTag, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Покинуто", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "the card's state description is what TalkBack reads: ${spokenState(started.book.id)}",
            spokenState(started.book.id).contains("Покинуто")
        )
    }

    @Test
    fun `the grid tile wears the mark over its cover`() {
        card(started, grid = true)

        composeTestRule.onNodeWithTag(badgeTag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a finished book never wears the mark`() {
        val finished = started.copy(progress = started.progress?.copy(isCompleted = true))

        card(finished)

        composeTestRule.onAllNodesWithTag(badgeTag, useUnmergedTree = true).assertCountEquals(0)
        assertFalse(spokenState(finished.book.id).contains("Покинуто"))
    }

    @Test
    fun `a card without the mark shows nothing`() {
        card(started, abandoned = false)

        composeTestRule.onAllNodesWithTag(badgeTag, useUnmergedTree = true).assertCountEquals(0)
        assertFalse(spokenState(started.book.id).contains("Покинуто"))
    }
}
