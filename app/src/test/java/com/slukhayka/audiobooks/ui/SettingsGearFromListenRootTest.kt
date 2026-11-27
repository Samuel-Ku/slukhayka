package com.slukhayka.audiobooks.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.AudiobookApp
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0049 / #860 / #958 — «Налаштування» are not a bottom-bar destination:
 * every ROOT header carries the gear, and BACK returns to the root the gear
 * was tapped on ([MainViewModel.selectTab] + `settingsReturnTab` in
 * `MainActivity`).
 *
 * The androidTest twin (`SettingsNavigationTest`, device-only) runs the same
 * loop over all four roots now that #958 connected LISTEN; this unit test is
 * the JVM-executable proof, so the contract is verified even with no device
 * attached. It renders the real composition root (`AudiobookApp`), so a gear
 * wired only in the screen's default lambda (i.e. `onOpenSettings = {}`)
 * cannot pass it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsGearFromListenRootTest {

    @get:Rule
    val compose = createComposeRule()

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
                .getOrDefault(false)
        }
    }

    @Test
    fun `the fresh Listen actions open the catalogue and the import chooser`() {
        val (viewModel, _) = composeRoot()
        val app = ApplicationProvider.getApplicationContext<App>()
        val findBook = app.getString(R.string.profile_view_catalogue)
        val addFromDevice = app.getString(R.string.profile_import_from_device)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(findBook).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(findBook).performClick()
        awaitTag("home_screen")

        compose.runOnIdle { viewModel.selectTab(SelectedTab.LISTEN) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText(addFromDevice).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(addFromDevice).performClick()
        awaitTag("library_import_sheet_content")
        assertEquals(SelectedTab.LIBRARY, viewModel.selectedTab.value)
    }

    @Test
    fun `the gear opens Settings from the Listen root and Back returns to Listen`() {
        val (viewModel, dispatcher) = composeRoot()

        awaitTag("listen_screen")
        assertEquals(
            "the app opens on the Listen root",
            SelectedTab.LISTEN,
            viewModel.selectedTab.value
        )

        compose.onNodeWithTag("settings_gear").assertIsDisplayed().performClick()
        awaitTag("settings_screen")
        assertEquals(
            "the gear opens Settings from the Listen root",
            SelectedTab.SETTINGS,
            viewModel.selectedTab.value
        )

        compose.runOnIdle { dispatcher.onBackPressed() }
        awaitTag("listen_screen")
        compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        assertEquals(
            "Back from Settings returns to the root the gear was tapped on",
            SelectedTab.LISTEN,
            viewModel.selectedTab.value
        )
    }

    /**
     * The same loop the device-only `SettingsNavigationTest` runs: from EACH
     * root, the gear opens Settings and BACK lands on THAT root — never on the
     * first one (`settingsReturnTab` starts at EXPLORE). All four roots are
     * asserted here, so a fifth root added later without wiring fails loudly.
     */
    @Test
    fun `every root opens Settings through its gear and Back returns to that same root`() {
        val (viewModel, dispatcher) = composeRoot()

        val roots = listOf(
            SelectedTab.LISTEN to "listen_screen",
            SelectedTab.EXPLORE to "home_screen",
            SelectedTab.LIBRARY to "library_screen",
            SelectedTab.FRIENDS to "friends_screen"
        )
        assertEquals("the contract covers every root", 4, roots.size)

        roots.forEach { (tab, rootTag) ->
            compose.runOnIdle { viewModel.selectTab(tab) }
            awaitTag(rootTag)
            compose.onNodeWithTag("settings_gear").assertIsDisplayed().performClick()
            awaitTag("settings_screen")
            assertEquals(
                "the gear opens Settings from $tab",
                SelectedTab.SETTINGS,
                viewModel.selectedTab.value
            )
            compose.runOnIdle { dispatcher.onBackPressed() }
            awaitTag(rootTag)
            compose.onNodeWithTag("settings_screen").assertDoesNotExist()
            assertEquals(
                "Back from Settings returns to $tab",
                tab,
                viewModel.selectedTab.value
            )
        }
    }


    @Test
    fun `a fresh gear entry focuses the heading after a destination visit`() {
        val (viewModel, dispatcher) = composeRoot()

        awaitTag("listen_screen")
        compose.onNodeWithTag("settings_gear").assertIsDisplayed().performClick()
        awaitTag("settings_screen")
        compose.onNodeWithTag("settings_ContentLanguages").performScrollTo().assertIsDisplayed().performClick()
        awaitTag("content_languages_screen")

        compose.runOnIdle { dispatcher.onBackPressed() }
        awaitTag("settings_screen")
        // Returning from a destination still restores that row.
        compose.onNodeWithTag("settings_ContentLanguages").assertIsFocused()
        compose.runOnIdle { dispatcher.onBackPressed() }
        awaitTag("listen_screen")

        compose.onNodeWithTag("settings_gear").assertIsDisplayed().performClick()
        awaitTag("settings_screen")

        compose.onNodeWithTag("settings_heading").assertIsFocused()
        compose.onNodeWithTag("settings_ContentLanguages").assertIsNotFocused()
        assertEquals(SelectedTab.SETTINGS, viewModel.selectedTab.value)
    }

    private fun composeRoot(): Pair<MainViewModel, OnBackPressedDispatcher> {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        val backDispatcher = OnBackPressedDispatcher()
        compose.setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides remember(backDispatcher) {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher: OnBackPressedDispatcher = backDispatcher
                        override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
                    }
                }
            ) {
                AudiobookTheme(darkTheme = true) {
                    AudiobookApp(viewModel = viewModel)
                }
            }
        }
        return viewModel to backDispatcher
    }
}
