package com.slukhayka.audiobooks.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1285 — the return-anchor UI journey.
 *
 * The projection tests pin WHICH entry is the anchor; this test pins that the
 * UI adapter actually performs the effect on that entry: the grid scrolls to it
 * and it receives focus. The anchor is deliberately a narration that is no
 * longer the card's primary voice — the order from the ticket — so the test
 * would fail if the screen went back to looking the book id up directly.
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

    @Test
    fun `a changed primary narration focuses the same Work card`() {
        // "b" is the card's voice now (further along), while the listener opened
        // "a" — the anchor must land on the Work card that owns "a".
        val rows = listOf(
            row("a", "k", narrator = "Іван", position = 10),
            row("b", "k", narrator = "Петро", position = 95)
        )
        val projection = projection(rows)
        val entries = projection.entries("")
        val target = projection.indexOfReturnAnchor(entries, "a")
        assertTrue("the Work card is still in the projection", target >= 0)
        var restored: String? = null

        composeTestRule.setContent {
            var anchor by remember { mutableStateOf<String?>("a") }
            val gridState = rememberLazyGridState()
            val bookRequester = remember { FocusRequester() }
            val headingRequester = remember { FocusRequester() }
            Box(Modifier.fillMaxSize()) {
                Text(
                    "Мої книги",
                    Modifier
                        .testTag("library_heading")
                        .focusRequester(headingRequester)
                        .focusable()
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().testTag("library_grid")
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                        val shown = entry.shownBook
                        Box(
                            Modifier
                                .testTag(if (shown != null) "card_${shown.book.id}" else "entry_$index")
                                .then(
                                    if (index == target) Modifier.focusRequester(bookRequester)
                                    else Modifier
                                )
                                .focusable()
                        ) {
                            Text(shown?.book?.title ?: "chrome")
                        }
                    }
                }
                LibraryReturnFocusEffect(
                    projection = projection,
                    entries = entries,
                    restoreFocusBookId = anchor,
                    activeTab = 0,
                    modalVisible = false,
                    hasBooks = rows.isNotEmpty(),
                    gridState = gridState,
                    bookFocusRequester = bookRequester,
                    headingFocusRequester = headingRequester,
                    onBookFocusRestored = { bookId ->
                        restored = bookId
                        anchor = null
                    }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("card_b").assertIsFocused()
        assertEquals("the token is consumed for the opened book", "a", restored)
    }

    @Test
    fun `a Work hidden by the filter keeps the heading fallback and the filter`() {
        val rows = listOf(
            row("a", "k1", title = "Кобзар", position = 90),
            row("b", "k2", title = "Лісова пісня", position = 10)
        )
        // The listener narrowed the list to «Кобзар»; the Work of "b" is hidden.
        val projection = projection(rows, query = "Кобзар")
        val entries = projection.entries("")
        assertEquals(
            "the hidden Work has no anchor to land on",
            -1,
            projection.indexOfReturnAnchor(entries, "b")
        )
        var restored: String? = null

        composeTestRule.setContent {
            val gridState = rememberLazyGridState()
            val bookRequester = remember { FocusRequester() }
            val headingRequester = remember { FocusRequester() }
            Box(Modifier.fillMaxSize()) {
                Text(
                    "Мої книги",
                    Modifier
                        .testTag("library_heading")
                        .focusRequester(headingRequester)
                        .focusable()
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().testTag("library_grid")
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                        val shown = entry.shownBook
                        Box(
                            Modifier
                                .testTag(if (shown != null) "card_${shown.book.id}" else "entry_$index")
                                .then(
                                    if (index == 0) Modifier.focusRequester(bookRequester)
                                    else Modifier
                                )
                                .focusable()
                        ) {
                            Text(shown?.book?.title ?: "chrome")
                        }
                    }
                }
                LibraryReturnFocusEffect(
                    projection = projection,
                    entries = entries,
                    restoreFocusBookId = "b",
                    activeTab = 0,
                    modalVisible = false,
                    hasBooks = rows.isNotEmpty(),
                    gridState = gridState,
                    bookFocusRequester = bookRequester,
                    headingFocusRequester = headingRequester,
                    onBookFocusRestored = { restored = it }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_heading").assertIsFocused()
        assertEquals("the token is consumed without an invented card", "b", restored)
    }
}
