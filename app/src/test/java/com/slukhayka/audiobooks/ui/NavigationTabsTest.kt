package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.AppBottomBar
import com.slukhayka.audiobooks.shouldHideAppBackgroundForFullPlayerTransition
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spec-9 (listen-first IA): the bottom bar shows exactly Слухати · Огляд ·
 * Медіатека, with the listening panel first. Renders the extracted
 * [AppBottomBar] directly — no `MainViewModel`, no full app — so the
 * assertions are deterministic and fast.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class NavigationTabsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun fullPlayerTransitionKeepsBackgroundHiddenUntilExitFinishes() {
        assertFalse(
            shouldHideAppBackgroundForFullPlayerTransition(
                currentState = false,
                targetState = false
            )
        )
        assertTrue(
            shouldHideAppBackgroundForFullPlayerTransition(
                currentState = false,
                targetState = true
            )
        )
        assertTrue(
            shouldHideAppBackgroundForFullPlayerTransition(
                currentState = true,
                targetState = true
            )
        )
        assertTrue(
            shouldHideAppBackgroundForFullPlayerTransition(
                currentState = true,
                targetState = false
            )
        )
    }

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
    fun bottomBarExposesOneUkrainianTabNameAndSelectedStatePerDestination() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Scaffold(
                    bottomBar = {
                        AppBottomBar(selectedTab = SelectedTab.LISTEN) { }
                    }
                ) { }
            }
        }

        composeTestRule.onNodeWithTag("tab_listen")
            .assertIsSelected()
            .assertTextEquals("Слухати")
        composeTestRule.onNodeWithTag("tab_explore").assertTextEquals("Огляд")
        composeTestRule.onNodeWithTag("tab_library").assertTextEquals("Медіатека")

        composeTestRule.onNodeWithContentDescription("Listen", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Browse", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Library", useUnmergedTree = true)
            .assertDoesNotExist()
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
    fun bottomBarKeepsEveryDestinationReachableAtTwoHundredPercentFontScale() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        AppBottomBar(selectedTab = SelectedTab.LISTEN) { }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("tab_listen")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Слухати")
        composeTestRule.onNodeWithTag("tab_explore")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Огляд")
        composeTestRule.onNodeWithTag("tab_library")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Медіатека")
    }
}
