package com.slukhayka.audiobooks.data.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Android10ProcessExitSourceTest {
    @Test
    fun `adapter neither reads exits nor writes process summary`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val source = AndroidHistoricalProcessExitSource(application)

        assertFalse(source.supported)
        assertNull(source.latest())
        source.set(CrashContext(playbackState = DiagnosticPlaybackState.PLAYING))
    }
}
