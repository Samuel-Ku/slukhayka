package com.slukhayka.audiobooks.accessibility

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.AppBottomBar
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

/** Exercise real routes without changing preferences or library data. */
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun largeTextLabelsFitWithoutChangingPhoneSettings() {
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AudiobookTheme(darkTheme = true) {
                        Box(Modifier.width(320.dp).height(480.dp)) {
                            AppBottomBar(SelectedTab.SETTINGS) { }
                        }
                    }
                }
            }
        }
        listOf(R.string.nav_listen, R.string.nav_explore, R.string.nav_library, R.string.nav_settings).forEach { res ->
            val label = rule.activity.getString(res)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Clipped label: $label", layouts.single().hasVisualOverflow)
        }
    }

    private fun waitFor(tag: String) {
        rule.waitUntil(20_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    }

    @Test fun allDestinationsReturnToTheirSettingsRow() {
        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[MainViewModel::class.java].selectTab(SelectedTab.SETTINGS)
        }
        waitFor("settings_screen")
        rule.onNodeWithTag("tab_settings").assertIsSelected()
        listOf("tab_listen", "tab_explore", "tab_library", "tab_settings").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        }
        val routes = listOf(
            "Profile" to "profile_screen_heading",
            "Storage" to "storage_destination_screen_heading",
            "NetworkPrivacy" to "network_privacy_screen_heading",
            "Recommendations" to "recommendations_screen_heading",
            "ContentLanguages" to "content_languages_screen_heading",
            "AppLocale" to "app_locale_screen_heading"
        )
        routes.forEach { (destination, heading) ->
            repeat(2) { backMethod ->
                rule.onNodeWithTag("settings_$destination").performScrollTo().performClick()
                waitFor(heading)
                if (backMethod == 0) {
                    rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_back)).performClick()
                } else {
                    rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
                }
                waitFor("settings_screen")
                rule.waitUntil(20_000) {
                    rule.onNodeWithTag("settings_$destination").fetchSemanticsNode()
                        .config.getOrNull(SemanticsProperties.Focused) == true
                }
                rule.onNodeWithTag("settings_$destination").assertIsFocused()
            }
        }
        rule.onNodeWithTag("settings_Profile").performScrollTo().performClick()
        waitFor("profile_screen_heading")
        rule.onNodeWithTag("tab_library").performClick()
        waitFor("library_screen")
        rule.onNodeWithTag("library_overflow_button").assertDoesNotExist()
        rule.onNodeWithTag("tab_settings").performClick()
        waitFor("settings_screen")
        rule.onNodeWithTag("profile_screen_heading").assertDoesNotExist()
        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[MainViewModel::class.java].apply {
                selectTab(SelectedTab.EXPLORE)
                openSeriesIndex()
            }
        }
        waitFor("series_index_screen")
        rule.onNodeWithTag("tab_settings").performClick()
        waitFor("settings_screen")
        rule.onNodeWithTag("series_index_screen").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { screenshot ->
            File(rule.activity.getExternalFilesDir(null), "547-settings.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
}
