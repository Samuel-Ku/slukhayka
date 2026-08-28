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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashReportConsentPromptTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `prompt explains consent and exposes both choices`() {
        var allowed = false
        var denied = false
        compose.setContent {
            AudiobookTheme {
                CrashReportConsentPrompt(
                    onAllow = { allowed = true },
                    onDeny = { denied = true }
                )
            }
        }

        compose.onNodeWithText("Допомогти зі збоями").assertExists()
        compose.onNodeWithText("Не надсилати").performClick()
        assertTrue(denied)
        assertFalse(allowed)
    }

    @Test
    fun `allow action is wired`() {
        var allowed = false
        compose.setContent {
            AudiobookTheme {
                CrashReportConsentPrompt(onAllow = { allowed = true }, onDeny = {})
            }
        }

        compose.onNodeWithText("Надсилати звіти").performClick()
        assertTrue(allowed)
    }
}
