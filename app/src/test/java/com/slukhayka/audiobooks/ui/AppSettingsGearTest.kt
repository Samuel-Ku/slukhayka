package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.AppSettingsGear
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ADR-0049 / #860 — the gear is a real, spoken, one-tap action on every root. */
@RunWith(RobolectricTestRunner::class)
class AppSettingsGearTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun theGearIsAReachableSpokenAction() {
        var opened = 0
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                AppSettingsGear(onClick = { opened++ })
            }
        }

        composeTestRule.onNodeWithTag("settings_gear")
            .assertExists()
            .assertHasClickAction()
            .assertHeightIsAtLeast(24.dp)
            .performClick()
        assertEquals("one tap opens Settings", 1, opened)

        val label = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(R.string.a11y_open_settings)
        composeTestRule.onNodeWithContentDescription(label).assertExists()
    }
}
