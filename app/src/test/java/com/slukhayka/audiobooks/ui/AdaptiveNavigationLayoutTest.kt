package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.test.onRoot
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
        miniPlayer: (@Composable () -> Unit)? = null,
        onSelect: (SelectedTab) -> Unit = {}
    ) {
        AudiobookTheme(darkTheme = true) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AdaptiveNavigationLayout(
                        layout = layout,
                        selectedTab = selectedTab,
                        bookDetailOpen = false,
                        onSelect = onSelect,
                        miniPlayer = miniPlayer
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

    // ---------------------------------------------------------------- #1205

    /**
     * #1205 — the mini-player's SECOND slot: under the rail, inside the same
     * leading column, with the content rectangle starting only after it.
     *
     * The frame is an 840 dp window because that is the window the slot exists
     * for (`WindowLayout.PlayerPaneMinWidthDp`); the harness still passes the
     * layout explicitly, so this test is about the SLOT, not about the width
     * line — the line itself is pinned in `WindowLayoutTest`.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun theMiniPlayerSlotSitsInTheLeadingColumnUnderTheRail() {
        composeTestRule.setContent {
            Harness(
                layout = WindowLayout.EXPANDED,
                miniPlayer = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("mini_player_slot")
                    )
                }
            )
        }

        val rail = composeTestRule.onNodeWithTag("navigation_rail")
            .getUnclippedBoundsInRoot()
        val slot = composeTestRule.onNodeWithTag("mini_player_slot")
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag("pane_content")
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the bar must sit UNDER the rail (rail bottom=${rail.bottom}, " +
                "slot top=${slot.top})",
            slot.top.value >= rail.bottom.value - 1f
        )
        assertTrue(
            "the bar must sit in the leading column, left of the content " +
                "(slot right=${slot.right}, content left=${content.left})",
            slot.right.value <= content.left.value + 1f
        )
        assertTrue("the bar was not laid out: $slot", (slot.bottom.value - slot.top.value) > 0f)
    }

    /**
     * #1205 — the point the owner named: with the bar in the leading column the
     * CONTENT rectangle keeps the window's whole height, so starting or
     * stopping playback no longer pushes the screen up and down. The bar pays
     * for itself out of the navigation column.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun theBarInTheLeadingColumnDoesNotShortenTheContentRectangle() {
        composeTestRule.setContent {
            Harness(
                layout = WindowLayout.EXPANDED,
                miniPlayer = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("mini_player_slot")
                    )
                }
            )
        }

        val content = composeTestRule.onNodeWithTag("pane_content")
            .getUnclippedBoundsInRoot()
        val rail = composeTestRule.onNodeWithTag("navigation_rail")
            .getUnclippedBoundsInRoot()
        val window = composeTestRule.onRoot().getUnclippedBoundsInRoot()

        assertEquals(
            "the content column must keep the WINDOW's full height",
            window.bottom.value - window.top.value,
            content.bottom.value - content.top.value,
            1f
        )
        assertTrue(
            "the bar must take its height from the navigation column, not " +
                "from the content (rail=${rail.bottom.value - rail.top.value} dp, " +
                "content=${content.bottom.value - content.top.value} dp)",
            (rail.bottom.value - rail.top.value) <
                (content.bottom.value - content.top.value) - 1f
        )
    }

    /**
     * The phone contract is untouched: [AdaptiveNavigationLayout] draws no
     * leading column at COMPACT, so a mini-player handed to it is not placed
     * there — the app keeps that bar in the Scaffold's `bottomBar`, which is
     * where every phone has always had it.
     */
    @Test
    fun aCompactWindowPlacesNoMiniPlayerInANavigationColumn() {
        composeTestRule.setContent {
            Harness(
                layout = WindowLayout.COMPACT,
                miniPlayer = {
                    Box(modifier = Modifier.fillMaxSize().testTag("mini_player_slot"))
                }
            )
        }

        composeTestRule.onNodeWithTag("mini_player_slot").assertDoesNotExist()
        composeTestRule.onNodeWithTag("navigation_rail").assertDoesNotExist()
        composeTestRule.onNodeWithTag("navigation_column").assertDoesNotExist()
        composeTestRule.onNodeWithTag("pane_content").assertExists()
    }
}
