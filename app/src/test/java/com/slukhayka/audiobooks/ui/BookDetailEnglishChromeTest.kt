package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.screens.BookDetailSourcePresentation
import com.slukhayka.audiobooks.ui.screens.ListenerReviewFormSheet
import com.slukhayka.audiobooks.ui.screens.NarrationRatingRow
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailCanonicalSummary
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailSourceSection
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookUniverseLine
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookmarkRowItem
import com.slukhayka.audiobooks.ui.screens.bookdetail.FavoriteButton
import com.slukhayka.audiobooks.ui.screens.bookdetail.NarrationRatingDeleteConfirmation
import com.slukhayka.audiobooks.ui.screens.bookdetail.NarrationRowCard
import com.slukhayka.audiobooks.ui.screens.bookdetail.SeriesPill
import com.slukhayka.audiobooks.ui.screens.bookdetail.WorkSourceRowCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T11 (#572) — the EN run of the book page must be clean.
 *
 * The ticket's fourth acceptance criterion is «UK-хром сторінки книги з
 * ресурсів; EN-прогін чистий». Asserting a handful of English labels would
 * still pass with one hardcoded Ukrainian string left in a corner, so every
 * test renders the real production composable with Latin-only fixture data and
 * fails on ANY Cyrillic in the semantics tree — text, content description,
 * state description and PaneTitle, from every root. A failure can then only
 * come from chrome the app itself produced, never from a title.
 *
 * Follows the #569 precedent ([LibraryEnglishChromeTest]).
 *
 * #986 — the walk itself is the shared [EnglishChromeWalk]; this slice used to
 * carry its own matcher-based walk, which missed PaneTitle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class BookDetailEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "en-book",
        title = "Dune",
        author = "Frank Herbert",
        narrator = "Frank Muller",
        description = "A desert planet and a spice worth empires.",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Science fiction",
        sourceUrl = "https://4read.org/dune",
        totalDurationSeconds = 36_000L,
        totalChapters = 12
    )

    private val profile = LibraryEntries.SourceProfile(
        sourceId = "4read",
        sourceName = "4read",
        url = book.sourceUrl,
        description = book.description,
        rating = 4.6,
        narrator = book.narrator,
        genres = listOf("Science fiction")
    )

    private val source = SourceCatalog.WorkSourceRow(
        sourceId = "4read",
        sourceName = "4read",
        url = book.sourceUrl,
        streamOnly = true
    )

    /** Every optional source-row slot filled, so every label is rendered. */
    private val richSource = BookDetailSourcePresentation(
        sourceId = "4read",
        name = "4read",
        url = book.sourceUrl,
        streamOnly = true,
        rating = 4.6,
        isCurrent = true,
        selectable = true,
        differingDescription = "Another blurb.",
        differingNarrator = "Kate Reading",
        differingGenres = listOf("Adventure", "Fantasy")
    )

    @Test
    fun summarySourceRowsAndIdentityChromeSpeakEnglish() {
        val presentation = bookDetailPresentation(book, listOf(profile), listOf(source))
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        BookDetailCanonicalSummary(presentation = presentation)
                        BookDetailSourceSection(presentation = presentation)
                        WorkSourceRowCard(source = richSource, workTitle = "Dune")
                        NarrationRowCard(
                            // A blank narrator is the honest boundary: the row
                            // must fall back to the EN resource, not «Невідомий
                            // читач».
                            sibling = book.copy(id = "en-sibling", narrator = ""),
                            average = 4.5,
                            voteCount = 3,
                            onClick = {}
                        )
                        SeriesPill("Saga", 2, onClick = {})
                        SeriesPill("Saga", 0, onClick = {})
                        BookUniverseLine("Arrakis")
                        FavoriteButton(isFavorite = false, onToggle = {}, bookTitle = "")
                    }
                }
            }
        }

        assertChromeHasNoCyrillic()
        assertTextExists("Author: Frank Herbert")
        assertTextExists("Narrated by: Frank Muller")
        // Two source rows are on screen (the section's row and the rich one),
        // so both chips legitimately render twice — hence "exists", not count.
        assertTextExists("Current")
        assertTextExists("Streaming only")
        assertTextExists("Source rating: ★ 4.6")
        assertTextExists("Narrated by (source data): Kate Reading")
        assertTextExists("Genres (source data): Adventure · Fantasy")
        assertTextExists("Different description from the source: Another blurb.")
        assertTextExists("Unknown narrator")
        assertTextExists("“Saga” • Book 2")
        assertTextExists("“Saga”")
        assertTextExists("Universe: “Arrakis”")
        // One source → the singular heading (the plural is pinned separately).
        assertTextExists("Source")
    }

    @Test
    fun bookmarkAndNarrationRatingRowsSpeakEnglish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        BookmarkRowItem(
                            bookmark = BookmarkEntity(
                                id = 1L,
                                bookId = book.id,
                                chapterIndex = 0,
                                chapterTitle = "Chapter one",
                                timestampSeconds = 754L,
                                note = "the good part"
                            ),
                            workTitle = "Dune",
                            onJumpClick = {},
                            onDeleteClick = {}
                        )
                        NarrationRatingRow(
                            average = 4.5,
                            voteCount = 3,
                            ownRating = null,
                            canRate = true,
                            onRate = {}
                        )
                    }
                }
            }
        }

        assertChromeHasNoCyrillic()
        assertTextExists("At 12:34: the good part")
        assertTextExists("Narration:")
        assertTextExists("Rate the narration")
    }

    @Test
    fun narrationRatingDeleteDialogSpeaksEnglish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                NarrationRatingDeleteConfirmation(
                    narrator = "A Narrator",
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }

        assertChromeHasNoCyrillic()
        assertTextExists("Delete the narration rating?")
        assertTextExists("Your stars for “A Narrator” will be removed from this page.")
    }

    @Test
    fun reviewFormSpeaksEnglish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenerReviewFormSheet(
                    bookTitle = "Dune",
                    editing = null,
                    editionOptions = listOf("A Narrator"),
                    defaultEditionTag = "",
                    onSave = { _, _, _ -> },
                    onDismiss = {}
                )
            }
        }

        assertChromeHasNoCyrillic()
        // The no-tag dropdown choice used to be the hardcoded «Не вказувати».
        assertTextExists("Do not specify")
    }

    /**
     * At least one node carries [text] in the EN run. Used instead of
     * `onNodeWithText` where the same label legitimately renders twice (two
     * source rows on screen) — an "exactly one" assertion would be a lie.
     */
    private fun assertTextExists(text: String) {
        val found = composeTestRule
            .onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("expected EN label \u00ab$text\u00bb in the book-page chrome", found.isNotEmpty())
    }

    /**
     * Any Cyrillic chrome anywhere in the semantics forest, through the shared
     * [EnglishChromeWalk] — including dialogs and sheets, which live in their
     * own roots, and PaneTitle, which the old matcher walk could not see.
     */
    private fun assertChromeHasNoCyrillic() {
        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "book page")
    }
}
