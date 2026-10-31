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

    // #1742 — the playerState collectors now start from start(), called by
    // AudiobookApp's first effect, not from the constructor. The behaviour is
    // unchanged: an already-playing session at start() still keeps the bar.
    @Test
    fun `view model built during an already playing session keeps the bar`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.playerManager.mirrorCastState { it.copy(isPlaying = true) }

        val viewModel = MainViewModel(app)
        viewModel.start()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(viewModel.miniPlayerDismissed.value)
    }

    @Test
    fun `closing a paused player hides the bar without touching playback`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        viewModel.start()

        viewModel.dismissMiniPlayer()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.miniPlayerDismissed.value)
        assertFalse(viewModel.playerState.value.isPlaying)
    }

    @Test
    fun `closing a playing player pauses it and hides the bar`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        viewModel.start()
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
        viewModel.start()
        viewModel.dismissMiniPlayer()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.miniPlayerDismissed.value)

        app.playerManager.mirrorCastState { it.copy(isPlaying = true) }
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(viewModel.miniPlayerDismissed.value)
    }
}
