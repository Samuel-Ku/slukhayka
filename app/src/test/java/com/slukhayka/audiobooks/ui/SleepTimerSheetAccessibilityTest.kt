package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.components.SleepTimerSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xxhdpi")
class SleepTimerSheetAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun visibleLastTimerOptionIsNotClippedBelowMinimumTouchTarget() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                SleepTimerSheet(
                    currentTimerMinutes = 0,
                    onSelectTimer = {},
                    onDismiss = {}
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        compose.onNodeWithTag("sleep_timer_option_90")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(32.dp)
    }
}
