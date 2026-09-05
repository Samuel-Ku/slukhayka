package com.slukhayka.audiobooks.data.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidHistoricalProcessExitSourceTest {
    @Test
    fun `reads only bounded fields from newest historical exit`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val manager = application.getSystemService(ActivityManager::class.java)
        val context = CrashContext(
            appVisibility = AppVisibility.BACKGROUND,
            playbackState = DiagnosticPlaybackState.PLAYING,
            playbackService = DiagnosticPlaybackService.STARTED,
            audioOrigin = DiagnosticAudioOrigin.LOCAL
        )
        val info = ApplicationExitInfoBuilder.newBuilder()
            .setProcessName("must-not-cross-boundary")
            .setDescription("must-not-cross-boundary")
            .setTimestamp(1234L)
            .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
            .setStatus(9)
            .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
            .setRss(4096L)
            .setPss(2048L)
            .setProcessStateSummary(
                ProcessStateSummaryCodec.encode(
                    context = context,
                    appVersionCode = 41L,
                    androidApi = 34
                )
            )
            .build()
        shadowOf(manager).addApplicationExitInfo(info)

        val snapshot = AndroidHistoricalProcessExitSource(application).latest()!!

        assertTrue(AndroidHistoricalProcessExitSource(application).supported)
        assertEquals(ProcessExitReason.LOW_MEMORY, snapshot.reason)
        assertEquals(ProcessImportance.FOREGROUND_SERVICE, snapshot.importance)
        assertEquals(context, snapshot.state)
        assertEquals(41L, snapshot.appVersionCode)
        assertEquals(34, snapshot.androidApi)
    }
}
