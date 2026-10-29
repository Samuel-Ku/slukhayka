package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.theme.paperInkDarkScheme
import com.slukhayka.audiobooks.ui.theme.paperInkLightScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F10 of the 2026-10-28 device audit — pushed settings destinations were the
 * last legacy `TopAppBar` next to a paper Settings root.
 *
 * The contract a pushed screen must now satisfy: it is inside the «Папір і
 * голос» theme (so the legacy body reads the paper palette instead of the
 * graphite one) and its header is the ONE compact pushed-screen header with a
 * visible back.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE graphics: without it Robolectric's text metrics are degenerate
// (a whole title measured 24 px wide), so a clipping assertion would be a
// lie in either direction.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-rUA-w411dp-h891dp")
class SettingsDestinationScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a pushed settings screen follows the paper scheme of a light app`() {
        assertEquals(paperInkLightScheme().background, contentBackground(darkOuterTheme = false))
    }

    @Test
    fun `a pushed settings screen follows the ink scheme of a dark app`() {
        assertEquals(paperInkDarkScheme().background, contentBackground(darkOuterTheme = true))
    }

    @Test
    fun `the pushed header names the destination and carries the visible way back`() {
        showScaffold(darkOuterTheme = false)
        compose.onNodeWithTag("storage_destination_screen_heading").assertExists()
        compose.onNodeWithTag("settings_back").assertExists()
    }

    // F10 review blockers (200 % AND 320 dp): a compact pushed header is not
    // "one line with an ellipsis". At 320 dp the content column is ~228 dp, so
    // «Завантаження та памʼять» (~253 dp at 1x) needs two lines and
    // «Персональні рекомендації» (~539 dp at 2x) needs three. The header grows
    // with its content (56 dp stays the floor); the test reads the laid-out
    // text and demands no visual overflow.
    @Test
    fun `the longest pushed title is not clipped at 320 dp at normal text`() {
        assertHeadingNotClipped(
            destination = SettingsDestination.Storage,
            fontScale = 1f,
            expectedMaxLines = 2,
        )
    }

    @Test
    fun `the storage title is not clipped at 320 dp at two hundred percent text`() {
        assertHeadingNotClipped(
            destination = SettingsDestination.Storage,
            fontScale = 2f,
            expectedMaxLines = 3,
        )
    }

    @Test
    fun `the recommendations title is not clipped at 320 dp at two hundred percent text`() {
        assertHeadingNotClipped(
            destination = SettingsDestination.Recommendations,
            fontScale = 2f,
            expectedMaxLines = 3,
        )
    }

    @Test
    fun `at 411 dp and normal text a short pushed title still fits one line`() {
        val layout = layoutOf(SettingsDestination.NetworkPrivacy, fontScale = 1f, width = 411)
        val diag = "lines=${layout.lineCount} maxLines=${layout.layoutInput.maxLines} " +
            "width=${layout.size.width}"
        assertEquals(diag, 2, layout.layoutInput.maxLines)
        assertEquals(diag, 1, layout.lineCount)
        assertEquals(diag, false, layout.isLineEllipsized(0))
    }

    private fun assertHeadingNotClipped(
        destination: SettingsDestination,
        fontScale: Float,
        expectedMaxLines: Int,
    ) {
        val layout = layoutOf(destination, fontScale, width = 320)
        assertEquals(
            "the header must allow $expectedMaxLines lines for ${destination.name}",
            expectedMaxLines,
            layout.layoutInput.maxLines,
        )
        // `hasVisualOverflow` is not usable here: Robolectric reports it true
        // even for a single line that fits (measured: 126 px of 319 dp), so the
        // truthful proxy is per-line ellipsis plus the line count.
        assertEquals(
            "${destination.name} needs more lines than the header allows at " +
                "320 dp/${fontScale}x: lines=${layout.lineCount} max=${layout.layoutInput.maxLines}",
            true,
            layout.lineCount <= expectedMaxLines,
        )
        assertEquals(
            "${destination.name} is ellipsized at 320 dp/${fontScale}x: " +
                "lines=${layout.lineCount} width=${layout.size.width}",
            false,
            layout.isLineEllipsized(layout.lineCount - 1),
        )
    }

    private fun layoutOf(
        destination: SettingsDestination,
        fontScale: Float,
        width: Int,
    ): TextLayoutResult {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                AudiobookTheme(darkTheme = false) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        SettingsDestinationScaffold(
                            destination = destination,
                            onBackClick = {},
                        ) { innerPadding ->
                            Text(text = "body", modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(destination.headingTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return requireNotNull(results.firstOrNull()) { "the heading never reported its layout" }
    }

    private fun contentBackground(darkOuterTheme: Boolean): Color {
        var background: Color? = null
        compose.setContent {
            AudiobookTheme(darkTheme = darkOuterTheme) {
                SettingsDestinationScaffold(
                    destination = SettingsDestination.Storage,
                    onBackClick = {},
                ) { innerPadding ->
                    background = MaterialTheme.colorScheme.background
                    Text(text = "body", modifier = Modifier.padding(innerPadding))
                }
            }
        }
        compose.waitForIdle()
        return requireNotNull(background) { "the destination content never composed" }
    }

    private fun showScaffold(darkOuterTheme: Boolean) {
        compose.setContent {
            AudiobookTheme(darkTheme = darkOuterTheme) {
                SettingsDestinationScaffold(
                    destination = SettingsDestination.Storage,
                    onBackClick = {},
                ) { innerPadding ->
                    Text(text = "body", modifier = Modifier.padding(innerPadding))
                }
            }
        }
        compose.waitForIdle()
    }
}
