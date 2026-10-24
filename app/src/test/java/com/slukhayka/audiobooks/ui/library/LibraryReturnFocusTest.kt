package com.slukhayka.audiobooks.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.ui.screens.LibraryBookGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1285 — the return-anchor UI journey, driven through the SHIPPED renderer
 * ([LibraryBookGrid]) rather than a hand-built grid.
 *
 * The projection tests pin WHICH entry is the anchor; this test pins that the
 * production wiring performs the effect on that entry. It renders exactly what
 * the screen does — the resolved anchor's shown-book id is handed to the
 * renderer, the resolved index to the effect — so a renderer that attaches the
 * focus requester to the stale token instead of the drawn card would fail here
 * (it would throw "FocusRequester is not initialized"), which is the regression
 * the first review caught.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryReturnFocusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun entity(
        id: String,
        mergeKey: String,
        narrator: String,
        title: String
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = "Тарас Шевченко",
        narrator = narrator,
        description = "Фікстура",
        coverDrawableRes = 0,
        genre = "класика",
        sourceUrl = "https://fixtures.4read.org/$id.html"
    ).apply {
        this.mergeKey = mergeKey
        this.createdAt = 0L
    }

    private fun row(
        id: String,
        mergeKey: String,
        narrator: String = "Читець",
        title: String = "Кобзар",
        position: Long = 0L
    ) = LibraryBook(
        book = entity(id, mergeKey, narrator, title),
        progress = PlaybackProgressEntity(
            editionId = "edition-$id",
            bookId = id,
            currentPositionSeconds = position,
            lastListenedAt = position
        ),
        cumulativePositionSeconds = position,
        totalDurationSeconds = 100L
    )

    private fun projection(
        rows: List<LibraryBook>,
        query: String = ""
    ) = projectLibraryBooks(
        books = rows,
        filter = LibraryFilter.ALL,
        sort = LibrarySort.TITLE,
        query = query,
        gridMode = false,
        denseTitle = "Усі"
    )

    /**
     * The production wiring, as `LibraryScreen` does it: resolve the anchor once,
     * hand the renderer the anchor's shown-book id, hand the effect the index.
     */
    @Composable
    private fun Harness(
        rows: List<LibraryBook>,
        query: String,
        token: String,
        modalVisible: Boolean,
        onRestored: (String?) -> Unit
    ) {
        val projection = remember(rows, query) { projection(rows, query) }
        val entries = remember(projection) { projection.entries("") }
        val anchorIndex = remember(token, entries, projection) {
            projection.indexOfReturnAnchor(entries, token)
        }
        val anchorBookId = entries.getOrNull(anchorIndex)?.shownBook?.book?.id
        val gridState = rememberLazyGridState()
        val bookRequester = remember { FocusRequester() }
        val headingRequester = remember { FocusRequester() }

        Column(Modifier.fillMaxSize()) {
            Text(
                "Мої книги",
                Modifier
                    .testTag("library_heading")
                    .focusRequester(headingRequester)
                    .focusable()
            )
            LibraryBookGrid(
                entries = entries,
                shape = projection.shape,
                availability = emptyMap(),
                downloadCounts = emptyMap(),
                restoreFocusBookId = anchorBookId,
                bookReturnFocusRequester = bookRequester,
                awaitingSubmissionBookIds = emptySet(),
                watchingSubmissionBookIds = emptySet(),
                deferredPublicationBookIds = emptySet(),
                onBookClick = {},
                onPlayClick = {},
                onRecheck = {},
                gridState = gridState,
                modifier = Modifier.weight(1f)
            )
            LibraryReturnFocusEffect(
                anchorIndex = anchorIndex,
                restoreFocusBookId = token,
                activeTab = 0,
                modalVisible = modalVisible,
                hasBooks = rows.isNotEmpty(),
                gridState = gridState,
                bookFocusRequester = bookRequester,
                headingFocusRequester = headingRequester,
                onBookFocusRestored = onRestored
            )
        }
    }

    @Test
    fun `a changed primary narration focuses the same Work card`() {
        // "b" is the card's voice now (further along), while the listener opened
        // "a" — the anchor must land on the Work card that owns "a".
        val rows = listOf(
            row("a", "k", narrator = "Іван", position = 10),
            row("b", "k", narrator = "Петро", position = 95)
        )
        var restored: String? = null

        composeTestRule.setContent {
            Harness(rows = rows, query = "", token = "a", modalVisible = false) { restored = it }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_book_item_b").assertIsFocused()
        assertEquals("the token is consumed for the opened book", "a", restored)
    }

    @Test
    fun `a Work hidden by the filter keeps the heading fallback`() {
        val rows = listOf(
            row("a", "k1", title = "Кобзар", position = 90),
            row("b", "k2", title = "Лісова пісня", position = 10)
        )
        // The listener narrowed the list to «Кобзар»; the Work of "b" is hidden.
        val narrowed = projection(rows, query = "Кобзар")
        assertEquals(
            "the hidden Work has no anchor to land on",
            -1,
            narrowed.indexOfReturnAnchor(narrowed.entries(""), "b")
        )
        var restored: String? = null

        composeTestRule.setContent {
            Harness(rows = rows, query = "Кобзар", token = "b", modalVisible = false) {
                restored = it
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_heading").assertIsFocused()
        assertEquals("the token is consumed without an invented card", "b", restored)
    }

    @Test
    fun `an open details modal does not consume the return token`() {
        val rows = listOf(row("a", "k", position = 10))
        var restored: String? = null

        composeTestRule.setContent {
            Harness(rows = rows, query = "", token = "a", modalVisible = true) { restored = it }
        }

        composeTestRule.waitForIdle()
        assertNull("focus stays with the modal until the list is the visible surface", restored)
    }
}
