package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
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

    // F10 review blocker (200 % text): the legacy header grew to 112 dp and
    // wrapped to two lines at fontScale ≥ 2. The compact header must do the
    // same instead of ellipsizing a pushed destination's title.
    @Test
    fun `a pushed title wraps to two full lines at two hundred percent text`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = false) {
                    Box(Modifier.width(320.dp).height(600.dp)) {
                        SettingsDestinationScaffold(
                            destination = SettingsDestination.Storage,
                            onBackClick = {},
                        ) { innerPadding ->
                            Text(text = "body", modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val heading = compose.onNodeWithTag(
            "storage_destination_screen_heading",
            useUnmergedTree = true,
        )
        heading.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))

        val results = mutableListOf<TextLayoutResult>()
        heading.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        val layout = requireNotNull(results.firstOrNull()) { "the heading never reported its layout" }
        assertEquals("the pushed title must wrap, not ellipsize", 2, layout.lineCount)
        assertEquals("no line may be visually clipped at 200 %", false, layout.hasVisualOverflow)
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
