package com.example.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.components.SleepTimerSheet
import com.example.ui.components.SpeedSheet
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Marketing/README screenshots for the two player bottom sheets. The full
 * player surface is captured by [PlayerScreenSnapshotTest]; these two capture
 * the sheets that open from its chips — «Таймер сну» and «Швидкість».
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PlayerSheetsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sleep_timer_sheet() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SleepTimerSheet(
                        currentTimerMinutes = 15,
                        onSelectTimer = {},
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/player_sleep_timer.png")
    }

    @Test
    fun speed_sheet() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpeedSheet(
                        currentSpeed = 1.25f,
                        onSpeedChange = {},
                        onSaveForBook = {},
                        onSetDefault = {},
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/player_speed.png")
    }
}
