package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import com.slukhayka.audiobooks.ui.components.TabSaveableHost
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * spec-54 T03 (#871) — switching tabs must NOT reset a tab's state. This drives
 * the REAL [TabSaveableHost] the app uses, with the same shape as the roots: a
 * tab leaves the composition when another is selected.
 */
@RunWith(RobolectricTestRunner::class)
class TabSaveableHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun Harness() {
        var selected by rememberSaveable { mutableStateOf("LISTEN") }
        Column {
            Text(
                text = "switch",
                modifier = Modifier.testTag("switch_to_explore").clickable {
                    selected = if (selected == "LISTEN") "EXPLORE" else "LISTEN"
                }
            )
            TabSaveableHost(tabKey = selected) {
                when (selected) {
                    // A tab keeps its own saveable state, exactly like the roots.
                    "LISTEN" -> ListenTab()
                    else -> ExploreTab()
                }
            }
        }
    }

    @Composable
    private fun ListenTab() {
        var filter by rememberSaveable { mutableStateOf("all") }
        Column {
            Text("listen tab", modifier = Modifier.testTag("listen_tab"))
            Text(
                text = "filter=$filter",
                modifier = Modifier
                    .testTag("listen_filter")
                    .clickable { filter = "downloaded" }
            )
        }
    }

    @Composable
    private fun ExploreTab() {
        Text("explore tab", modifier = Modifier.testTag("explore_tab"))
    }

    @Test
    fun aTabsStateSurvivesSwitchingAwayAndBack() {
        composeTestRule.setContent { AudiobookTheme(darkTheme = true) { Harness() } }

        // The listener filters the first tab...
        composeTestRule.onNodeWithTag("listen_filter").performClick()
        composeTestRule.onNodeWithTag("listen_filter").assertTextEquals("filter=downloaded")

        // ...switches to another root and back.
        composeTestRule.onNodeWithTag("switch_to_explore").performClick()
        composeTestRule.onNodeWithTag("explore_tab").assertExists()
        composeTestRule.onNodeWithTag("switch_to_explore").performClick()
        composeTestRule.onNodeWithTag("listen_tab").assertExists()

        // The filter is STILL there: the tab was not reset by leaving.
        composeTestRule.onNodeWithTag("listen_filter").assertTextEquals("filter=downloaded")
    }
}
