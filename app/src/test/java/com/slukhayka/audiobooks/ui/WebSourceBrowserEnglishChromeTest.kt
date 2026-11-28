package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.screens.WebSourceBrowserScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1774 — the source browser carried ~30 Ukrainian status literals: import and
 * category outcomes, blocked navigation, the network error card and the
 * privacy-route gate. Its callbacks assign state from outside composition, so
 * the strings are resolved once in the composable and the callbacks reuse them;
 * this walk proves the rendered chrome is EN/PL.
 *
 * `soundbooks` has no entry notice, so a one-shot Ukrainian source announcement
 * (a data-layer fact, not this screen's composition) cannot colour the check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class WebSourceBrowserEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<App>()

    private fun setScreen() {
        val viewModel = MainViewModel(app)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(900.dp)) {
                    WebSourceBrowserScreen(
                        viewModel = viewModel,
                        sourceId = "soundbooks",
                        homeUrl = "https://sound-books.net/",
                        displayName = "Sound-Books",
                        onClose = {}
                    )
                }
            }
        }
    }

    @Test
    fun browserChromeSpeaksEnglish() {
        setScreen()

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "source browser")
        composeTestRule
            .onNodeWithContentDescription("Close the browser for source “Sound-Books”")
            .assertExists()
        composeTestRule.onNodeWithContentDescription("Add the category to Explore")
            .assertExists()
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun browserChromeSpeaksPolish() {
        setScreen()

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "source browser (pl)")
        composeTestRule
            .onNodeWithContentDescription("Zamknij przeglądarkę źródła «Sound-Books»")
            .assertExists()
        composeTestRule.onNodeWithContentDescription("Dodaj kategorię do Przeglądu")
            .assertExists()
    }
}
