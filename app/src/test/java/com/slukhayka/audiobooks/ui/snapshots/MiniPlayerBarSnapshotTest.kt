package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mini player is the surface where the affordances already fill the width.
 * Issue #808 settles its control row: three transport targets (previous,
 * play/pause, next), no cast and no cross, with the title on one line. This
 * snapshot pins that set and checks the book title still gets real room at
 * 360 dp — the width the controls compete for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class MiniPlayerBarSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = TestDataFactory.dataBooks().first()
    private val chapters = TestDataFactory.dataChapters(listOf(book))

    @Composable
    private fun NarrowBar(content: @Composable () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // 360 dp is the narrow phone the control row has to survive
                // (1080 px at 3x).
                Box(modifier = Modifier.width(360.dp)) { content() }
            }
        }
    }

    @Test
    fun mini_player_bar_keeps_title_room_with_the_transport_row() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                NarrowBar {
                    MiniPlayerBar(
                        playerState = PlayerState(
                            currentBook = book,
                            chapters = chapters,
                            currentChapterIndex = 1,
                            isPlaying = true,
                            isOfflineMode = true
                        ),
                        onPlayPauseClick = {},
                        onCloseClick = {},
                        onBarClick = {}
                    )
                }
            }
        }

        // Self-verifying on top of the image: the title is one line now (#808),
        // and the snapshot measures the box it actually gets at 360 dp — the
        // floor is what keeps the row legitimate.
        val titleWidth = composeTestRule.onNodeWithTag("mini_player_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val titleFloorPx = with(composeTestRule.density) { 64.dp.toPx() }
        assertTrue("book title must keep usable room, was $titleWidth px", titleWidth >= titleFloorPx)

        composeTestRule.onNodeWithTag("mini_player_bar").captureRoboImage(
            filePath = "src/test/snapshots/mini_player_bar_transport.png"
        )
    }

    /**
     * The direction replaces the bottom underline with the ring around the ONE
     * play target: a screenshot can show the ring, but not that it is the only
     * transport control and that the bar keeps its 64 dp height.
     */
    @Test
    fun mini_player_has_one_ringed_play_target_and_no_underline() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                NarrowBar {
                    MiniPlayerBar(
                        playerState = PlayerState(
                            currentBook = book,
                            chapters = chapters,
                            currentChapterIndex = 1,
                            isPlaying = true,
                            isOfflineMode = true,
                            durationMs = 1_000L,
                            currentPositionMs = 500L
                        ),
                        onPlayPauseClick = {},
                        onCloseClick = {},
                        onBarClick = {}
                    )
                }
            }
        }

        val density = composeTestRule.density
        val bar = composeTestRule.onNodeWithTag("mini_player_bar", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val target = composeTestRule.onNodeWithTag("mini_player_play_pause")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "bar must be 64 dp tall, was ${bar.height / density.density} dp",
            bar.height >= with(density) { 64.dp.toPx() },
        )
        assertTrue(
            "ring play target must be 48 dp, was ${target.height / density.density} dp",
            target.height >= with(density) { 48.dp.toPx() },
        )
        // F14: the ring is now a 32 dp indicator AROUND the 48 dp target (it
        // used to fill the whole target), so it is its own, smaller node.
        val ring = composeTestRule.onNodeWithTag("mini_player_progress")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the progress ring must be smaller than the 48 dp target, was ${ring.height / density.density} dp",
            ring.height < with(density) { 48.dp.toPx() },
        )
        // The old bottom underline is gone, and so are the previous/next
        // buttons: one control, plus the summary.
        composeTestRule.onNodeWithTag("mini_player_previous").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mini_player_next").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mini_player_summary").assertExists()
    }
}
