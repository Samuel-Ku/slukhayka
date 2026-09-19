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
                            // #956 — the bar's real four destinations; SETTINGS
                            // has not been one since #860.
                            AppBottomBar(SelectedTab.LISTEN) { }
                        }
                    }
                }
            }
        }
        listOf(R.string.nav_listen, R.string.nav_explore, R.string.nav_library, R.string.nav_friends).forEach { res ->
            val label = rule.activity.getString(res)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Clipped label: $label", layouts.single().hasVisualOverflow)
        }
    }

    private fun waitFor(tag: String) {
        rule.waitUntil(20_000) {
            // #766 A — a raw fetchSemanticsNodes() THROWS while no hierarchy
            // exists yet; the tolerant wait retries instead.
            runCatching { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
                .getOrDefault(false)
        }
    }

    @Test fun allDestinationsReturnToTheirSettingsRow() {
        val viewModel = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        // #860 / ADR-0049 — «Налаштування» are NOT a bottom-bar destination any
        // more: the real entry is the gear every root header carries
        // (AppSettingsGear, tag "settings_gear"), and BACK returns to the root
        // the gear was tapped on (MainActivity.kt BackHandler +
        // settingsReturnTab). MainActivity wires the gear for EXPLORE, LIBRARY
        // and FRIENDS; the LISTEN call passes no onOpenSettings, so its gear is
        // inert — a production gap, reported, deliberately not asserted here.
        val roots = listOf(
            SelectedTab.EXPLORE to "home_screen",
            SelectedTab.LIBRARY to "library_screen",
            SelectedTab.FRIENDS to "friends_screen"
        )
        for ((tab, rootTag) in roots) {
            rule.runOnUiThread { viewModel.selectTab(tab) }
            waitFor(rootTag)
            rule.onNodeWithTag("settings_gear").performClick()
            waitFor("settings_screen")
            // The bar keeps its four real destinations — and Settings, having
            // left it in #860, must not be back as a fifth tab.
            listOf("tab_listen", "tab_explore", "tab_library", "tab_friends").forEach { tag ->
                rule.onNodeWithTag(tag).assertIsDisplayed()
                    .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            }
            rule.onNodeWithTag("tab_settings").assertDoesNotExist()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            waitFor(rootTag)
            rule.onNodeWithTag(rootTag).assertIsDisplayed()
            rule.onNodeWithTag("settings_screen").assertDoesNotExist()
        }

        // The loop left us on the FRIENDS root; open Settings once more through
        // its gear for the destination contract below.
        rule.onNodeWithTag("settings_gear").performClick()
        waitFor("settings_screen")
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
        // A pushed pane must not survive leaving Settings: open Profile, switch
        // to Бібліотека through the bar, then re-open Settings via its gear.
        rule.onNodeWithTag("settings_Profile").performScrollTo().performClick()
        waitFor("profile_screen_heading")
        rule.onNodeWithTag("tab_library").performClick()
        waitFor("library_screen")
        rule.onNodeWithTag("library_overflow_button").assertDoesNotExist()
        rule.onNodeWithTag("settings_gear").performClick()
        waitFor("settings_screen")
        rule.onNodeWithTag("profile_screen_heading").assertDoesNotExist()
        // A secondary root route must not survive a bar switch either.
        rule.runOnUiThread {
            viewModel.selectTab(SelectedTab.EXPLORE)
            viewModel.openSeriesIndex()
        }
        waitFor("series_index_screen")
        rule.onNodeWithTag("tab_library").performClick()
        waitFor("library_screen")
        rule.onNodeWithTag("series_index_screen").assertDoesNotExist()
        rule.onNodeWithTag("settings_gear").performClick()
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
