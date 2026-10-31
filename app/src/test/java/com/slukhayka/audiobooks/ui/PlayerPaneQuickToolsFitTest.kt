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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.screens.calculatePlayerProgress
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1629 — the player rendered in the RIGHT PANE of a landscape window.
 *
 * The pane is 0.6 of a 905 dp window and only 411 dp tall. At 543 dp (the
 * 905 dp window's own pane) the player's two-column layout still applies, but
 * its right control column gets (543 − 2×16)×1.15/2 ≈ 294 dp. Four equal quick
 * tools then get ≈73 dp each — less than «Закладка»/«Розділи» need — and the
 * labels were ellipsized on device («Тай…», «Зак…», «Роз…»).
 *
 * The fix under test: the quick-tool row must wrap into two rows when its own
 * measured width cannot hold four whole labels (same "measure, don't guess"
 * seam as #1627), and the artwork hero must stay inside the pane.
 *
 * #1708 — two columns are now chosen by the PLAYER's own width, not the
 * window's: below `PlayerTwoColumnMinWidth` (≈533 dp, the width at which the
 * right column holds the 288 dp transport row) the one-column branch is used,
 * and fontScale 2.0 never enters the two-column branch at all — it scrolls.
 * So the 543 dp pane is two columns while the real 486–495 dp pane is one,
 * and this class pins both, including the transport-target geometry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w905dp-h411dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerPaneQuickToolsFitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<App>()
    }

    private val book = AudiobookEntity(
        id = "pane-player-book",
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

    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity(
            id = "pane-player-chapter-$index",
            bookId = "pane-player-book",
            chapterIndex = index,
            title = "Розділ ${index + 1}. Зустріч у Чіба-сіті",
            durationSeconds = duration
        )
    }

    private val playerState = PlayerState(
        currentBook = book,
        chapters = chapters,
        currentChapterIndex = 1,
        isPlaying = true,
        currentPositionMs = 320_000,
        durationMs = chapters[1].durationSeconds * 1_000,
        playbackSpeed = 1.25f,
        isOfflineMode = true
    )

    private val paneHeightDp = 411.dp

    private fun showPane(paneWidthDp: androidx.compose.ui.unit.Dp, fontScale: Float = 1.0f) {
        composeTestRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = current.density, fontScale = fontScale),
                LocalOnBackPressedDispatcherOwner provides remember {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher = OnBackPressedDispatcher()
                        override val lifecycle: Lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
            ) {
                AudiobookTheme(darkTheme = true) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(modifier = Modifier.requiredSize(paneWidthDp, paneHeightDp)) {
                            PlayerScreenContent(
                                playerState = playerState,
                                book = book,
                                currentChapterTitle = chapters[1].title,
                                progress = calculatePlayerProgress(
                                    chapters,
                                    1,
                                    320_000,
                                    chapters[1].durationSeconds * 1_000,
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
                                onChapters = {},
                                seekSumLabel = null
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertChipsInsidePane() {
        for (tag in listOf("speed_chip", "sleep_timer_chip", "add_bookmark_chip", "chapters_chip")) {
            val bounds = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(
                "quick tool «$tag» не вміщається у панель: $bounds",
                bounds.top.value >= -1f && bounds.bottom.value <= paneHeightDp.value + 1f
            )
        }
    }

    private fun assertQuickToolLabelsWhole() {
        // The chip's own Text nodes, by tag — no dependency on the current
        // locale's wording. Speed has a value AND a label; the other three
        // show their label as the value.
        for (tag in listOf(
            "speed_chip_value",
            "sleep_timer_chip_value",
            "add_bookmark_chip_value",
            "chapters_chip_value"
        )) {
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val ellipsized = (0 until layout.lineCount).firstOrNull { layout.isLineEllipsized(it) }
            assertFalse(
                "підпис quick tool «$tag» обрізано в панелі плеєра " +
                    "(line=$ellipsized of ${layout.lineCount}, size=${layout.size}, " +
                    "constraints=${layout.layoutInput.constraints})",
                ellipsized != null
            )
        }
    }

    /**
     * #1708 — the narrow-short landscape pane scrolls, so "inside the pane" is
     * no longer the contract: the contract is "reachable". Scroll each primary
     * control into the viewport and demand it is actually displayed.
     */
    private fun assertPrimaryControlsReachable() {
        for (tag in listOf(
            "player_play_pause_button",
            "speed_chip",
            "sleep_timer_chip",
            "add_bookmark_chip",
            "chapters_chip"
        )) {
            composeTestRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    private val transportOrder = listOf(
        "player_previous_button",
        "player_rewind_button",
        "player_play_pause_button",
        "player_forward_button",
        "player_next_button"
    )

    /**
     * #1708 — the transport row is five fixed targets (4 × 52 dp + 80 dp =
     * 288 dp). With `Arrangement.SpaceBetween` a column narrower than that does
     * not clip them: it draws them OVER each other. The old window-wide
     * decision left the pane's control column ~261 dp and the targets
     * overlapped by 7–11 dp, so this is the assertion that was missing.
     */
    private fun assertTransportTargetsApart() {
        val targets = transportOrder.map { tag ->
            tag to composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        }
        targets.forEach { (tag, bounds) ->
            assertTrue(
                "$tag не має боксу: $bounds",
                bounds.right.value - bounds.left.value > 0f &&
                    bounds.bottom.value - bounds.top.value > 0f
            )
        }
        targets.zipWithNext { (tag, left), (nextTag, right) ->
            assertTrue(
                "транспортні цілі перекриваються: $tag закінчується на ${left.right}, " +
                    "$nextTag починається на ${right.left}",
                right.left.value >= left.right.value - 0.5f
            )
        }
    }

    @Test
    fun `543 dp pane keeps every quick tool whole at fontScale 1_0`() {
        showPane(paneWidthDp = 543.dp)
        assertQuickToolLabelsWhole()
        assertChipsInsidePane()
        // 543 dp is above the 533 dp two-column line: the right column gets
        // ≈294 dp, so the five 288 dp targets still fit apart.
        assertTransportTargetsApart()
    }

    @Test
    fun `543 dp pane keeps every quick tool whole at fontScale 1_15`() {
        showPane(paneWidthDp = 543.dp, fontScale = 1.15f)
        assertQuickToolLabelsWhole()
        assertChipsInsidePane()
    }

    // #1708 — renamed from «narrow 460 dp pane wraps the quick tools instead of
    // truncating»: one column no longer squeezes the row into a 2×2, so the
    // tools keep their labels in one row and the scroll brings them into reach.
    @Test
    fun `narrow 460 dp pane keeps the quick tools whole and scrolls them into reach`() {
        showPane(paneWidthDp = 460.dp, fontScale = 1.15f)
        assertQuickToolLabelsWhole()
        // One column since #1708 — the four tools get the full width, and the
        // transport row no longer has to fit a ~262 dp control column.
        assertTransportTargetsApart()
        assertPrimaryControlsReachable()
    }

    /**
     * #1708 — the REAL pane of an 840–905 dp window: 0.6 × (905 − 80) minus
     * the 1 dp hairline ≈ 494 dp. The old window-wide two-column decision ran
     * here and its right column was ~262 dp, 26 dp short of the transport row.
     * The player must now measure ITSELF, choose one column and keep the five
     * targets apart — with the whole row inside the pane.
     */
    @Test
    fun `the real 494 dp pane keeps the transport targets apart`() {
        showPane(paneWidthDp = 494.dp)
        assertTransportTargetsApart()
        // 494 × 411 is too short for the one-screen portrait column, so the
        // branch scrolls; what must hold is that every control is reachable.
        assertPrimaryControlsReachable()

        val first = composeTestRule.onNodeWithTag("player_previous_button", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val last = composeTestRule.onNodeWithTag("player_next_button", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the transport row must stay inside the 494 dp pane: first=$first last=$last",
            first.left.value >= -1f && last.right.value <= 494f + 1f
        )
    }

    /**
     * #1708 — fontScale 2.0 in the landscape pane. The two-column branch has
     * no scroll and a fixed 208 dp cover slot, so the enlarged title slid under
     * the top bar and the timer row fell out of the 411 dp. A large font must
     * use the portrait branch, which scrolls: every primary control is
     * reachable and nothing starts above the top bar.
     */
    @Test
    fun `landscape pane at fontScale 2_0 scrolls instead of sliding under the top bar`() {
        showPane(paneWidthDp = 494.dp, fontScale = 2f)

        val topBar = composeTestRule.onNodeWithTag("close_player_button", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        for (tag in listOf("player_cover", "player_context")) {
            val bounds = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "$tag заходить під верхню панель: $bounds, панель=$topBar",
                bounds.top.value >= topBar.bottom.value - 1f
            )
        }
        for (tag in listOf(
            "player_play_pause_button",
            "speed_chip",
            "sleep_timer_chip",
            "add_bookmark_chip",
            "chapters_chip"
        )) {
            composeTestRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun `the full landscape window keeps one row of four tools`() {
        showPane(paneWidthDp = 905.dp)
        assertQuickToolLabelsWhole()
        val speed = composeTestRule.onNodeWithTag("speed_chip").getUnclippedBoundsInRoot()
        val chapters = composeTestRule.onNodeWithTag("chapters_chip").getUnclippedBoundsInRoot()
        assertTrue(
            "a full-width landscape window must not wrap the tools: speed=$speed chapters=$chapters",
            kotlin.math.abs(speed.top.value - chapters.top.value) <= 1f
        )
    }

    @Test
    fun `the artwork hero stays inside the pane`() {
        showPane(paneWidthDp = 543.dp)
        val cover = composeTestRule.onNodeWithTag("player_cover").getUnclippedBoundsInRoot()
        assertTrue("cover not laid out: $cover", cover.right.value > 0f)
        assertTrue(
            "cover right edge ${cover.right} escapes the 543 dp pane",
            cover.right.value <= 543f + 1f
        )
        assertTrue("cover must start inside the pane: ${cover.left}", cover.left.value >= -1f)
    }
}
