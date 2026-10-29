package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F14 of the 2026-10-28 device audit — the mini player's play node was
 * `selectable(selected = false, role = Role.Button)`, so TalkBack announced a
 * play control as «не вибрано», and the progress ring carried no
 * `progressBarRangeInfo` at all. It is a button that REPORTS progress.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [35])
class MiniPlayerSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the play control is a button that reports its progress`() {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        PaperInkMiniPlayerBar(
                            title = "Темна матерія",
                            meta = "Філіп Пулман · Розділ 2",
                            progress = 0.42f,
                            isPlaying = true,
                            onPlayPauseClick = {},
                            playContentDescription = "Пауза",
                            playTestTag = "mini_player_play",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val play = compose.onNodeWithTag("mini_player_play")
        // A button, not a tab: no `selected` state is announced.
        play.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        play.assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected).not()
        )
        val range = play.fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.42f, range.current, 0.001f)
        assertEquals(0f, range.range.start, 0.001f)
        assertEquals(1f, range.range.endInclusive, 0.001f)
        play.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "42%",
            )
        )
        // The ring is an indicator around the glyph, not a second 48 dp button.
        compose.onNodeWithTag("mini_player_progress")
            .assertHeightIsAtLeast(32.dp)
    }
}
