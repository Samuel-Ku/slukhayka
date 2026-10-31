package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1705 — the root no longer collects the 1 Hz [PlayerState]: [MiniPlayerHost]
 * does. These tests pin that composition boundary, not an implementation
 * detail: a position tick must repaint the bar and leave its caller untouched,
 * and the host must still draw exactly the states the bar used to draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [36])
class MiniPlayerHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks().first()
    private val chapters = TestDataFactory.dataChapters(listOf(book))

    private fun state(
        positionMs: Long,
        currentBook: com.slukhayka.audiobooks.data.db.AudiobookEntity? = book
    ) = PlayerState(
        currentBook = currentBook,
        chapters = if (currentBook == null) emptyList() else chapters,
        currentChapterIndex = 0,
        isPlaying = true,
        currentPositionMs = positionMs,
        durationMs = 600_000L
    )

    @Test
    fun `a position tick repaints the bar without recomposing its caller`() {
        val playerState = MutableStateFlow(state(positionMs = 0L))
        var callerCompositions = 0

        compose.setContent {
            callerCompositions++
            AudiobookTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(Modifier.fillMaxSize()) {
                        MiniPlayerHost(
                            playerState = playerState,
                            onPlayPauseClick = {},
                            onBarClick = {},
                            onCloseClick = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val callerCompositionsBeforeTick = callerCompositions
        val progressBeforeTick = compose.onNodeWithTag("mini_player_play_pause")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

        compose.runOnIdle { playerState.value = state(positionMs = 300_000L) }
        compose.waitForIdle()

        val progressAfterTick = compose.onNodeWithTag("mini_player_play_pause")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.runOnIdle {
            assertEquals(
                "the host must hand the tick to the bar",
                0.5f,
                progressAfterTick,
                0.001f
            )
            assertEquals(
                "a position tick must not recompose the caller that renders the host",
                callerCompositionsBeforeTick,
                callerCompositions
            )
        }
    }

    @Test
    fun `the host draws the bar only while the flow has a book`() {
        val playerState = MutableStateFlow(PlayerState())

        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayerHost(
                        playerState = playerState,
                        onPlayPauseClick = {},
                        onBarClick = {},
                        onCloseClick = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("mini_player_bar").assertDoesNotExist()

        compose.runOnIdle { playerState.value = state(positionMs = 0L) }
        compose.waitForIdle()
        compose.onNodeWithTag("mini_player_bar").assertExists()

        compose.runOnIdle { playerState.value = PlayerState() }
        compose.waitForIdle()
        compose.onNodeWithTag("mini_player_bar").assertDoesNotExist()
    }
}
