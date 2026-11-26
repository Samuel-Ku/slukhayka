package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkMiniPlayerBar
import com.slukhayka.audiobooks.ui.screens.LibraryHeaderActions
import com.slukhayka.audiobooks.ui.theme.ColorContrast
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Physical UI findings: enlarged mini-player text and the quiet library menu must remain readable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "pl-rPL-w400dp-h800dp-420dpi", sdk = [36])
class ChromeReadabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun miniPlayerFitsBothTextLinesAtTwoHundredPercentOnPaper() = checkMiniPlayer(dark = false)
    @Test fun miniPlayerFitsBothTextLinesAtTwoHundredPercentOnInk() = checkMiniPlayer(dark = true)
    @Test fun libraryMoreGlyphContrastsAgainstPaper() = checkMore(dark = false)
    @Test fun libraryMoreGlyphContrastsAgainstInk() = checkMore(dark = true)

    private fun checkMiniPlayer(dark: Boolean) {
        var plays = 0
        var opens = 0
        var closes = 0
        val title = "Slukhayka"
        val meta = "Rozdział 1 · 1:30:00"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PaperInkTheme(darkTheme = dark) {
                    PaperInkMiniPlayerBar(
                        title = title, meta = meta, progress = 0.5f, isPlaying = false,
                        onPlayPauseClick = { plays++ }, playContentDescription = "Odtwórz",
                        onBarClick = { opens++ }, onCloseAction = { closes++ },
                        closeActionDescription = "Zamknij", summaryStateDescription = "Wstrzymano",
                        modifier = Modifier.width(360.dp).testTag("readable_mini_player"),
                        titleTestTag = "readable_mini_title", summaryTestTag = "readable_mini_summary",
                        playTestTag = "readable_mini_play",
                    )
                }
            }
        }
        val bar = compose.onNodeWithTag("readable_mini_player", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        listOf(title, meta).forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$text must be inside the mini-player: $bounds in $bar",
                bounds.top >= bar.top && bounds.bottom <= bar.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("$text must retain its entire line, not a clipped text box: ${layout.size}",
                layout.lineCount == 1 && layout.getLineBottom(0) <= layout.size.height + 1f)
        }
        val play = compose.onNodeWithTag("readable_mini_play")
        val target = play.getUnclippedBoundsInRoot()
        assertTrue("play remains a 48dp target", target.width >= 48.dp && target.height >= 48.dp)
        assertTrue("play remains inside the bar", target.top >= bar.top && target.bottom <= bar.bottom)
        play.performClick()
        val summary = compose.onNodeWithTag("readable_mini_summary")
        summary.performClick()
        assertTrue(summary.fetchSemanticsNode().config[SemanticsProperties.CustomActions].single().action())
        assertEquals(1, plays)
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    private fun checkMore(dark: Boolean) {
        var opened = 0
        var background = Color.Unspecified
        compose.setContent {
            PaperInkTheme(darkTheme = dark) {
                background = MaterialTheme.colorScheme.background
                Surface(modifier = Modifier.fillMaxSize(), color = background) {
                    // Root chrome cannot assume that the platform ambient content color
                    // has been replaced by a parent Surface in every composition host.
                    CompositionLocalProvider(LocalContentColor provides Color.Black) {
                        Box {
                            LibraryHeaderActions(
                                bookmarksCount = 0, peopleCount = 0, menuOpen = false,
                                onMenuOpenChange = { if (it) opened++ }, onOpenSection = {}, onAdd = {},
                                importFocusRequester = remember { FocusRequester() },
                            )
                        }
                    }
                }
            }
        }
        val menu = compose.onNodeWithTag("library_sections_menu")
        menu.assertIsDisplayed()
        val file = File("build/test-ui-witness/library_more_${if (dark) "ink" else "paper"}.png")
        file.parentFile.mkdirs()
        // This fresh raster is a contrast measurement, not a golden to accept/re-record.
        menu.captureRoboImage(filePath = file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val image = requireNotNull(ImageIO.read(file))
        var visibleGlyphPixels = 0
        var strongestContrast = 1.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val contrast = ColorContrast.ratio(Color(image.getRGB(x, y)), background)
            strongestContrast = maxOf(strongestContrast, contrast)
            if (contrast >= ColorContrast.LARGE_TEXT_FLOOR) visibleGlyphPixels++
        }
        assertTrue("More glyph must reach 3:1 against its actual theme; strongest=$strongestContrast",
            visibleGlyphPixels >= 10)
        menu.performClick()
        assertEquals(1, opened)
    }
}
