package com.slukhayka.audiobooks.accessibility

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import org.junit.Rule
import org.junit.Test

/** Search remains visible and can be cleared after leaving Overview. */
class SearchReturnNavigationTest {
    // The app asks for POST_NOTIFICATIONS in MainActivity.onCreate. Without the
    // grant the system dialog takes the foreground, the activity never reaches
    // RESUMED and NO Compose root exists — every semantics query then fails on
    // an empty hierarchy (docs/runbooks/instrumented-suites.md, trap 2).
    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun permanentSearchFieldIsEditableOnArrival() {
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.runOnUiThread {
            vm.updateSearchQuery("")
            vm.selectTab(SelectedTab.EXPLORE)
        }
        // IA §4 «Огляд» / expressive: the field is visible without any action.
        rule.onNodeWithTag("home_search_input").assertIsDisplayed()
        try {
            rule.runOnUiThread { vm.updateSearchQuery("Absolute") }
            rule.onNodeWithTag("home_search_input")
                .assertIsDisplayed().assertTextContains("Absolute")
            rule.onNodeWithTag("home_search_input").performTextClearance()
            rule.onNodeWithTag("home_search_input").assertIsDisplayed().performTextInput("Wonder")
            rule.onNodeWithTag("home_search_input").assertTextContains("Wonder")
        } finally {
            rule.runOnUiThread { vm.updateSearchQuery("") }
        }
    }

    @Test fun returningToOverviewKeepsTheQueryEditable() {
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.runOnUiThread {
            vm.updateSearchQuery("")
            vm.selectTab(SelectedTab.EXPLORE)
        }
        try {
            rule.runOnUiThread { vm.updateSearchQuery("Absolute") }
            rule.onNodeWithTag("home_search_input")
                .assertIsDisplayed().assertTextContains("Absolute")
            // #982 / #860 — «Налаштування» лишили нижній бар в #860 (ADR-0049):
            // вхід — шестерня AppSettingsGear у шапці кореня («settings_gear»),
            // а не видалений тег `tab_settings`. Старий тег не існує в жодному
            // лезі CI, тож клік по ньому не виходив з «Огляду» взагалі — і
            // повернення до пошуку (#549) не відтворювалося.
            rule.onNodeWithTag("settings_gear").performClick()
            rule.onNodeWithTag("settings_screen").assertIsDisplayed()
            rule.onNodeWithTag("tab_explore").performClick()
            rule.onNodeWithTag("home_search_input")
                .assertIsDisplayed().assertTextContains("Absolute")
            // ✕ only empties the query — the field stays on screen.
            rule.onNodeWithTag("home_search_clear").performClick()
            rule.onNodeWithTag("home_search_input").assertIsDisplayed().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
        } finally {
            rule.runOnUiThread { vm.updateSearchQuery("") }
        }
    }
}
