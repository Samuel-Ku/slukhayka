package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibraryGridEntry
import com.slukhayka.audiobooks.ui.library.UK_REMAINING_TIME_UNITS
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.formatRemainingTime
import com.slukhayka.audiobooks.ui.library.libraryGridEntries
import com.slukhayka.audiobooks.ui.components.AppTabHeader
import com.slukhayka.audiobooks.ui.screens.LibraryHeaderActions
import com.slukhayka.audiobooks.ui.screens.LibrarySearchField
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.screens.libraryGridContent
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The redesigned Медіатека (v1.5 review) as golden images.
 *
 * The book area is rendered through the very same [libraryGridContent] seam the
 * screen calls, so these goldens cannot document a layout the app does not
 * ship — the only chrome this test re-states is the permanently visible search
 * field, the one-line status row and the filter chip beside it.
 *
 * Run:    ./gradlew testDebugUnitTest --tests "*LibraryRedesignSnapshotTest" \
 *           -Proborazzi.test.record=true
 * Output: app/src/test/snapshots/library_redesign_*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryRedesignSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun browsing_sections_hero_and_shelf() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryRedesignScreen(
                    entries = libraryGridEntries(
                        browsing = true,
                        gridMode = false,
                        visible = previewLibrary,
                        continueBook = previewLibrary.firstOrNull { it.isListening },
                        denseTitle = "Усі"
                    )
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_redesign_browsing.png"
        )
    }

    @Test
    fun narrowed_down_dense_rows() {
        val listening = previewLibrary.filter { it.isListening }
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryRedesignScreen(
                    selected = LibraryFilter.LISTENING,
                    subtitle = "Фільтр: Слухаю · ${listening.size}",
                    entries = libraryGridEntries(
                        browsing = false,
                        gridMode = false,
                        visible = listening,
                        continueBook = null,
                        denseTitle = "Слухаю",
                        denseTrailing = "разом " + formatRemainingTime(
                            listening.sumOf { it.remainingSeconds },
                            UK_REMAINING_TIME_UNITS
                        )
                    ),
                    browsing = false
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_redesign_dense.png"
        )
    }

    @Test
    fun grid_view_sections() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryRedesignScreen(
                    entries = libraryGridEntries(
                        browsing = true,
                        gridMode = true,
                        visible = previewLibrary,
                        continueBook = previewLibrary.firstOrNull { it.isListening },
                        denseTitle = "Усі"
                    ),
                    gridMode = true
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_redesign_grid.png"
        )
    }
}

/** The screen's chrome, then the shared [libraryGridContent] book area. */
@Composable
private fun LibraryRedesignScreen(
    entries: List<LibraryGridEntry>,
    selected: LibraryFilter = LibraryFilter.ALL,
    subtitle: String = "${previewLibrary.size} книг",
    gridMode: Boolean = false,
    browsing: Boolean = true
) {
    val returnFocus = remember { FocusRequester() }
    val importFocus = remember { FocusRequester() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The real header, including the «+ Додати» corner and the ⋮ menu.
            AppTabHeader(
                title = "Мої книги",
                subtitle = subtitle,
                headingTestTag = "library_heading",
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
            // ADR-0033 amended 2026-09-18: the field is permanently visible, so
            // it belongs to the chrome these goldens restate.
            LibrarySearchField(query = "", onQueryChange = {})
            LibraryStatusRow(
                selected = selected,
                onSelect = {},
                trailing = {
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("Фільтр") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier.padding(0.dp)
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
            LazyVerticalGrid(
                columns = if (gridMode) GridCells.Fixed(2) else GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppDimens.PageSides,
                    end = AppDimens.PageSides,
                    top = AppDimens.SpaceSm,
                    bottom = AppDimens.SpaceSection
                ),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd)
            ) {
                libraryGridContent(
                    entries = entries,
                    browsing = browsing,
                    gridMode = gridMode,
                    availability = emptyMap(),
                    downloadCounts = emptyMap(),
                    restoreFocusBookId = null,
                    bookReturnFocusRequester = returnFocus,
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

// ───────────────────────────────────────────────────────────────────────────
// Fixtures: the same shape of library the redesign was drawn against.
// ───────────────────────────────────────────────────────────────────────────

private data class PreviewSpec(
    val id: String,
    val title: String,
    val author: String,
    val genre: String,
    val totalSeconds: Long,
    val positionSeconds: Long,
    val chapterIndex: Int,
    val completed: Boolean = false,
    val downloaded: Boolean = false
)

private val previewSpecs = listOf(
    PreviewSpec("p1", "Сходами вниз", "Дон Куін", "детектив", 36_000L, 12_240L, 4),
    PreviewSpec("p2", "Нейромант", "Вільям Ґібсон", "кіберпанк", 36_000L, 4_320L, 2, downloaded = true),
    PreviewSpec("p3", "Місто", "Валер'ян Підмогильний", "класика", 36_000L, 25_920L, 16, downloaded = true),
    PreviewSpec("p4", "Дюна", "Френк Герберт", "фантастика", 39_600L, 19_008L, 13),
    PreviewSpec("p5", "1984", "Джордж Орвелл", "антиутопія", 28_800L, 0L, 0),
    PreviewSpec("p6", "451° за Фаренгейтом", "Рей Бредбері", "фантастика", 19_800L, 0L, 0),
    PreviewSpec("p7", "Тіні забутих предків", "Михайло Коцюбинський", "класика", 11_520L, 0L, 0, downloaded = true),
    PreviewSpec("p8", "Майстер і Маргарита", "Михайло Булгаков", "роман", 64_800L, 64_800L, 20, completed = true),
    PreviewSpec("p9", "Кобзар", "Тарас Шевченко", "класика", 14_400L, 14_400L, 8, completed = true, downloaded = true)
)

private val previewLibrary: List<LibraryBook> by lazy {
    val template = TestDataFactory.dataBooks().first()
    val entities = previewSpecs.mapIndexed { index, spec ->
        template.copy(
            id = spec.id,
            title = spec.title,
            author = spec.author,
            genre = spec.genre,
            coverImageUrl = null,
            coverDrawableRes = 0,
            isDownloaded = spec.downloaded,
            totalDurationSeconds = spec.totalSeconds,
            totalChapters = 14
        )
    }
    val progress = previewSpecs.mapNotNull { spec ->
        if (spec.positionSeconds <= 0L && !spec.completed) return@mapNotNull null
        PlaybackProgressEntity(
            editionId = "edition-${spec.id}",
            bookId = spec.id,
            currentChapterIndex = spec.chapterIndex,
            currentPositionSeconds = spec.positionSeconds,
            lastListenedAt = TestDataFactory.FIXED_CLOCK_MS,
            isCompleted = spec.completed
        )
    }
    buildLibraryBooks(entities, progress, emptyMap())
}
