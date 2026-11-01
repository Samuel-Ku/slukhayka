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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unslop audit 2026-11-01 (U11a) — at NORMAL text size the hero's trailing lines
 * used to reach the card's right edge, under the floating play disc: only the
 * progress row reserved end space. On the device the narrator was visibly
 * clipped by the button (`docs/audits/assets/unslop-2026-11-01/hero-before.png`).
 *
 * The fixture reproduces the device state rather than a convenient one: the
 * title wraps to two lines (the real «Левеня, яке навчилося ричати» case), which
 * drops the narrator into the disc's vertical band, and the narrator is long
 * enough to reach the disc horizontally. With a one-line title the narrator sits
 * above the button, so a test built on that fixture would pass on the broken
 * layout. This one pins the horizontal seam (`narrator.right <= play.left`),
 * which is the reserve itself, and the absence of a rectangle overlap.
 *
 * The 200 % case is a different layout and is covered by
 * [ListenHeroFontScaleLayoutTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w360dp-h800dp-xhdpi", sdk = [35])
class ListenHeroNormalTextOverlapTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0].copy(
        title = "Левеня, яке навчилося ричати",
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
    fun `the narrator keeps clear of the play disc at normal text size`() {
        showHeroAtDefaultTextSize()

        val panel = compose.onNodeWithTag("listen_hero_panel", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val narrator = compose.onNodeWithTag("hero_narrator", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("hero_play", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        // The seam itself: without the 68 dp reserve this line runs past the
        // disc's left edge, whatever the vertical band happens to be.
        assertTrue(
            "the narrator must end at or before the play disc: " +
                "narrator.right=${narrator.right} play.left=${play.left} " +
                "narrator=[${narrator.left}, ${narrator.top}, ${narrator.right}, ${narrator.bottom}] " +
                "play=[${play.left}, ${play.top}, ${play.right}, ${play.bottom}]",
            narrator.right <= play.left + 1.dp,
        )
        assertTrue(
            "the narrator must stay inside the card: narrator.right=${narrator.right} panel.right=${panel.right}",
            narrator.right <= panel.right + 1.dp,
        )
    }

    @Test
    fun `no trailing line sits inside the play disc rectangle`() {
        showHeroAtDefaultTextSize()

        val narrator = compose.onNodeWithTag("hero_narrator", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("hero_play", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        // Rectangles are half-open on the right/bottom, so touching edges are
        // not an overlap.
        val overlaps =
            narrator.left < play.right && play.left < narrator.right &&
                narrator.top < play.bottom && play.top < narrator.bottom
        assertTrue(
            "the narrator must not sit under the play disc: " +
                "narrator=[${narrator.left}, ${narrator.top}, ${narrator.right}, ${narrator.bottom}] " +
                "play=[${play.left}, ${play.top}, ${play.right}, ${play.bottom}]",
            !overlaps,
        )
    }
}
