package com.slukhayka.audiobooks.player

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceLegacyStartTest {

    @Test
    fun mediaButtonWithoutDataIsTheLegacyHandshake() {
        assertTrue(isLegacyMediaButtonStart(Intent(Intent.ACTION_MEDIA_BUTTON)))
    }

    @Test
    fun mediaButtonWithSessionUriIsNotTheLegacyHandshake() {
        assertFalse(
            isLegacyMediaButtonStart(
                Intent(Intent.ACTION_MEDIA_BUTTON).setData(Uri.parse("content://media/session/1"))
            )
        )
    }

    @Test
    fun otherActionsAreNotTheLegacyHandshake() {
        assertFalse(isLegacyMediaButtonStart(Intent(Intent.ACTION_PLAY)))
        assertFalse(isLegacyMediaButtonStart(null))
    }
}
