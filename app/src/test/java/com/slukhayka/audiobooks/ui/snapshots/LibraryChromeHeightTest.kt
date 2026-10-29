package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkTabHeader
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.projectLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryHeaderActions
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.screens.libraryChrome
import com.slukhayka.audiobooks.ui.screens.libraryGridColumns
import com.slukhayka.audiobooks.ui.screens.libraryGridContent
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F30b — the audited chrome height, measured on the SHIPPED chrome seams.
 *
 * Audit 28.10.2026: the first «Книги» row sat ≈400 dp down (header 104 + «Мій
 * рік» 97 + tabs 52 + search 56 + chips 48 + a section header). Spec §5 target:
 * **≤240 dp (235)**. «Мій рік» moved to «Статистика», the search became a
 * permanently visible compact pill inside the tab row (ADR-0033 forbids a
 * reveal toggle), the chip strip lost its extra padding — and this test holds
 * the result as a number instead of trusting the golden's shape.
 *
 * The composition is the same seams the screen calls: `PaperInkTabHeader`,
 * `libraryChrome`, the pinned `LibraryStatusRow` and `libraryGridContent`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryChromeHeightTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The shipped projection of the fixture: the screen's entries come from it. */
    private val projection by lazy {
        projectLibraryBooks(
            books = books,
            filter = LibraryFilter.ALL,
            sort = LibrarySort.RECENTLY_LISTENED,
            query = "",
            gridMode = false,
            denseTitle = "Усі"
        )
    }

    private val books: List<LibraryBook> by lazy {
        val template = TestDataFactory.dataBooks().first()
        val ids = listOf("f30b-a", "f30b-b", "f30b-c")
        buildLibraryBooks(
            books = ids.mapIndexed { index, id ->
                template.copy(
                    id = id,
                    title = "Книга ${index + 1}",
                    author = "Автор ${index + 1}",
                    coverDrawableRes = 0,
                    coverImageUrl = null,
                    isDownloaded = false,
                    totalDurationSeconds = 3600L,
                    totalChapters = 3
                )
            },
            // No progress rows: the fixture is about chrome height, not bars.
            progressList = emptyList<PlaybackProgressEntity>(),
            chaptersByBook = emptyMap()
        )
    }

    @Test
    fun the_books_list_starts_within_240_dp() {
        showLibrary()

        val firstBook = composeTestRule
            .onNodeWithTag("library_book_item_${books.first().book.id}", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the first book row starts at ${firstBook.top} dp — the spec target " +
                "is ≤240 dp (235)",
            firstBook.top.value <= 240f
        )
        // The chrome band itself is what moved: the year hero is not on the
        // books tab any more, and the search has no reveal action (ADR-0033).
        composeTestRule.onNodeWithTag("library_year_hero").assertDoesNotExist()
        composeTestRule.onNodeWithTag("library_search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_search_toggle").assertDoesNotExist()
    }

    @Test
    fun the_header_band_is_at_most_240_dp_tall() {
        showLibrary()

        val chrome = composeTestRule.onNodeWithTag("library_chrome").getUnclippedBoundsInRoot()

        assertTrue(
            "the chrome band alone is ${chrome.height} dp tall — target ≤240 dp",
            chrome.height.value <= 240f
        )
    }

    /**
     * The smallest supported width must not squeeze either control below its
     * floor: the field keeps ≥140 dp (a pill+query is unusable narrower) and
     * the three tabs stay their own row instead of a 30 dp sliver each.
     */
    @Test
    @Config(
        qualifiers = "uk-rUA-w320dp-h2000dp-normal-notlong-notround-any-420dpi-keyshidden-nonav",
        sdk = [36]
    )
    fun a_320_dp_window_keeps_the_search_usable() {
        showLibrary()

        val search = composeTestRule.onNodeWithTag("library_search_zone")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the search zone is ${search.width} wide on 320 dp — floor is 140 dp",
            search.width.value >= 140f
        )
        composeTestRule.onNodeWithTag("library_search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_tab_books").assertIsDisplayed()
    }

    /**
     * 360 dp is BELOW the inline band's floor (three one-line tab labels + a
     * 140 dp field need ≈400 dp), so the field falls back to its own 48 dp line
     * instead of squeezing either control — and this test says the number that
     * fallback costs rather than pretending the 411 dp measurement covers it.
     *
     * The ≤240 dp target is a property of the inline band the audit measured
     * (Pixel 8, 411 dp). On the narrow fallback the first row lands ≈280 dp;
     * this pins ≤300 so a regression cannot quietly turn the fallback into a
     * second search row plus a second tab row.
     */
    @Test
    @Config(
        qualifiers = "uk-rUA-w360dp-h2000dp-normal-notlong-notround-any-420dpi-keyshidden-nonav",
        sdk = [36]
    )
    fun a_360_dp_window_keeps_the_search_visible_and_measures_its_fallback() {
        showLibrary()

        val search = composeTestRule.onNodeWithTag("library_search_zone")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the search zone is ${search.width} wide on 360 dp — floor is 140 dp",
            search.width.value >= 140f
        )
        composeTestRule.onNodeWithTag("library_search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_tab_books").assertIsDisplayed()

        val firstBook = composeTestRule
            .onNodeWithTag("library_book_item_${books.first().book.id}", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the 360 dp fallback puts the first book at ${firstBook.top} dp — " +
                "the documented fallback bound is ≤300 dp",
            firstBook.top.value <= 300f
        )
    }

    private fun showLibrary() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val importFocus = remember { FocusRequester() }
                        val filterFocus = remember { FocusRequester() }
                        val gridReturnFocus = remember { FocusRequester() }
                        val stripScroll = rememberScrollState()
                        PaperInkTabHeader(
                            title = "Мої книги",
                            subtitle = "3 книги",
                            headingTestTag = "library_heading",
                            onSettingsClick = {},
                            actions = {
                                LibraryHeaderActions(
                                    bookmarksCount = 0,
                                    peopleCount = 0,
                                    menuOpen = false,
                                    onMenuOpenChange = {},
                                    onOpenSection = {},
                                    onAdd = {},
                                    importFocusRequester = importFocus
                                )
                            }
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .testTag("library_chrome")
                        ) {
                            libraryChrome(
                                activeTab = 0,
                                sectionTitleRes = com.slukhayka.audiobooks.R.string.lib_section_books,
                                query = "",
                                onQueryChange = {},
                                filter = LibraryFilter.ALL,
                                onFilterChange = {},
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
                                onRenamePseudonym = {}
                            )
                        }
                        // The pinned strip, exactly as the screen pins it (#1384).
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            LibraryStatusRow(
                                selected = LibraryFilter.ALL,
                                onSelect = {},
                                scrollState = stripScroll,
                                trailing = {
                                    FilterChip(
                                        selected = false,
                                        onClick = {},
                                        label = { Text("Фільтр") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            labelColor = MaterialTheme.colorScheme.onSurface
                                        ),
                                        shape = RoundedCornerShape(AppDimens.RadiusPanel),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = false,
                                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                                            selectedBorderColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    )
                                }
                            )
                        }
                        LazyVerticalGrid(
                            columns = libraryGridColumns(gridMode = false),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("library_grid"),
                            contentPadding = PaddingValues(
                                start = AppDimens.PageSides,
                                end = AppDimens.PageSides,
                                top = 8.dp,
                                bottom = AppDimens.SpaceSection
                            ),
                            horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd),
                            verticalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd)
                        ) {
                            libraryGridContent(
                                // The SAME projection seam the screen calls: the
                                // redundant «Усі» header rule must be the shipped
                                // one, not a test-only filter.
                                entries = projection.entries(""),
                                gridMode = false,
                                availability = emptyMap(),
                                downloadCounts = emptyMap(),
                                restoreFocusBookId = null,
                                bookReturnFocusRequester = gridReturnFocus,
                                awaitingSubmissionBookIds = emptySet(),
                                watchingSubmissionBookIds = emptySet(),
                                deferredPublicationBookIds = emptySet(),
                                onBookClick = {},
                                onPlayClick = {},
                                onRecheck = {}
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
