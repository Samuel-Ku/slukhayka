package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.components.CompactBookCard
import com.slukhayka.audiobooks.ui.library.ListenComposer
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.nextSeriesPartCaption
import com.slukhayka.audiobooks.ui.screens.ListenBlockHeader
import com.slukhayka.audiobooks.ui.screens.ListenBlockShelf
import com.slukhayka.audiobooks.ui.screens.ListenEmptyState
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
import com.slukhayka.audiobooks.ui.screens.OpenWebSourceRow
import com.slukhayka.audiobooks.ui.screens.RecentlyListenedRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the Слухати tab blocks (spec-9 T3): the hero resume
 * card, a recently-listened row and the fresh-install empty state. Pure
 * `@Composable` inputs — no `MainViewModel`, no Room.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ListenScreenBlocksSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0]
    private val progress = TestDataFactory.seedPlaybackProgress(listOf(book), chapterIndex = 2, positionSeconds = 420L)[0]

    @Composable
    private fun AtFontScale(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = fontScale),
            content = content
        )
    }
    // Spec-24 T1: the hero card shows the BOOK-level position — the chapters
    // before the current one (600 + 660 for fixture book 0) plus the in-
    // chapter offset (420) — the same cumulative value LibraryBook computes.
    private val cumulativePositionSeconds = 600L + 660L + 420L

    @Test
    fun hero_resume_card() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    ListenHeroCard(
                        book = book,
                        progress = progress,
                        cumulativePositionSeconds = cumulativePositionSeconds,
                        onResumeClick = {},
                        onBookClick = {}
                    )
                }
            }
        }
        // Spec-24 T5 (#166): the mid-book hero shows the CUMULATIVE percent
        // (1680 / 1980 s = 84 %), never the in-chapter offset (420 s = 21 %)
        // that would read as 0 % early in a long chapter.
        composeTestRule.onNodeWithText("84% · Залишилося 5 хв").assertExists()
        composeTestRule.onNodeWithText("21% · Залишилося 26 хв").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_hero_card.png"
        )
    }

    @Test
    fun hero_resume_card_light() {
        // Themes ticket (#37): the light scheme must render the migrated
        // reference screen, not just the primitives.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = false) {
                ListenSurface {
                    ListenHeroCard(
                        book = book,
                        progress = progress,
                        cumulativePositionSeconds = cumulativePositionSeconds,
                        onResumeClick = {},
                        onBookClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_hero_card_light.png"
        )
    }

    @Test
    fun recently_listened_row() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    RecentlyListenedRow(
                        book = book,
                        progress = progress,
                        cumulativePositionSeconds = cumulativePositionSeconds,
                        onClick = {},
                        onPlayClick = {}
                    )
                }
            }
        }
        // Spec-24 T5 (#166): the row shows the cumulative position (28:00 =
        // chapters before + in-chapter offset), not the bare 07:00 offset.
        composeTestRule.onNodeWithText("Розділ 3 · 28:00").assertExists()
        composeTestRule.onNodeWithText("Розділ 3 · 07:00").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_recent_row.png"
        )
    }

    // spec-28 (#191): each Listen block is a horizontal shelf of compact
    // posters — the block header plus the poster row, pinned as the shelf
    // form renders on the tab. US-3 (#199): with no playback rows every
    // cover stays CLEAN — the «без прогресу» pin of the progress hairline.
    @Test
    fun listen_block_shelf() {
        val books = TestDataFactory.dataBooks()
        val shelfBooks = buildLibraryBooks(
            books = books,
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        )
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    Column {
                        ListenBlockHeader(
                            title = "Щось коротке",
                            reason = "~1 год прослуховування",
                            blockId = ListenComposer.BlockId.SHORT,
                            onMoveUp = {},
                            onMoveDown = {},
                            onHide = {}
                        )
                        ListenBlockShelf(
                            books = shelfBooks,
                            onBookClick = {},
                            onNotInterested = {}
                        )
                    }
                }
            }
        }
        // Self-verifying on top of the image: titles and authors render, and
        // an unstarted book draws NO progress hairline (clean cover).
        composeTestRule.onNodeWithText("Нейромант").assertExists()
        composeTestRule.onNodeWithText("Вільям Гібсон").assertExists()
        composeTestRule.onNodeWithTag("compact_book_progress_${books.first().id}").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_block_shelf.png"
        )
    }

    // US-3 (spec-28 #199): a STARTED book on a shelf draws the thin progress
    // hairline along the cover's bottom edge, filled to the consumed
    // fraction; an unstarted book keeps a clean cover. The percent comes
    // from the library module's real playback rows (buildLibraryBooks).
    @Test
    fun listen_block_shelf_with_progress() {
        val books = TestDataFactory.dataBooks()
        val chapters = TestDataFactory.dataChapters(books)
        // The first book stays unstarted (no row) — a clean cover next to
        // the hairlines; the rest get distinct in-book positions so the
        // hairlines differ visibly.
        val progressList = TestDataFactory.seedPlaybackProgress(books, chapterIndex = 1, positionSeconds = 300L)
            .drop(1)
            .mapIndexed { index, row -> row.copy(currentPositionSeconds = 300L + 600L * index) }
        val shelfBooks = buildLibraryBooks(books, progressList, chapters.groupBy { it.bookId })
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    Column {
                        ListenBlockHeader(
                            title = "Далі по серії",
                            reason = "книги, які ви почали",
                            blockId = ListenComposer.BlockId.NEXT_IN_SERIES,
                            onMoveUp = {},
                            onMoveDown = {},
                            onHide = {}
                        )
                        ListenBlockShelf(
                            books = shelfBooks,
                            onBookClick = {},
                            onNotInterested = {}
                        )
                    }
                }
            }
        }
        // The unstarted first book draws nothing; the started ones do. The
        // hairline's tag is merged into the card's clickable semantics, so
        // the finder needs the unmerged tree.
        composeTestRule.onNodeWithTag("compact_book_progress_${books.first().id}", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("compact_book_progress_${books[1].id}", useUnmergedTree = true).assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_block_shelf_progress.png"
        )
    }

    // spec-28 (#201): the «Далі у серії» shelf restores the series context
    // that #192 carried away — each card names WHICH part is next. The
    // one-tap play triangle stays gone (ADR-0018 forbids it on the shelf
    // card): only the context returns.
    @Test
    fun listen_block_shelf_next_in_series() {
        val nextBook = TestDataFactory.dataBooks()[0]
            .copy(id = "next-volume", title = "Меч", author = "Максим Темний")
            .also {
                it.seriesTitle = "Максим Темний"
                it.seriesIndex = 2
            }
        val next = buildLibraryBooks(listOf(nextBook), emptyList(), emptyMap())
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    Column {
                        ListenBlockHeader(
                            title = "Продовжити серію",
                            reason = "Наступний том: Максим Темний",
                            blockId = ListenComposer.BlockId.NEXT_IN_SERIES,
                            onMoveUp = {},
                            onMoveDown = {},
                            onHide = {}
                        )
                        ListenBlockShelf(
                            books = next,
                            onBookClick = {},
                            onNotInterested = {},
                            captionFor = { entry -> nextSeriesPartCaption(entry) }
                        )
                    }
                }
            }
        }
        // Which part is next, right on the card.
        composeTestRule.onNodeWithText("Частина 2").assertExists()
        // No one-tap play triangle anywhere on the shelf (ADR-0018).
        composeTestRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_block_shelf_next_in_series.png"
        )
    }

    // The Listen shelf keeps the reversible «Не цікаво» dismiss on each
    // poster (wayfinder #62) — pinned so it cannot silently disappear.
    @Test
    fun compact_book_card_with_dismiss() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    CompactBookCard(book = book, onClick = {}, onNotInterested = {})
                }
            }
        }
        composeTestRule.onNodeWithTag("not_interested_${book.id}").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/compact_book_card_dismiss.png"
        )
    }

    // #372: pin the compact «Не цікаво» indicator at the reporter's font scale.
    @Test
    fun compact_book_card_dismiss_font_scale_1_15() {
        composeTestRule.setContent {
            AtFontScale(1.15f) {
                AudiobookTheme(darkTheme = true) {
                    ListenSurface {
                        CompactBookCard(book = book, onClick = {}, onNotInterested = {})
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("not_interested_${book.id}").assertExists()
        composeTestRule.onNodeWithTag("not_interested_visual_${book.id}", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/compact_book_card_dismiss_font_scale_1_15.png"
        )
    }

    // #372: pin a full shelf with compact «Не цікаво» indicators.
    @Test
    fun listen_block_shelf_font_scale_1_15() {
        val books = TestDataFactory.dataBooks()
        val shelfBooks = buildLibraryBooks(
            books = books,
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        )
        composeTestRule.setContent {
            AtFontScale(1.15f) {
                AudiobookTheme(darkTheme = true) {
                    ListenSurface {
                        Column {
                            ListenBlockHeader(
                                title = "Щось коротке",
                                reason = "~1 год прослуховування",
                                blockId = ListenComposer.BlockId.SHORT,
                                onMoveUp = {},
                                onMoveDown = {},
                                onHide = {}
                            )
                            ListenBlockShelf(
                                books = shelfBooks,
                                onBookClick = {},
                                onNotInterested = {}
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Нейромант").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_block_shelf_font_scale_1_15.png"
        )
    }

    @Test
    fun empty_state_with_ctas() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    ListenEmptyState(
                        onBrowseClick = {},
                        onImportClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_empty_state.png"
        )
    }

    @Test
    fun open_web_source_row() {
        // Spec-13 T3: the compact «більше книг на Sluhay →» entry row.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    OpenWebSourceRow(
                        displayName = "Sluhay",
                        onClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_open_web_source.png"
        )
    }
}

/** Same chrome as the other snapshot suites: scheme background, full size. */
@Composable
private fun ListenSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
