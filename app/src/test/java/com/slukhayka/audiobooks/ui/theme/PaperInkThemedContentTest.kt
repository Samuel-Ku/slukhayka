package com.slukhayka.audiobooks.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The seam root tabs use to enter «Папір і голос».
 *
 * Regression guard for #1530: `PaperInkTheme` defaults to the SYSTEM scheme,
 * so a root wrapped in it ignored a forced «Папір»/«Чорнило» choice and drew
 * light-on-light (or dark-on-dark) when the system theme disagreed.
 * [PaperInkThemedContent] must inherit the already-resolved outer scheme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperInkThemedContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRootInsideTheLightAppThemeGetsThePaperScheme() {
        assertEquals(paperInkLightScheme().background, probeBackground(darkTheme = false))
    }

    @Test
    fun aRootInsideTheDarkAppThemeGetsTheInkScheme() {
        assertEquals(paperInkDarkScheme().background, probeBackground(darkTheme = true))
    }

    private fun probeBackground(darkTheme: Boolean): Color {
        var background: Color? = null
        compose.setContent {
            AudiobookTheme(darkTheme = darkTheme) {
                PaperInkThemedContent {
                    background = MaterialTheme.colorScheme.background
                }
            }
        }
        compose.waitForIdle()
        return requireNotNull(background) { "the probe never composed" }
    }
}
