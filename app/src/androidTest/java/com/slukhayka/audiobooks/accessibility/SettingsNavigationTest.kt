package com.slukhayka.audiobooks.accessibility

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import java.io.File
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertTrue
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

    /** Real system input: Compose's text-action helpers do not open the IME. */
    @Test fun systemBackHidesSearchKeyboardBeforeClearingQuery() {
        val viewModel = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        for ((tab, rootTag, searchTag) in listOf(
            Triple(SelectedTab.LIBRARY, "library_screen", "library_search"),
            Triple(SelectedTab.EXPLORE, "home_screen", "home_search_input")
        )) {
            rule.runOnUiThread { viewModel.selectTab(tab) }
            waitFor(rootTag)
            rule.onNodeWithTag(searchTag).performTouchInput { click() }
            rule.waitUntil(20_000) { keyboardVisible() }
            waitForSystemInputIdle()
            systemInput("input text z")
            rule.waitUntil(20_000) {
                rule.onNodeWithTag(searchTag).fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.EditableText)?.text == "z"
            }
            rule.onNodeWithTag(searchTag).assertTextContains("z")
            if (gestureNavigation()) {
                cancelSystemBack()
                rule.waitUntil(5_000) { keyboardVisible() }
                assertTrue("canceled Back keeps the keyboard open on $tab", keyboardVisible())
                rule.onNodeWithTag(searchTag).assertTextContains("z")
            }
            systemBack()
            rule.waitUntil(20_000) { !keyboardVisible() }
            // The test clock controls Compose frames that settle animated IME insets.
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
            rule.onNodeWithTag(rootTag).assertIsDisplayed()
            rule.onNodeWithTag(searchTag).assertTextContains("z")
            if (gestureNavigation()) {
                cancelSystemBack()
                rule.onNodeWithTag(searchTag).assertTextContains("z")
            }
            systemBack()
            rule.onNodeWithTag(rootTag).assertIsDisplayed()
            rule.waitUntil(20_000) {
                rule.onNodeWithTag(searchTag).fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.EditableText)?.text == ""
            }
        }
    }

    private fun keyboardVisible(): Boolean {
        var visible = false
        rule.runOnUiThread {
            visible = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun systemInput(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
    }

    private fun gestureNavigation(): Boolean =
        Settings.Secure.getInt(rule.activity.contentResolver, "navigation_mode", 0) == 2

    private fun cancelSystemBack() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = rule.activity.resources.displayMetrics
        val y = metrics.heightPixels * .45f
        val startedAt = SystemClock.uptimeMillis()
        fun pointer(action: Int, x: Float) {
            val event = MotionEvent.obtain(startedAt, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 1f)
        SystemClock.sleep(80)
        pointer(MotionEvent.ACTION_MOVE, metrics.widthPixels / 3f)
        SystemClock.sleep(100)
        pointer(MotionEvent.ACTION_CANCEL, metrics.widthPixels / 3f)
        // The system's cancel animation outlives the app's main-thread idle.
        // Wait for the accessibility stream to settle before another gesture.
        waitForSystemInputIdle()
    }

    private fun waitForSystemInputIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        instrumentation.waitForIdleSync()
    }

    private fun systemBack() {
        val activity = rule.activity
        if (gestureNavigation()) {
            val width = activity.resources.displayMetrics.widthPixels
            val height = activity.resources.displayMetrics.heightPixels
            // Above the keyboard: the swipe belongs to the system edge handler.
            systemInput("input swipe 1 ${height * 45 / 100} ${width * 40 / 100} ${height * 45 / 100} 300")
        } else {
            // Match SystemUI's Back button; shell keyevent omits these system flags.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(
                    downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD
                )
                assertTrue(automation.injectInputEvent(event, true))
            }
        }
        // A key event returns before the IME's hide animation completes.
        // Observe the completed action before issuing another Back.
        waitForSystemInputIdle()
    }

    @Test fun systemBackClosesLibrarySectionAndCollectionPage() {
        val viewModel = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.runOnUiThread { viewModel.selectTab(SelectedTab.LIBRARY) }
        waitFor("library_screen")
        rule.onNodeWithTag("library_sections_menu").performClick()
        rule.onNodeWithTag("library_section_saved").performClick()
        waitFor("library_saved_people_header")
        if (gestureNavigation()) {
            cancelSystemBack()
            rule.onNodeWithTag("library_saved_people_header").assertIsDisplayed()
        }
        systemBack()
        waitFor("library_search")
        rule.onNodeWithTag("library_saved_people_header").assertDoesNotExist()
        // A curator page uses the same CollectionPage BackHandler as a public
        // collection. Blank author avoids any network dependency for this route.
        rule.runOnUiThread {
            viewModel.selectTab(SelectedTab.EXPLORE)
            viewModel.openCuratorProfile("", "Куратор")
        }
        waitFor("curator_profile_page")
        if (gestureNavigation()) {
            cancelSystemBack()
            rule.onNodeWithTag("curator_profile_page").assertIsDisplayed()
        }
        systemBack()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("curator_profile_page").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("home_search_input").assertIsDisplayed()
        rule.onNodeWithTag("curator_profile_page").assertDoesNotExist()
    }

    @Test fun systemBackLeavesEmptyRootAfterCanceledGesture() {
        val viewModel = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.runOnUiThread {
            viewModel.updateSearchQuery("")
            viewModel.selectTab(SelectedTab.EXPLORE)
        }
        waitFor("home_screen")
        assertTrue("root starts without the keyboard", !keyboardVisible())
        if (gestureNavigation()) {
            cancelSystemBack()
            rule.onNodeWithTag("home_screen").assertIsDisplayed()
        }
        val activity = rule.activity
        systemBack()
        rule.waitUntil(20_000) { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
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
            if (gestureNavigation()) {
                cancelSystemBack()
                rule.onNodeWithTag("settings_screen").assertIsDisplayed()
            }
            systemBack()
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
            repeat(3) { backMethod ->
                rule.onNodeWithTag("settings_$destination").performScrollTo().performClick()
                waitFor(heading)
                if (backMethod == 0) {
                    rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_back)).performClick()
                } else if (backMethod == 1) {
                    rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
                } else {
                    if (gestureNavigation()) {
                        cancelSystemBack()
                        rule.onNodeWithTag(heading).assertIsDisplayed()
                    }
                    systemBack()
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
