package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unslop audit 2026-11-01 (U11a) — at NORMAL text size the hero's trailing lines
 * used to run under the floating play disc: only the progress row reserved end
 * space, while the author, narrator and chapter lines reached the card's right
 * edge. On the device the narrator was visibly clipped by the button.
 *
 * The 200 % case is covered by [ListenHeroFontScaleLayoutTest], which exercises
 * the large-text layout instead. This test pins the DEFAULT layout with a
 * narrator long enough to reach the disc, because the default fixture narrator
 * fits and hides the defect.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w360dp-h800dp-xhdpi", sdk = [35])
class ListenHeroNormalTextOverlapTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0].copy(
        narrator = "Олександра Зушман-Ковалевська, Наталія Петрівна Бондаренко",
    )

    private val progress = TestDataFactory.seedPlaybackProgress(
        listOf(book),
        chapterIndex = 2,
        positionSeconds = 420L,
    )[0]

    private fun showHeroAtDefaultTextSize() {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                // A plain Box, not an M3 Surface: Surface propagates min
                // constraints, so the hero would be measured as the whole
                // viewport instead of its own content height.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    ListenHeroCard(
                        book = book,
                        progress = progress,
                        cumulativePositionSeconds = 600L + 660L + 420L,
                        onResumeClick = {},
                        onBookClick = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the narrator never runs under the play disc at normal text size`() {
        showHeroAtDefaultTextSize()

        val panel = compose.onNodeWithTag("listen_hero_panel", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val narrator = compose.onNodeWithTag("hero_narrator", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("hero_play", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val overlaps =
            narrator.left < play.right && play.left < narrator.right &&
                narrator.top < play.bottom && play.top < narrator.bottom
        assertFalse(
            "the narrator must not sit under the play disc: " +
                "narrator=[${narrator.left}, ${narrator.top}, ${narrator.right}, ${narrator.bottom}] " +
                "play=[${play.left}, ${play.top}, ${play.right}, ${play.bottom}]",
            overlaps,
        )
        assertTrue(
            "the narrator must stay inside the card: narrator.right=${narrator.right} panel.right=${panel.right}",
            narrator.right <= panel.right + 1.dp,
        )
    }

    @Test
    fun `the chapter line never runs under the play disc at normal text size`() {
        showHeroAtDefaultTextSize()

        val chapter = compose.onNodeWithTag("hero_chapter", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("hero_play", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val overlaps =
            chapter.left < play.right && play.left < chapter.right &&
                chapter.top < play.bottom && play.top < chapter.bottom
        assertFalse(
            "the chapter line must not sit under the play disc: " +
                "chapter=[${chapter.left}, ${chapter.top}, ${chapter.right}, ${chapter.bottom}] " +
                "play=[${play.left}, ${play.top}, ${play.right}, ${play.bottom}]",
            overlaps,
        )
    }
}
