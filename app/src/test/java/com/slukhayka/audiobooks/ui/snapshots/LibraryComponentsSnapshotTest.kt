package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.STATUS_FILTERS
import com.slukhayka.audiobooks.ui.screens.ClearCacheConfirmDialog
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.screens.LibraryFilterSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.screens.ListeningStatsCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose snapshot tests for the `LibraryScreen` composables (wayfinder #39).
 *
 * The full `LibraryScreen` is not snapshotted because it requires a concrete
 * `MainViewModel` instance; we exercise the building blocks instead:
 *
 * - [LibraryBookCard] in list and grid modes — the unified book card that
 *   now powers the whole Медіатека (progress, remaining time, source badge).
 * - [ListeningStatsCard] — the Статистика tab body.
 *
 * Every fixture comes from `TestDataFactory`; the card input is a
 * [LibraryBook] built through the same pure [buildLibraryBooks] the app uses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryComponentsSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val books = TestDataFactory.dataBooks()
    private val chapters = TestDataFactory.dataChapters(books)
    private val progress = TestDataFactory.seedPlaybackProgress(books, chapterIndex = 1, positionSeconds = 300L)
    private val libraryBooks = buildLibraryBooks(books, progress, chapters.groupBy { it.bookId })

    // Spec-15 T6: a book from a non-4read source, so the card's badge shows
    // the real source («Sluhay»), not a hardcoded «4read».
    private val sluhayBook = buildLibraryBooks(
        listOf(
            TestDataFactory.dataBooks()[0].copy(
                id = "sluhay-pasazhir",
                title = "Пасажир",
                sourceUrl = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
            )
        ),
        emptyList(),
        emptyMap()
    ).single()

    @Test
    fun book_card_list_mode() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    LibraryBookCard(book = libraryBooks[0], grid = false, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_book_card_list.png"
        )
    }

    @Test
    fun book_card_grid_mode() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    LibraryBookCard(book = libraryBooks[0], grid = true, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_book_card_grid.png"
        )
    }

    @Test
    fun book_card_sluhay_source_badge() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    LibraryBookCard(book = sluhayBook, grid = false, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_book_card_sluhay_badge.png"
        )
    }

    @Test
    fun stats_card_empty() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    ListeningStatsCard(listeningStats = emptyList(), totalBooks = 0)
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_stats_card_empty.png"
        )
    }

    @Test
    fun stats_card_populated() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    ListeningStatsCard(
                        listeningStats = TestDataFactory.seedListeningStats(),
                        totalBooks = TestDataFactory.BOOK_COUNT
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_stats_card_populated.png"
        )
    }

    // Spec-28 #193: the segmented status row — five one-tap statuses, the
    // selected one filled with the accent (the visible highlight of the
    // active non-default filter). «Слухаю» selected here.
    @Test
    fun status_row_segmented() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    LibraryStatusRow(selected = LibraryFilter.LISTENING, onSelect = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_status_row.png"
        )
    }

    // Spec-28 #193: «Нові» is the never-started status completing the
    // Нові/Слухаю/Завершені trilogy — pinned with it selected.
    @Test
    fun status_row_new_selected() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    LibraryStatusRow(selected = LibraryFilter.NEW, onSelect = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_status_row_new.png"
        )
    }

    // Spec-28 #193: the filter sheet body — the rare filters (Обрані /
    // Локальні / Онлайн), the six sort modes and the view toggle, with
    // «Локальні» and the default sort selected.
    @Test
    fun filter_sheet_content() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    LibraryFilterSheetContent(
                        filter = LibraryFilter.LOCAL,
                        sort = LibrarySort.RECENTLY_LISTENED,
                        gridMode = false,
                        onFilterChange = {},
                        onSortChange = {},
                        onGridModeChange = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_filter_sheet.png"
        )
    }

    // Spec-28 #193 AC: «one tap each» — tapping every status chip reports
    // exactly that status, including the off-screen tail of the scrollable row.
    @Test
    fun status_row_one_tap_per_status() {
        var selected: LibraryFilter? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    LibraryStatusRow(selected = LibraryFilter.ALL, onSelect = { selected = it })
                }
            }
        }

        STATUS_FILTERS.forEach { f ->
            composeTestRule.onNodeWithText(f.label)
                .performScrollTo()
                .performClick()
            assertEquals(f, selected)
        }
    }

    // Spec-27 (#184) BUG-001: the destructive-action confirm quotes the exact
    // scope (book count + bytes) and a destructive-colored confirm — deleting
    // every offline file is only ever one explicit step away, never a direct
    // tap on a neutral-looking button.
    @Test
    fun clear_cache_confirm_dialog() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ClearCacheConfirmDialog(
                    bookCount = 12,
                    bytes = 2_469_396_397L,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_clear_cache_dialog.png"
        )
    }
}

/**
 * Match the `LibraryScreen` chrome: scheme background, full-size column with
 * the same outer padding the screen would apply. Keeps the snapshot faithful
 * without dragging in `MainViewModel`.
 */
@Composable
private fun LibrarySurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(0.dp)) { content() }
    }
}
