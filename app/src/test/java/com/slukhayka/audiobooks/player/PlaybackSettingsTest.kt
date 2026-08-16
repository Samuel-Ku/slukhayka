package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Global playback preferences (wayfinder #26). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default speed starts at 1x`() {
        assertEquals(1.0f, PlaybackSettings(context).defaultSpeed, 0.001f)
    }

    @Test
    fun `a new default survives across instances`() {
        val settings = PlaybackSettings(context)
        settings.defaultSpeed = 1.5f

        val fresh = PlaybackSettings(context)
        assertEquals(1.5f, fresh.defaultSpeed, 0.001f)
    }
}
