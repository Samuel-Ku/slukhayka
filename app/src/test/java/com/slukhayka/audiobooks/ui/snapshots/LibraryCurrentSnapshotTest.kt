package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.AppBottomBar
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkTabHeader
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.projectLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryBookGrid
import com.slukhayka.audiobooks.ui.screens.LibraryHeaderActions
import com.slukhayka.audiobooks.ui.screens.LibraryResultsSummary
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.screens.libraryChrome
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Current filtered library assembled from the production chrome, projection,
 * results summary, grid, mini-player and navigation seams. This is a composed
 * fixture, not a MainActivity/device render: it omits loading, availability and
 * the off-screen rare-filter launcher. The fixture owns only data, state and the
 * Scaffold layout. In particular it does not recreate a search-results heading.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "pl-rPL-w400dp-h800dp-420dpi", sdk = [36])
class LibraryCurrentSnapshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val books: List<LibraryBook> by lazy {
        val template = TestDataFactory.dataBooks().first()
        buildLibraryBooks(
            books = listOf(
                template.copy(
                    id = "current-library-missing-art",
                    title = "Кобзар",
                    author = "Тарас Шевченко",
                    coverImageUrl = null,
                    coverDrawableRes = 0,
                    totalDurationSeconds = 3600L,
                    totalChapters = 3,
                    isDownloaded = false,
                ),
            ),
            progressList = emptyList(),
            chaptersByBook = emptyMap(),
        )
    }

    @Test
    fun current_filtered_library_ink() = capture(fontScale = 1f, ink = true)

    @Test
    fun current_filtered_library_ink_large_text() = capture(fontScale = 2f, ink = true)

    @Test
    fun current_filtered_library_paper_large_text() = capture(fontScale = 2f, ink = false)

    private fun capture(fontScale: Float, ink: Boolean) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PaperInkTheme(darkTheme = ink) { CurrentLibraryFixture(books) }
            }
        }
        compose.onNodeWithTag("library_results_summary").assertIsDisplayed()
        compose.onNodeWithTag("library_book_item_current-library-missing-art").assertIsDisplayed()
        val theme = if (ink) "ink" else "paper"
        val scale = if (fontScale == 2f) "200" else "100"
        compose.onRoot().captureRoboImage("src/test/snapshots/library_current_${theme}_${scale}.png")
    }
}

@Composable
private fun CurrentLibraryFixture(books: List<LibraryBook>) {
    var query by remember { mutableStateOf("Кобзар") }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    val importFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val stripScroll = rememberScrollState()
    val projection = projectLibraryBooks(
        books = books,
        filter = filter,
        sort = LibrarySort.RECENTLY_LISTENED,
        query = query,
        gridMode = false,
        denseTitle = stringResource(R.string.lib_search_section_title),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                MiniPlayerBar(
                    playerState = PlayerState(
                        currentBook = books.first().book,
                        chapters = (0..2).map { index ->
                            ChapterEntity(id = "current-chapter-$index", bookId = books.first().book.id,
                                chapterIndex = index, title = "Розділ ${index + 1}", durationSeconds = 1_200L)
                        },
                        currentPositionMs = 600_000L,
                        durationMs = 1_200_000L,
                    ),
                    onPlayPauseClick = {},
                    onBarClick = {},
                    onCloseClick = {},
                )
                AppBottomBar(selectedTab = SelectedTab.LIBRARY, onSelect = {})
            }
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            PaperInkTabHeader(
                title = stringResource(R.string.nav_library),
                subtitle = pluralStringResource(R.plurals.book_count, books.size, books.size),
                showBrandMark = true,
                headingTestTag = "library_heading",
                onSettingsClick = {},
                actions = {
                    LibraryHeaderActions(
                        bookmarksCount = 0,
                        peopleCount = 0,
                        menuOpen = menuOpen,
                        onMenuOpenChange = { menuOpen = it },
                        onOpenSection = {},
                        onAdd = {},
                        importFocusRequester = importFocus,
                    )
                },
            )
            // Matches LibraryScreen's private ChromeMaxHeight (280 dp) and
            // scroll boundary. Keep this fixture constraint aligned with that
            // production constant until a shared screen-content seam exists.
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                libraryChrome(
                    activeTab = 0,
                    sectionTitleRes = R.string.lib_section_books,
                    query = query,
                    onQueryChange = { query = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    statusRowScrollState = stripScroll,
                    filterFocusRequester = filterFocus,
                    onShowFilterSheet = {},
                    onBooks = {},
                    onShelves = {},
                    onSaved = {},
                    libraryBooks = books,
                    listenerCollections = emptyList(),
                    publishedCollections = emptyList(),
                    onOpenCollection = {},
                    onOpenPublishedCollection = {},
                    onRenamePseudonym = {},
                )
            }
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                LibraryStatusRow(selected = filter, onSelect = { filter = it }, scrollState = stripScroll)
            }
            LibraryResultsSummary(
                filter = filter,
                count = projection.shownBooks.size,
                onResetFilter = { filter = LibraryFilter.ALL },
            )
            LibraryBookGrid(
                entries = projection.entries(""),
                shape = projection.shape,
                availability = emptyMap(),
                downloadCounts = emptyMap(),
                restoreFocusBookId = null,
                bookReturnFocusRequester = returnFocus,
                awaitingSubmissionBookIds = emptySet(),
                watchingSubmissionBookIds = emptySet(),
                deferredPublicationBookIds = emptySet(),
                onBookClick = {},
                onPlayClick = {},
                onRecheck = {},
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
