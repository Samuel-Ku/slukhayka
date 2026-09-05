package com.slukhayka.audiobooks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.NarrationSwitchPrompt
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NarrationSwitchConfirmationPromptTest {

    @get:Rule
    val compose = createComposeRule()

    private val prompt = NarrationSwitchPrompt(
        currentNarrator = "Диктор А",
        targetNarrator = "Диктор Б",
        title = "Проблема з миром",
        targetEditionKey = "edition-b"
    )

    @Test
    fun `dialog names both narrations and cancellation is wired`() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            AudiobookTheme {
                NarrationSwitchConfirmationPrompt(
                    prompt = prompt,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        compose.onNodeWithText("Змінити начитку?").assertExists()
        compose.onNodeWithText(
            "Поточна начитка — Диктор А. Для «Проблема з миром» вибрано іншу — Диктор Б. Її позиція прослуховування зберігається окремо."
        ).assertExists()
        compose.onNodeWithText("Залишити поточну").performClick()

        assertTrue(dismissed)
        assertFalse(confirmed)
    }

    @Test
    fun `switch action is wired`() {
        var confirmed = false
        compose.setContent {
            AudiobookTheme {
                NarrationSwitchConfirmationPrompt(prompt, { confirmed = true }, {})
            }
        }

        compose.onNodeWithText("Перемкнути").performClick()
        assertTrue(confirmed)
    }
}
