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
 * This snapshot pins the visible control set (cast, play/pause, next, close) and
 * checks the book title still gets real room at 360 dp — the width the close
 * button competes for.
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
    fun mini_player_bar_keeps_title_room_with_the_close_button() {
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
                        onSkipNextClick = {},
                        onCloseClick = {},
                        onBarClick = {}
                    )
                }
            }
        }

        // Self-verifying on top of the image: a fifth 48 dp target would cut the
        // title column by half, and the ellipsised Text reports that squeeze as
        // its own width — the floor is what keeps the row legitimate.
        val titleWidth = composeTestRule.onNodeWithTag("mini_player_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val titleFloorPx = with(composeTestRule.density) { 64.dp.toPx() }
        assertTrue("book title must keep usable room, was $titleWidth px", titleWidth >= titleFloorPx)

        composeTestRule.onNodeWithTag("mini_player_bar").captureRoboImage(
            filePath = "src/test/snapshots/mini_player_bar_close.png"
        )
    }
}
