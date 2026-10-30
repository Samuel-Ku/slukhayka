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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * The pane is 0.6 of a 905 dp window (543 dp) and only 411 dp tall, so the
 * player's landscape two-column layout still applies, but its right control
 * column gets (543 − 2×16)×1.15/2 ≈ 294 dp. Four equal quick tools then get
 * ≈73 dp each — less than «Закладка»/«Розділи» need — and the labels were
 * ellipsized on device («Тай…», «Зак…», «Роз…»).
 *
 * The fix under test: the quick-tool row must wrap into two rows when its own
 * measured width cannot hold four whole labels (same "measure, don't guess"
 * seam as #1627), and the artwork hero must stay inside the pane.
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

    private fun assertQuickToolLabelsWhole() {
        // The chip's own Text nodes, by tag — no dependency on the current
        // locale's wording. Speed has a value AND a label; the other three
        // show their label as the value.
        for (tag in listOf(
            "speed_chip_value",
            "speed_chip_label",
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

    @Test
    fun `543 dp pane keeps every quick tool whole at fontScale 1_0`() {
        showPane(paneWidthDp = 543.dp)
        assertQuickToolLabelsWhole()
    }

    @Test
    fun `543 dp pane keeps every quick tool whole at fontScale 1_15`() {
        showPane(paneWidthDp = 543.dp, fontScale = 1.15f)
        assertQuickToolLabelsWhole()
    }

    @Test
    fun `narrow 460 dp pane wraps the quick tools instead of truncating`() {
        showPane(paneWidthDp = 460.dp, fontScale = 1.15f)
        assertQuickToolLabelsWhole()
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
