package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.AdaptiveNavigationLayout
import com.slukhayka.audiobooks.AppBottomBarSlot
import com.slukhayka.audiobooks.ui.adaptive.WindowLayout
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #900 — «Бічна навігація замість нижньої» as an assertion, not a promise.
 *
 * Renders the two REAL surfaces the app uses through the two slot composables
 * the app calls ([AppBottomBarSlot] in the Scaffold's `bottomBar`,
 * [AdaptiveNavigationLayout] in its content), so a regression in the wiring
 * fails here and not only on a tablet nobody has plugged in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Ukrainian labels regardless of host locale; a phone window (411 dp wide).
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AdaptiveNavigationLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun Harness(
        layout: WindowLayout,
        selectedTab: SelectedTab = SelectedTab.LISTEN,
        onSelect: (SelectedTab) -> Unit = {}
    ) {
        AudiobookTheme(darkTheme = true) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AdaptiveNavigationLayout(
                        layout = layout,
                        selectedTab = selectedTab,
                        bookDetailOpen = false,
                        onSelect = onSelect
                    ) {
                        Box(modifier = Modifier.fillMaxSize().testTag("pane_content"))
                    }
                }
                AppBottomBarSlot(layout = layout, selectedTab = selectedTab, onSelect = onSelect)
            }
        }
    }

    @Test
    fun phoneWindowKeepsTheBottomBarAndHasNoRail() {
        composeTestRule.setContent { Harness(WindowLayout.COMPACT) }

        composeTestRule.onNodeWithTag("bottom_navigation_bar").assertExists().assertIsDisplayed()
        // The phone contract is the four `tab_*` destinations, «Друзі» included
        // (ADR-0049 / #898) — the rail must not have changed that surface.
        listOf("listen", "explore", "library", "friends").forEach { section ->
            composeTestRule.onNodeWithTag("tab_$section").assertExists()
        }
        composeTestRule.onNodeWithTag("navigation_rail").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rail_tab_listen").assertDoesNotExist()
        composeTestRule.onNodeWithTag("pane_content").assertExists()
    }

    @Test
    fun wideWindowReplacesTheBottomBarWithTheRail() {
        composeTestRule.setContent { Harness(WindowLayout.EXPANDED) }

        composeTestRule.onNodeWithTag("navigation_rail").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail_tab_listen").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail_tab_explore").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail_tab_library").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail_tab_friends").assertExists().assertIsDisplayed()
        // «Бічна навігація ЗАМІСТЬ нижньої»: the bar is not merely hidden.
        composeTestRule.onNodeWithTag("bottom_navigation_bar").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tab_listen").assertDoesNotExist()
        composeTestRule.onNodeWithTag("pane_content").assertExists()
    }

    @Test
    fun theRailCarriesAllFourWorkingSectionsOfAdr0049() {
        // ADR-0049 / #898 fix the map at four sections; the rail must not drop
        // one just because it is a different surface than the bar.
        composeTestRule.setContent { Harness(WindowLayout.EXPANDED) }

        listOf("listen", "explore", "library", "friends").forEach { section ->
            composeTestRule.onNodeWithTag("rail_tab_$section")
                .assertExists()
                .assertIsDisplayed()
        }
    }

    @Test
    fun railLeadsTheContentInsteadOfSittingBelowIt() {
        composeTestRule.setContent { Harness(WindowLayout.EXPANDED) }

        val rail = composeTestRule.onNodeWithTag("navigation_rail")
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag("pane_content")
            .getUnclippedBoundsInRoot()

        assertTrue(
            "rail (right=${rail.right}) must end before the content starts " +
                "(left=${content.left})",
            rail.right.value <= content.left.value
        )
    }

    @Test
    fun railItemsCarryTheSameUkrainianNamesAndSelectedStateAsTheBar() {
        composeTestRule.setContent {
            Harness(WindowLayout.EXPANDED, selectedTab = SelectedTab.EXPLORE)
        }

        composeTestRule.onNodeWithTag("rail_tab_listen").assertTextEquals("Слухати")
        composeTestRule.onNodeWithTag("rail_tab_explore")
            .assertIsSelected()
            .assertTextEquals("Огляд")
        composeTestRule.onNodeWithTag("rail_tab_library").assertTextEquals("Мої книги")
        composeTestRule.onNodeWithTag("rail_tab_friends").assertTextEquals("Друзі")
        // ADR-0049 stands on both surfaces: settings stay behind the gear.
        composeTestRule.onNodeWithTag("rail_tab_settings").assertDoesNotExist()
    }

    @Test
    fun railClickReportsTheSameDestinationTheBarWould() {
        var selected: SelectedTab? = null
        composeTestRule.setContent {
            Harness(WindowLayout.EXPANDED) { tab -> selected = tab }
        }

        composeTestRule.onNodeWithTag("rail_tab_library").performClick()
        assertEquals(SelectedTab.LIBRARY, selected)

        composeTestRule.onNodeWithTag("rail_tab_friends").performClick()
        assertEquals(SelectedTab.FRIENDS, selected)
    }

    @Test
    fun railKeepsEveryDestinationReachableAtTwoHundredPercentFontScale() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                Harness(WindowLayout.EXPANDED)
            }
        }

        listOf("listen", "explore", "library", "friends").forEach { section ->
            composeTestRule.onNodeWithTag("rail_tab_$section")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(24.dp)
        }
    }
}
