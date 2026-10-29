package com.slukhayka.audiobooks.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.AudiobookApp
import com.slukhayka.audiobooks.ui.screens.SettingsDestination
import com.slukhayka.audiobooks.ui.screens.SettingsScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F18 of the 2026-10-28 device audit — the dead door to «Досягнення».
 *
 * The destination, its pane, its heading and `MainViewModel.openAchievements()`
 * all existed, but no settings row listed [SettingsDestination.Achievements]:
 * the finished «Досягнення» screen was unreachable from the UI (found during the
 * QA walk, not by a test, because every test rendered the destination directly).
 *
 * Two proofs, deliberately different in cost:
 *  - the SCREEN proves the row exists, is reachable by scrolling, and reports
 *    the destination through `onOpen` (cheap, no Room);
 *  - the ROOT proves the whole composition wires that report to the real pane:
 *    the gear opens Settings, the row click opens `achievements_screen_pane`
 *    and its heading. A screen wired only in its own default lambda cannot pass
 *    the second test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsAchievementsEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
                .getOrDefault(false)
        }
    }

    @Test
    fun `the settings screen offers the achievements row and reports it`() {
        var opened: SettingsDestination? = null
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SettingsScreen(onOpen = { opened = it })
                }
            }
        }

        compose.onNodeWithTag("settings_Achievements")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            "the row must report ITS destination, not just any click",
            SettingsDestination.Achievements,
            opened,
        )
    }

    @Test
    fun `the achievements row opens the achievements pane from the real app root`() {
        val (viewModel, backDispatcher) = composeRoot()

        awaitTag("settings_gear")
        compose.onNodeWithTag("settings_gear").performClick()
        awaitTag("settings_screen")

        compose.onNodeWithTag("settings_Achievements").performScrollTo().performClick()

        awaitTag("achievements_screen_pane")
        assertEquals(
            "the ViewModel must be asked to open the destination",
            true,
            viewModel.achievementsOpen.value,
        )
        compose.onNodeWithTag("achievements_screen_pane").assertIsDisplayed()
        compose.onNodeWithTag("achievements_screen_heading").assertIsDisplayed()

        // BACK from the pushed screen returns to Settings, it does not switch
        // the tab: the settings BackHandler must own this panel (the F18 entry
        // made it reachable, and before that the handler never saw it).
        compose.runOnIdle { backDispatcher.onBackPressed() }
        awaitTag("settings_screen")
        assertEquals(false, viewModel.achievementsOpen.value)
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
