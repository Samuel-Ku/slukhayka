package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.screens.LibraryContinueCard
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1187 / ADR-0014 — the VISIBLE half of the honesty rule: with no total the
 * library renders no «0%» and no empty progress bar (the review's blocker),
 * while a book with a proven total keeps the numbers it always had.
 *
 * The unmerged tree is deliberate: the card is ONE a11y node
 * (`clearAndSetSemantics`), so the merged tree only carries the state
 * description. The unmerged tree is where the texts the listener actually sees
 * live — and if it did not, the known-total case below would fail instead of
 * passing silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryUnknownDurationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Listening, but no chapter was measurable: no total, `percent` = 0f. */
    private val unknownTotal: LibraryBook = run {
        val books = TestDataFactory.dataBooks().take(1).map { it.copy(totalDurationSeconds = 0L) }
        buildLibraryBooks(
            books = books,
            progressList = TestDataFactory.seedPlaybackProgress(
                books,
                chapterIndex = 0,
                positionSeconds = 3L
            ),
            chaptersByBook = mapOf(books.first().id to emptyList())
        ).first()
    }

    /** The known book of the a11y suite: 45 % of a measurable book. */
    private val knownTotal: LibraryBook = run {
        val books = TestDataFactory.dataBooks().take(1)
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
    fun anUnknownTotalRendersNoPercentAndNoBar() {
        render(unknownTotal)

        composeTestRule.onAllNodesWithText("0%", useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Прослухано 0%", useUnmergedTree = true).assertCountEquals(0)
        // Not even an empty bar: the only LinearProgressIndicator in either
        // surface is the «Продовжити» card's, and it was drawn at 0f.
        composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true
            )
            .assertCountEquals(0)
    }

    @Test
    fun aKnownTotalStillRendersItsPercentAndBar() {
        render(knownTotal)

        // F30a — the list row is the canonical cover row now: the progress is
        // the direction's lamp hairline (`library_row_progress_<id>`), not a
        // printed «45%» beside a timeline dot. The honest number still reaches
        // TalkBack through the row's state description; the bar itself is what
        // this test can hold without re-asserting a layout the golden owns.
        composeTestRule
            .onNodeWithTag("library_row_progress_${knownTotal.book.id}", useUnmergedTree = true)
            .assertExists()
        // Two honest bars now: the canonical row's lamp hairline and the
        // «Продовжити» card's. The dense row's own bar was a plain Box (no
        // semantics), which is why this counted ONE before F30a.
        composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true
            )
            .assertCountEquals(2)
    }

    /**
     * #1187 — the BROWSE TILE is a third surface, and the third review caught
     * it drawing the bar unconditionally. `clearAndSetSemantics` keeps the bar
     * out of the merged tree, so the test tag is the only handle.
     */
    @Test
    fun anUnknownTotalDrawsNoBarOnTheTile() {
        renderTile(unknownTotal)
        composeTestRule
            .onAllNodesWithTag("library_tile_progress_${unknownTotal.book.id}", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun aKnownTotalDrawsItsBarOnTheTile() {
        renderTile(knownTotal)
        composeTestRule
            .onAllNodesWithTag("library_tile_progress_${knownTotal.book.id}", useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun renderTile(book: LibraryBook) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryBookCard(book = book, grid = true, onClick = {})
            }
        }
    }

    private fun render(book: LibraryBook) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Column {
                    LibraryBookCard(
                        book = book,
                        grid = false,
                        onClick = {}
                    )
                    LibraryContinueCard(book = book, onOpen = {}, onPlay = {})
                }
            }
        }
    }
}
