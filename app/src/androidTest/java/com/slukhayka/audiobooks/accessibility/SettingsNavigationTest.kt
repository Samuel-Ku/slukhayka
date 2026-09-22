package com.slukhayka.audiobooks.accessibility

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Exercise real routes without changing preferences or library data.
 *
 * #852: the app asks for `POST_NOTIFICATIONS` on launch
 * ([MainActivity] `onCreate` → `LaunchedEffect`); without the pre-grant the
 * system permission dialog takes the foreground, the activity never reaches
 * RESUMED, no Compose root is registered and every wait dies with «No compose
 * hierarchies found in the app» — the same rule [MainActivityAccessibilityTest]
 * already carries. The 200 %-text bottom-bar check moved to
 * [BottomBarLargeTextLayoutTest], which needs a content-free host.
 */
class SettingsNavigationTest {

    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) {
        try {
            rule.waitUntil(20_000) {
                // #766 A — a raw fetchSemanticsNodes() THROWS while no hierarchy
                // exists yet; the tolerant wait retries instead.
                runCatching { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
                    .getOrDefault(false)
            }
        } catch (timeout: ComposeTimeoutException) {
            // #852: a bare ComposeTimeoutException left the failing step to
            // guesswork — name the node that never appeared and show the tree
            // that WAS there.
            val tree = runCatching { rule.onRoot(useUnmergedTree = true).printToString() }
                .getOrDefault("(no compose root)")
            throw AssertionError("node «$tag» never appeared within 20 s; tree:\n$tree", timeout)
        }
    }

    @Test fun allDestinationsReturnToTheirSettingsRow() {
        val viewModel = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        // #860 / ADR-0049 — «Налаштування» are NOT a bottom-bar destination any
        // more: the real entry is the gear every root header carries
        // (AppSettingsGear, tag "settings_gear"), and BACK returns to the root
        // the gear was tapped on (MainActivity.kt BackHandler +
        // settingsReturnTab). All FOUR roots are wired — LISTEN joined them in
        // #958, which gave its ListenScreen call the same onOpenSettings the
        // other three already carried, so its gear is no longer inert.
        val roots = listOf(
            SelectedTab.LISTEN to "listen_screen",
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
        // ADR-0037 — the SEVENTH destination, «Аудіо джерел» (#959). It sits
        // between NetworkPrivacy and Recommendations in SettingsScreen's own
        // group order; leaving it out made the six-route list pass without ever
        // opening the panel.
        val routes = listOf(
            "Profile" to "profile_screen_heading",
            "Storage" to "storage_destination_screen_heading",
            "NetworkPrivacy" to "network_privacy_screen_heading",
            "SourceAudioRefusal" to "source_audio_refusal_screen_heading",
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
