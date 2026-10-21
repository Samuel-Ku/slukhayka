package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.AdaptiveNavigationLayout
import com.slukhayka.audiobooks.AppBottomBarSlot
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.adaptive.WindowLayout
import com.slukhayka.audiobooks.ui.adaptive.isLandscapePhoneWindow
import com.slukhayka.audiobooks.ui.adaptive.showsPlayerPane
import com.slukhayka.audiobooks.ui.adaptive.windowLayoutFor
import com.slukhayka.audiobooks.ui.components.AppSettingsGear
import com.slukhayka.audiobooks.ui.components.AppTabHeader
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.components.PlayerPane
import com.slukhayka.audiobooks.ui.components.PosterWidth
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.screens.calculatePlayerProgress
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.components.WideDetailPane
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.libraryGridEntries
import com.slukhayka.audiobooks.ui.screens.libraryGridColumns
import com.slukhayka.audiobooks.ui.screens.libraryGridContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #900 — the frame evidence for the two finished slices: the ≥600 dp breakpoint
 * with the rail that replaces the bottom bar, and the Бібліотека list with the
 * opened book's page beside it.
 *
 * These are JVM Roborazzi renders, NOT device screenshots: `adb devices` had
 * no device attached when this was written, and ADR-0017's on-device check is
 * still owed for both slices. They pin the two navigation surfaces side by
 * side, and the two-pane split, so a review can see what the width changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LargeScreenSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Where the frames land.
     *
     * Default: the repository's golden set, exactly where every other
     * `ui/snapshots` test writes. The override exists so a review can drop the
     * ticket's evidence beside its other working frames
     * (`LARGE_SCREEN_SNAPSHOT_DIR=/home/stealth/cmp`) without changing the test.
     */
    private val frameDir: String =
        System.getenv("LARGE_SCREEN_SNAPSHOT_DIR") ?: "src/test/snapshots"

    @Test
    fun large_phone_bottom_bar() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { NavigationFrame(WindowLayout.COMPACT) }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-phone-bottom-bar.png"
        )
    }

    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_wide_window_navigation_rail() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { NavigationFrame(WindowLayout.EXPANDED) }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-navigation-rail.png"
        )
    }

    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_library_two_pane() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WideDetailPane(
                        list = { PanePlaceholder("Список книг", "заглушка") },
                        detail = { PanePlaceholder("Сторінка книги", "заглушка") }
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-library-two-pane.png"
        )
    }

    /**
     * The two-pane split as a FRAME, with both panes deliberately labelled as
     * placeholders: the real [com.slukhayka.audiobooks.ui.screens.LibraryScreen],
     * `GenreScreen`, `SeriesScreen` and `BookDetailScreen` need a ViewModel, and
     * a MainViewModel-composing test belongs to the Room/Robolectric partition,
     * not to the snapshot one. So these pin the SPLIT (list 0.4 left, detail
     * right, hairline between) and nothing about the panes' content.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_explore_two_pane() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WideDetailPane(
                        list = { PanePlaceholder("Добірка / жанр", "заглушка") },
                        detail = { PanePlaceholder("Картка твору", "заглушка") }
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-explore-two-pane.png"
        )
    }

    /**
     * #962 — the LANDSCAPE PHONE (905 × 411 dp), as a frame.
     *
     * This is the window the defect was found in and the one the golden set
     * never covered: at that width every wide rule fires, and at that height
     * the chrome they ask for does not fit. The frame pins the COMPACT header
     * `AppTabHeader` draws there — title and actions on one 56 dp line, no
     * subtitle — which is what hands the list its height back.
     *
     * Qualifiers carry `uk-rUA` explicitly and not the bare `uk` of
     * `robolectric.properties`: this class's fixtures are Ukrainian, and a
     * qualifier set without a locale pins EN chrome onto uk content.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w905dp-h411dp-560dpi", sdk = [36])
    fun landscape_phone_compact_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AppTabHeader(
                            title = "Мої книги",
                            subtitle = "12 книг · 12 год",
                            headingTestTag = "library_heading",
                            compact = isLandscapePhoneWindow(905, 411)
                        ) {
                            AppSettingsGear(onClick = {})
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/landscape-phone-compact-header.png"
        )
    }

    /** The same header on a portrait phone, where nothing changes. */
    @Test
    fun portrait_phone_full_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AppTabHeader(
                            title = "Мої книги",
                            subtitle = "12 книг · 12 год",
                            headingTestTag = "library_heading",
                            compact = isLandscapePhoneWindow(411, 905)
                        ) {
                            AppSettingsGear(onClick = {})
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/portrait-phone-full-header.png"
        )
    }

    /**
     * #1205 — the 840 dp line, as two frames of the SAME wiring: at 840 dp the
     * player takes the right-hand pane beside the rail and the content, and at
     * 839 dp the same player covers the window. Both are 1000 dp tall, so the
     * only thing between them is the width the owner's decision draws at.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_player_pane() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { PlayerBoundaryFrame(widthDp = 840) }
        }

        composeTestRule.onNodeWithTag("player_pane", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-player-pane.png"
        )
    }

    /** The same window one dp below the line: the player is the whole screen. */
    @Test
    @Config(qualifiers = "uk-rUA-w839dp-h1000dp-420dpi", sdk = [36])
    fun player_full_screen_below_the_line() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { PlayerBoundaryFrame(widthDp = 839) }
        }

        composeTestRule.onNodeWithTag("player_pane", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/player-full-screen-839dp.png"
        )
    }

    /**
     * #1205 decision 2 — the mini-player's own delta, and the interaction the
     * first version of this frame could not show (review S1): the bar in the
     * leading column AND the wide two-pane Медіатека in the SAME window.
     *
     * The bar's 360 dp column — the rail sits INSIDE it, not beside it — leaves
     * the Library's list pane 0.4 × (840 − 360) = 192 dp, far under the 284 dp
     * two tile columns need. The frame shows
     * what the app does about it — an adaptive grid draws ONE tile per row
     * instead of two squeezed ones — and the assertions pin what an image
     * cannot: every tile keeps at least the canonical poster's width and none
     * leaves the pane it was given.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_leading_mini_player_two_pane_library() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdaptiveNavigationLayout(
                        layout = WindowLayout.EXPANDED,
                        selectedTab = SelectedTab.LIBRARY,
                        bookDetailOpen = true,
                        onSelect = {},
                        miniPlayer = {
                            MiniPlayerBar(
                                playerState = miniPlayerState,
                                onPlayPauseClick = {},
                                onPreviousClick = {},
                                onSkipNextClick = {},
                                onCloseClick = {},
                                onBarClick = {}
                            )
                        }
                    ) {
                        WideDetailPane(
                            list = { LibraryTilesFrame() },
                            detail = { PanePlaceholder("Сторінка книги", "заглушка") }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("mini_player_bar", useUnmergedTree = true)
            .assertIsDisplayed()

        val pane = composeTestRule.onNodeWithTag("wide_detail_list", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val tiles = composeTestRule.onAllNodes(
            SemanticsMatcher("testTag starts with library_book_item_") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
                    ?.startsWith("library_book_item_") == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()

        assertTrue("no Медіатека tile was laid out in the pane at all", tiles.isNotEmpty())
        with(composeTestRule.density) {
            val posterPx = PosterWidth.toPx()
            val paneRightPx = pane.right.toPx()
            tiles.forEach { tile ->
                val bounds = tile.boundsInRoot
                assertTrue(
                    "a tile was squeezed to ${bounds.width} px, under the " +
                        "$posterPx px canonical poster, in a ${pane.left}..${pane.right} pane",
                    bounds.width >= posterPx - 1f
                )
                assertTrue(
                    "a tile left the list pane: tile=$bounds, pane right=${pane.right}",
                    bounds.right <= paneRightPx + 1f
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-leading-mini-player-library.png"
        )
    }

    /**
     * The real Медіатека tiles, drawn through the SHIPPED column rule
     * ([libraryGridColumns]) and the same content seam the screen calls — so the
     * frame cannot show a grid the app does not draw.
     */
    @Composable
    private fun LibraryTilesFrame() {
        val returnFocus = remember { FocusRequester() }
        LazyVerticalGrid(
            columns = libraryGridColumns(gridMode = true),
            modifier = Modifier
                .fillMaxSize()
                .testTag("library_grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            libraryGridContent(
                entries = libraryGridEntries(
                    browsing = false,
                    gridMode = true,
                    visible = frameLibrary,
                    continueBook = null,
                    denseTitle = "Усі"
                ),
                browsing = false,
                gridMode = true,
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

    /**
     * The books the Медіатека frame shows as tiles: the shared fixture's own,
     * capped so a growing fixture cannot turn the frame into a shelf list.
     */
    private val frameLibrary: List<LibraryBook> by lazy {
        buildLibraryBooks(
            books = TestDataFactory.dataBooks().take(4),
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        )
    }

    /**
     * The app's own wiring at this width, mirrored: [AdaptiveNavigationLayout]
     * leads with the rail, and INSIDE the content area it leads (exactly where
     * the Scaffold's padded content box sits) the player is either the right
     * pane ([PlayerPane], the slot the opened book's page holds) or — below the
     * line — the full-screen surface drawn over the whole window, which is what
     * MainActivity's outer `AnimatedVisibility` does.
     *
     * The real [PlayerScreenContent] is used, so the frame shows the player
     * itself and not a placeholder; the content beside it stays a placeholder
     * because a MainViewModel-composing frame belongs to the Room/Robolectric
     * partition (see the note above).
     */
    @Composable
    private fun PlayerBoundaryFrame(widthDp: Int) {
        val paneOpen = showsPlayerPane(widthDp = widthDp, playerOpen = true)

        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                AdaptiveNavigationLayout(
                    layout = windowLayoutFor(widthDp),
                    selectedTab = SelectedTab.LIBRARY,
                    bookDetailOpen = true,
                    onSelect = {}
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val content: @Composable () -> Unit = {
                            PanePlaceholder("Мої книги", "$widthDp dp · рейл ліворуч")
                        }
                        if (paneOpen) {
                            PlayerPane(
                                playerPaneTitle = "Плеєр",
                                content = content,
                                player = { PlayerBody() }
                            )
                        } else {
                            content()
                        }
                    }
                }
                if (!paneOpen) {
                    PlayerBody()
                }
            }
        }
    }

    /** The pure player surface with the frame's fixed fixture. */
    @Composable
    private fun PlayerBody() {
        PlayerScreenContent(
            playerState = playerState,
            book = playerBook,
            currentChapterTitle = playerChapters[1].title,
            progress = calculatePlayerProgress(
                playerChapters,
                playerState.currentChapterIndex,
                playerState.currentPositionMs,
                playerState.durationMs,
                emptyList()
            ),
            artworkAccent = androidx.compose.ui.graphics.Color(0xFF355D67),
            onArtworkLoaded = {},
            onDismiss = {},
            onToggleFavorite = {},
            onToggleDebug = {},
            onSeek = {},
            onBookSeek = {},
            onPreviousChapter = {},
            onBack = {},
            onPlayPause = {},
            onForward = {},
            onNextChapter = {},
            onUndoSeek = {},
            onSpeed = {},
            onTimer = {},
            onBookmark = {},
            onChapters = {}
        )
    }

    private val playerBook = AudiobookEntity(
        id = "wide-frame-book",
        title = "Нейромант",
        author = "Вільям Гібсон",
        narrator = "Олександр Завальський",
        description = "",
        coverDrawableRes = 0,
        genre = "Кіберпанк",
        sourceUrl = "https://example.invalid/book",
        isDownloaded = true,
        totalDurationSeconds = 1_980,
        totalChapters = 3
    )

    private val playerChapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity(
            id = "wide-frame-chapter-$index",
            bookId = playerBook.id,
            chapterIndex = index,
            title = "Розділ ${index + 1}. Зустріч у Чіба-сіті",
            durationSeconds = duration
        )
    }

    private val playerState = PlayerState(
        currentBook = playerBook,
        chapters = playerChapters,
        currentChapterIndex = 1,
        isPlaying = true,
        currentPositionMs = 320_000,
        durationMs = playerChapters[1].durationSeconds * 1_000,
        playbackSpeed = 1.25f,
        isOfflineMode = true
    )

    /** The mini-player's fixture: whatever the library test data starts with. */
    private val miniPlayerState = PlayerState(
        currentBook = TestDataFactory.dataBooks().first(),
        chapters = TestDataFactory.dataChapters(listOf(TestDataFactory.dataBooks().first())),
        currentChapterIndex = 0,
        isPlaying = true,
        currentPositionMs = 90_000,
        durationMs = 600_000
    )

    @Composable
    private fun PanePlaceholder(title: String, subtitle: String) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /**
     * The two surfaces as the app wires them: [AppBottomBarSlot] in the
     * Scaffold's bottomBar, [AdaptiveNavigationLayout] in its content. The
     * placeholder stands in for whichever screen the tab is showing — this
     * slice changes the chrome around the screens, not the screens.
     */
    @Composable
    private fun NavigationFrame(layout: WindowLayout) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AdaptiveNavigationLayout(
                        layout = layout,
                        selectedTab = SelectedTab.EXPLORE,
                        bookDetailOpen = false,
                        onSelect = {}
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Огляд",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(
                                    text = if (layout == WindowLayout.EXPANDED) {
                                        "840 dp · рейл ліворуч"
                                    } else {
                                        "411 dp · нижня навігація"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                AppBottomBarSlot(
                    layout = layout,
                    selectedTab = SelectedTab.EXPLORE,
                    onSelect = {}
                )
            }
        }
    }
}
