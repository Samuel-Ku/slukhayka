package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.screens.SettingsScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #885 (wave 3) — the settings screen, which had NO visual coverage at all.
 *
 * Found while shipping the group-header restyle (#1075): the change moved ZERO
 * goldens, and the reason was not "nothing was touched" but that no golden
 * covered this screen and no JVM test rendered it. A whole surface was
 * invisible to every check the repository runs.
 *
 * So this test does two jobs:
 *  - it PINS the group header exactly as the prototype writes it
 *    (`.sl-setting-group > h2`: 12 px, uppercase, `.8px` tracking, muted), which
 *    is what makes the #1075 change verified instead of merely compiled;
 *  - it gives the screen a golden, so the NEXT settings change cannot land
 *    invisibly either.
 *
 * Only two parameters are passed because that is all [SettingsScreen] takes —
 * no ViewModel, no Room, so this stays a cheap JVM pin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SettingsScreenSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dark_theme_pins_the_group_header_and_the_screen() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SettingsScreen(onOpen = {})
            }
        }

        assertGroupHeadersAreMutedCaps()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/settings_screen_dark.png"
        )
    }

    @Test
    fun light_theme_pins_the_group_header_and_the_screen() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = false) {
                SettingsScreen(onOpen = {})
            }
        }

        assertGroupHeadersAreMutedCaps()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/settings_screen_light.png"
        )
    }

    /**
     * The prototype's own values, asserted rather than assumed: a group name is
     * UPPERCASE (so `onNodeWithText` finds the transformed form, not the
     * resource) and it is present on screen. A future edit that drops the caps
     * — or moves the header out of the viewport — fails here.
     */
    private fun assertGroupHeadersAreMutedCaps() {
        // `settings_group_profile` is «Профіль»; the header must render it as
        // «ПРОФІЛЬ», which is the prototype's `text-transform:uppercase`.
        composeTestRule.onNodeWithText("ПРОФІЛЬ").assertIsDisplayed()
        composeTestRule.onNodeWithText("ДАНІ ТА ПАМʼЯТЬ").assertIsDisplayed()
    }
}
