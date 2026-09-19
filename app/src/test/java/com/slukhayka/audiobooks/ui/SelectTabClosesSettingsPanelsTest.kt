package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #959 — every pushed Settings destination is transient: switching a
 * bottom-bar tab drops it, so the next tab never shows a stale panel. The
 * mismatch the issue found was `SourceAudioRefusal`, which [MainViewModel.selectTab]
 * forgot while its six siblings were already closed there.
 *
 * The list is the whole invariant, not just the regression: a new destination
 * that skips the `close…()` call in `selectTab` fails this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SelectTabClosesSettingsPanelsTest {

    private class Panel(
        val name: String,
        val open: (MainViewModel) -> Unit,
        val isOpen: (MainViewModel) -> StateFlow<Boolean>
    )

    private val panels = listOf(
        Panel("Profile", { it.openProfileSettings() }, { it.profileOpen }),
        Panel("Storage", { it.openStorageDestination() }, { it.storageDestinationOpen }),
        Panel("NetworkPrivacy", { it.openPrivacySettings() }, { it.privacySettingsOpen }),
        Panel("Recommendations", { it.openRecommendationSettings() }, { it.recommendationSettingsOpen }),
        Panel("ContentLanguages", { it.openContentLanguages() }, { it.contentLanguagesOpen }),
        Panel("AppLocale", { it.openAppLocale() }, { it.appLocaleOpen }),
        Panel("SourceAudioRefusal", { it.openSourceAudioRefusal() }, { it.sourceAudioRefusalOpen })
    )

    @Test
    fun `switching tabs closes every pushed settings destination`() {
        val viewModel = MainViewModel(ApplicationProvider.getApplicationContext<App>())
        val stillOpen = mutableListOf<String>()

        for (panel in panels) {
            panel.open(viewModel)
            assertTrue("${panel.name} must be open before the switch", panel.isOpen(viewModel).value)
            viewModel.selectTab(SelectedTab.EXPLORE)
            if (panel.isOpen(viewModel).value) stillOpen += panel.name
        }

        assertTrue(
            "panels left behind by selectTab: $stillOpen of ${panels.size}",
            stillOpen.isEmpty()
        )
        drainMainLooper()
    }

    @Test
    fun `the source audio refusal panel does not survive a tab switch`() {
        val viewModel = MainViewModel(ApplicationProvider.getApplicationContext<App>())

        viewModel.openSourceAudioRefusal()
        assertTrue(viewModel.sourceAudioRefusalOpen.value)

        viewModel.selectTab(SelectedTab.LIBRARY)

        assertFalse(viewModel.sourceAudioRefusalOpen.value)
        drainMainLooper()
    }

    // viewModelScope is Dispatchers.Main.immediate: MainViewModel's init posts
    // work the test must let finish before Robolectric tears the looper down.
    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}
