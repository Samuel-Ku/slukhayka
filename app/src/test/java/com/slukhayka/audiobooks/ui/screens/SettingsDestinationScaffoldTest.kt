package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
