package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Closing the mini player: the bar leaves the screen, audio pauses if it was
 * playing, and nothing about the session is forgotten — audio starting again
 * from any surface brings the bar back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniPlayerDismissalTest {

    // viewModelScope is Dispatchers.Main.immediate, so its first playerState
    // emission can be delivered while the constructor is still assigning
    // fields. An already-playing session at construction time is the case that
    // used to reach a not-yet-initialised flag.
    @Test
    fun `view model built during an already playing session keeps the bar`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.playerManager.mirrorCastState { it.copy(isPlaying = true) }

        val viewModel = MainViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(viewModel.miniPlayerDismissed.value)
    }

    @Test
    fun `closing a paused player hides the bar without touching playback`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        viewModel.dismissMiniPlayer()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.miniPlayerDismissed.value)
        assertFalse(viewModel.playerState.value.isPlaying)
    }

    @Test
    fun `closing a playing player pauses it and hides the bar`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        app.playerManager.mirrorCastState { it.copy(isPlaying = true) }

        viewModel.dismissMiniPlayer()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.miniPlayerDismissed.value)
        assertFalse(viewModel.playerState.value.isPlaying)
    }

    @Test
    fun `audio resuming brings the dismissed bar back`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        viewModel.dismissMiniPlayer()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.miniPlayerDismissed.value)

        app.playerManager.mirrorCastState { it.copy(isPlaying = true) }
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(viewModel.miniPlayerDismissed.value)
    }
}
