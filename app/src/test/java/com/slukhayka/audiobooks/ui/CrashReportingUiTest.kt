package com.slukhayka.audiobooks.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.CrashReportingConsentDialog
import com.slukhayka.audiobooks.ui.screens.CrashReportingSettingsSection
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashReportingUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun consentDialogExplainsTheAnonymousReportAndOffersBothExplicitChoices() {
        var allowed = 0
        var denied = 0
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CrashReportingConsentDialog(
                    onAllow = { allowed += 1 },
                    onDeny = { denied += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Я роблю «Слухайку» сам. Якщо хочете допомогти мені зробити застосунок " +
                "стабільнішим, дозвольте надсилати анонімні технічні звіти про збої. " +
                "Вони не містять назв книжок, адрес джерел чи історії прослуховування. " +
                "Рішення завжди можна змінити в налаштуваннях."
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("Надсилати звіти").performClick()
        assertEquals(1, allowed)
        assertEquals(0, denied)
    }

    @Test
    fun consentDialogDenialIsAnExplicitWorkingChoice() {
        var denied = 0
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CrashReportingConsentDialog(onAllow = {}, onDeny = { denied += 1 })
            }
        }

        composeTestRule.onNodeWithText("Не надсилати").performClick()

        assertEquals(1, denied)
    }

    @Test
    fun privacySettingsExposeOneAccessibleReversibleCrashReportSwitch() {
        var requested: Boolean? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CrashReportingSettingsSection(
                    allowed = false,
                    onAllowedChange = { requested = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("crash_reporting_switch", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.Off
                )
            )
            .performClick()

        composeTestRule.onNodeWithText("Надсилати звіти про збої").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Анонімні технічні звіти без назв книжок, адрес джерел та історії прослуховування."
        ).assertIsDisplayed()
        assertEquals(true, requested)
    }
}
