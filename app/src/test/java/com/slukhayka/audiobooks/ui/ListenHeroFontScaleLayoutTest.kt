package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
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
 * #1675 — at 200 % text the «Продовжити слухати» hero broke: the narrator ran
 * off the right edge / under the play button, and the meta never moved below
 * the cover. The hero now has a large-text layout: the card grows, the meta
 * lines take the full width under the cover, and the play button joins the
 * flow instead of floating over text.
 *
 * The 360 dp viewport is explicit: on the default 411 dp fixture the defect is
 * much easier to miss.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w360dp-h800dp-xhdpi", sdk = [35])
class ListenHeroFontScaleLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0]
    private val progress = TestDataFactory.seedPlaybackProgress(
        listOf(book),
        chapterIndex = 2,
        positionSeconds = 420L,
    )[0]

    private fun showHeroAtTwoHundredPercent() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
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
        }
        compose.waitForIdle()
    }

    @Test
    fun `the narrator moves under the cover and stays inside the card`() {
        showHeroAtTwoHundredPercent()

        val panel = compose.onNodeWithTag("listen_hero_panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("hero_cover_placeholder", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val narrator = compose.onNodeWithTag("hero_narrator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the narrator must not run past the card: narrator.right=${narrator.right} panel.right=${panel.right}",
            narrator.right <= panel.right + 1f,
        )
        assertTrue(
            "the meta must move BELOW the cover at 200 %: narrator.top=${narrator.top} cover.bottom=${cover.bottom}",
            narrator.top >= cover.bottom,
        )
    }

    @Test
    fun `the play button joins the flow and never covers text`() {
        showHeroAtTwoHundredPercent()

        val density = compose.density
        val play = compose.onNodeWithTag("hero_play", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val narrator = compose.onNodeWithTag("hero_narrator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val chapter = compose.onNodeWithTag("hero_chapter", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the play target must stay ≥ 48 dp: ${play.width / density.density}×${play.height / density.density} dp",
            play.width >= with(density) { 48.dp.toPx() } - 1f &&
                play.height >= with(density) { 48.dp.toPx() } - 1f,
        )
        assertTrue(
            "play must sit BELOW the narrator, not on top of it: play.top=${play.top} narrator.bottom=${narrator.bottom}",
            play.top >= narrator.bottom,
        )
        assertTrue(
            "play must sit BELOW the chapter line: play.top=${play.top} chapter.bottom=${chapter.bottom}",
            play.top >= chapter.bottom,
        )
    }

    @Test
    fun `the chapter line is not clipped by the card edge`() {
        showHeroAtTwoHundredPercent()

        val panel = compose.onNodeWithTag("listen_hero_panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chapter = compose.onNodeWithTag("hero_chapter", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "chapter.right=${chapter.right} panel.right=${panel.right}",
            chapter.right <= panel.right + 1f,
        )
    }
}
