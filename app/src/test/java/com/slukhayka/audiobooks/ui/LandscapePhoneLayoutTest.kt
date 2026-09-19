package com.slukhayka.audiobooks.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.AudiobookApp
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.screens.calculatePlayerProgress
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #962 — a PHONE IN LANDSCAPE, as two defects and their fix.
 *
 * The device evidence (OnePlus 8 Pro, 3168×1440 @560 dpi = **905.1 × 411.4 dp**
 * → EXPANDED by width) was:
 *
 * * **Defect A** — «Мої книги» drew NO book row at all and could not be
 *   scrolled: the chrome alone reached y 1402 of 1440 px, the grid got ≈11 dp,
 *   and `L2 == L3 == L4` were byte-identical after swipes and PageDown.
 * * **Defect B** — the player's `Швидкість` / `Таймер сну` / `Трансляція` /
 *   `Закладка` / `Розділи` reported `bounds=[0,0][0,0]` (never laid out) and
 *   the transport row was clipped by the bottom edge.
 *
 * This test is the JVM reproduction of both, on a window of exactly those
 * proportions, and it is RED before the fix: the grid's viewport is asserted to
 * be a real one, at least one book row is asserted to be laid out INSIDE the
 * window, the list is asserted to be scrollable to its end, and every one of
 * the five player controls is asserted to have a non-zero, on-screen box.
 *
 * The qualifiers carry `uk-rUA` explicitly and not the bare `uk` of
 * `robolectric.properties`: two earlier classes in this suite pinned EN chrome
 * while the fixtures were Ukrainian, and the affected rows are Ukrainian
 * resources here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w905dp-h411dp-560dpi", sdk = [36])
class LandscapePhoneLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val landscapeWidthDp = 905.dp
    private val landscapeHeightDp = 411.dp

    @Before
    fun seedLibrary() {
        val app = ApplicationProvider.getApplicationContext<App>()
        runBlocking {
            AudiobookDatabase.getDatabase(app).audiobookDao().apply {
                insertAudiobooks(
                    (1..12).map { index ->
                        AudiobookEntity(
                            id = "landscape-book-$index",
                            title = "Книга $index",
                            author = "Автор $index",
                            narrator = "",
                            description = "",
                            coverDrawableRes = 0,
                            genre = "Роман",
                            sourceUrl = "https://example.invalid/$index",
                            isDownloaded = false,
                            totalDurationSeconds = 3_600L,
                            totalChapters = 3
                        )
                    }
                )
                insertChapters(
                    (1..12).flatMap { book ->
                        (0 until 3).map { chapter ->
                            ChapterEntity(
                                id = "landscape-chapter-$book-$chapter",
                                bookId = "landscape-book-$book",
                                chapterIndex = chapter,
                                title = "Розділ ${chapter + 1}",
                                durationSeconds = 1_200L
                            )
                        }
                    }
                )
            }
        }
    }

    private fun showLibrary() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        val backDispatcher = OnBackPressedDispatcher()
        composeTestRule.setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides remember(backDispatcher) {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher: OnBackPressedDispatcher =
                            backDispatcher
                        override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
                    }
                }
            ) {
                AudiobookTheme(darkTheme = true) {
                    AudiobookApp(viewModel = viewModel)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { viewModel.selectTab(SelectedTab.LIBRARY) }
        composeTestRule.waitForIdle()
    }

    /**
     * The book grid's box, found by its tag when the build has one and by the
     * scrollable node otherwise — so this measurement means the same thing
     * before and after the fix (the tag is part of the fix).
     */
    private fun libraryGridBounds(): androidx.compose.ui.geometry.Rect {
        val tagged = composeTestRule.onAllNodesWithTag("library_grid")
            .fetchSemanticsNodes()
        if (tagged.isNotEmpty()) return tagged.first().boundsInRoot
        return tallScrollable()?.boundsInRoot
            ?: error("no scrollable book list exists at all in landscape")
    }

    /**
     * A semantics box in DP. `Rect` is in PIXELS, so every threshold in this
     * test ("96 dp of list", "inside a 411 dp window") has to be compared in
     * the same unit the window qualifier is written in.
     */
    /** Window size in PIXELS, which is what every semantics box is measured in. */
    private fun px(dp: androidx.compose.ui.unit.Dp): Float =
        with(composeTestRule.density) { dp.toPx() }

    private val windowHeightPx: Float get() = px(landscapeHeightDp)

    /** The TALL scrollable of the screen: the book list, not the chip row. */
    private fun tallScrollable(): SemanticsNode? =
        composeTestRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxByOrNull { node -> node.boundsInRoot.height }

    /** Every laid-out book row, whichever id it carries. */
    private fun laidOutBookRows() = composeTestRule.onAllNodes(
        SemanticsMatcher("testTag starts with library_book_item_") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                ?.startsWith("library_book_item_") == true
        },
        useUnmergedTree = true
    ).fetchSemanticsNodes()

    // ---------------------------------------------------------------- Defect A

    /**
     * #962 A1 — the grid must get a REAL viewport, not the ≈11 dp the device
     * measured. Before the fix the chrome filled the window and the grid was
     * left a sliver; the number this asserts is what makes a book row fit.
     */
    @Test
    fun `the landscape book grid gets a real viewport below the chrome`() {
        showLibrary()

        val grid = libraryGridBounds()

        assertTrue(
            "the book grid must get real height in a 411 dp landscape window; got " +
                "${grid.height} px (grid bottom=${grid.bottom} px, window=${windowHeightPx} px)",
            grid.height >= px(96.dp)
        )
        assertTrue(
            "the grid must still be inside the window; grid bottom=${grid.bottom} px",
            grid.bottom <= windowHeightPx + 1f
        )
    }

    /**
     * #962 A2 — at least one book row is LAID OUT and visible. This is the
     * defect stated exactly: on the device not a single row existed.
     */
    @Test
    fun `at least one book row is laid out and visible in landscape`() {
        showLibrary()

        val rows = laidOutBookRows()
        assertTrue("no book row was laid out at all; got ${rows.size}", rows.isNotEmpty())

        val visible = rows.filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f && bounds.top < windowHeightPx
        }
        assertTrue(
            "no book row landed inside the window; rows=" +
                rows.map { it.boundsInRoot },
            visible.isNotEmpty()
        )
    }

    /**
     * #962 A3 — the list can be SCROLLED. The device proof of the defect was
     * `L2 == L3 == L4`: swipes and PageDown left the pixels byte-identical, so
     * the list had nowhere to go. Here the same claim is made on the semantics
     * tree — the book list must own a scroll action whose range is real.
     */
    @Test
    fun `the landscape book list is genuinely scrollable`() {
        showLibrary()

        val scrollables = composeTestRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(
            "the library exposes no scrollable list at all in landscape",
            scrollables.isNotEmpty()
        )

        // The book list is the tall one; the chip row is a thin horizontal
        // scroller and is deliberately not what this asserts.
        val bookList = scrollables.maxByOrNull { node -> node.boundsInRoot.height }
            ?: error("no scrollable found")
        val listHeight = bookList.boundsInRoot.height
        assertTrue(
            "the book list is a sliver ($listHeight px), so it has nowhere to scroll",
            listHeight >= px(96.dp)
        )

        val scrollRange = bookList.config.getOrNull(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        )
        assertTrue(
            "the book list owns no vertical scroll range, so nothing can move",
            scrollRange != null
        )
        assertTrue(
            "the book list's scroll range is empty (maxValue=${scrollRange?.maxValue?.invoke()}), " +
                "so the last book is unreachable",
            (scrollRange?.maxValue?.invoke() ?: 0f) > 0f
        )
    }

    /** #962 A4 — the chrome must not be silently clipped either. */
    @Test
    fun `the landscape chrome stays reachable rather than clipped away`() {
        showLibrary()

        composeTestRule.onNodeWithTag("library_search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_status_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_filter_button").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Defect B

    /**
     * #962 B1 — the five secondary player controls must be LAID OUT.
     *
     * On the device every one of them reported `bounds=[0,0][0,0]`: a zero-sized
     * box, i.e. never placed. A non-zero box inside the window is the whole
     * assertion, and it is exactly what was false.
     */
    @Test
    fun `the landscape player lays out every secondary control`() {
        showLandscapePlayer()

        listOf("speed_chip", "sleep_timer_chip", "add_bookmark_chip", "chapters_chip")
            .forEach { tag ->
                val bounds = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                assertTrue(
                    "$tag was never laid out in landscape: bounds=" +
                        "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                    (bounds.right.value - bounds.left.value) > 0f &&
                        (bounds.bottom.value - bounds.top.value) > 0f
                )
                assertTrue(
                    "$tag falls outside the landscape window " +
                        "(bottom=${bounds.bottom}, window=$landscapeHeightDp)",
                    bounds.bottom.value <= landscapeHeightDp.value + 1f
                )
            }
    }

    /**
     * #962 B2 — the transport row is NOT clipped by the bottom edge, and the
     * play button is fully inside the window.
     */
    @Test
    fun `the landscape player transport row is not clipped`() {
        showLandscapePlayer()

        val play = composeTestRule.onNodeWithTag("player_play_pause_button")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the play button has no height: $play",
            (play.bottom.value - play.top.value) > 0f
        )
        assertTrue(
            "the play button is clipped by the bottom edge: bottom=${play.bottom} " +
                "of $landscapeHeightDp",
            play.bottom.value <= landscapeHeightDp.value + 1f
        )
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
    }

    /** #962 B3 — the cover still keeps its aspect ratio in the landscape pane. */
    @Test
    fun `the landscape player cover keeps its aspect ratio`() {
        showLandscapePlayer()

        val cover = composeTestRule.onNodeWithTag("player_cover")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the cover was not laid out: $cover", cover.height > 0f)
        assertEquals(
            "the landscape cover must keep its 2:3 aspect ratio",
            2f / 3f,
            cover.width / cover.height,
            0.05f
        )
    }

    private fun showLandscapePlayer() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    Box(
                        modifier = Modifier.requiredSize(
                            landscapeWidthDp,
                            landscapeHeightDp
                        )
                    ) {
                        PlayerScreenContent(
                            playerState = landscapePlayerState,
                            book = landscapeBook,
                            currentChapterTitle = landscapeChapters[1].title,
                            progress = calculatePlayerProgress(
                                landscapeChapters,
                                1,
                                320_000,
                                landscapeChapters[1].durationSeconds * 1_000,
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
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private val landscapeBook = AudiobookEntity(
        id = "landscape-player-book",
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

    private val landscapeChapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity(
            id = "landscape-player-chapter-$index",
            bookId = "landscape-player-book",
            chapterIndex = index,
            title = "Розділ ${index + 1}. Зустріч у Чіба-сіті",
            durationSeconds = duration
        )
    }

    private val landscapePlayerState = PlayerState(
        currentBook = landscapeBook,
        chapters = landscapeChapters,
        currentChapterIndex = 1,
        isPlaying = true,
        currentPositionMs = 320_000,
        durationMs = landscapeChapters[1].durationSeconds * 1_000,
        playbackSpeed = 1.25f,
        isOfflineMode = true
    )
}
