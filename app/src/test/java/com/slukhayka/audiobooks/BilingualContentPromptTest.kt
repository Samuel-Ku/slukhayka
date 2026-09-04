package com.slukhayka.audiobooks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 (#405) T8 (#496) — the one-time bilingual prompt (US9): the full
 * sentence is visible (and announced — AlertDialog exposes text + both
 * TextButton actions to TalkBack), and each action reaches its branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BilingualContentPromptTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `prompt explains the choice and exposes both actions`() {
        var kept = false
        var ukOnly = false
        compose.setContent {
            AudiobookTheme {
                BilingualContentPrompt(
                    onKeepEnglish = { kept = true },
                    onUkrainianOnly = { ukOnly = true }
                )
            }
        }

        // The full sentence (title + body) is present for TalkBack.
        compose.onNodeWithText("Англомовні книжки").assertExists()
        compose.onNodeWithText("Знайдено англомовні книжки — залишити чи сховати?").assertExists()

        compose.onNodeWithText("Лише українські").performClick()
        assertTrue(ukOnly)
        assertFalse(kept)
    }

    @Test
    fun `keep English action is wired`() {
        var kept = false
        compose.setContent {
            AudiobookTheme {
                BilingualContentPrompt(onKeepEnglish = { kept = true }, onUkrainianOnly = {})
            }
        }

        compose.onNodeWithText("Залишити").performClick()
        assertTrue(kept)
    }
}