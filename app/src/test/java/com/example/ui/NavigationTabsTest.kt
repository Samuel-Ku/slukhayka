package com.example.ui

import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.AppBottomBar
import com.example.ui.screens.LocalAudioImportButton
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spec-9 (listen-first IA): the bottom bar shows exactly Слухати · Огляд ·
 * Медіатека, with the listening panel first. The Library still exposes the
 * local-audio import button (T7). Renders the extracted [AppBottomBar] and
 * [LocalAudioImportButton] directly — no `MainViewModel`, no full app — so the
 * assertions are deterministic and fast.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class NavigationTabsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bottomBarShowsListenBrowseAndLibraryTabs() {
        var selected: SelectedTab? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Scaffold(
                    bottomBar = {
                        AppBottomBar(selectedTab = SelectedTab.LISTEN) { tab -> selected = tab }
                    }
                ) { }
            }
        }

        composeTestRule.onNodeWithTag("tab_listen").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_explore").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_library").assertExists().assertIsDisplayed()
        // Removed tabs: the WebView and the standalone Bookmarks tab.
        composeTestRule.onNodeWithTag("tab_4read_web").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tab_bookmarks").assertDoesNotExist()
    }

    @Test
    fun listenTabClickReportsListenSelection() {
        var selected: SelectedTab? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Scaffold(
                    bottomBar = {
                        AppBottomBar(selectedTab = SelectedTab.EXPLORE) { tab -> selected = tab }
                    }
                ) { }
            }
        }

        composeTestRule.onNodeWithTag("tab_listen").performClick()

        assertEquals(SelectedTab.LISTEN, selected)
    }

    @Test
    fun browseTabClickReportsBrowseSelection() {
        var selected: SelectedTab? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Scaffold(
                    bottomBar = {
                        AppBottomBar(selectedTab = SelectedTab.LISTEN) { tab -> selected = tab }
                    }
                ) { }
            }
        }

        composeTestRule.onNodeWithTag("tab_explore").performClick()

        assertEquals(SelectedTab.EXPLORE, selected)
    }

    @Test
    fun libraryTabClickReportsLibrarySelection() {
        var selected: SelectedTab? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Scaffold(
                    bottomBar = {
                        AppBottomBar(selectedTab = SelectedTab.LISTEN) { tab -> selected = tab }
                    }
                ) { }
            }
        }

        composeTestRule.onNodeWithTag("tab_library").performClick()

        assertEquals(SelectedTab.LIBRARY, selected)
    }

    @Test
    fun localAudioImportButtonIsPresentAndClickable() {
        var clicked = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LocalAudioImportButton(onClick = { clicked = true })
            }
        }

        composeTestRule.onNodeWithTag("import_audio_button")
            .assertExists()
            .assertIsDisplayed()
            .performClick()

        assertEquals(true, clicked)
    }
}
