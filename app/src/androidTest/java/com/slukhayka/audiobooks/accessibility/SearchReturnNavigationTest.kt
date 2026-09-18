package com.slukhayka.audiobooks.accessibility

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

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
            rule.onNodeWithTag("tab_settings").performClick()
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
