package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashContextTrackerTest {
    @Test
    fun `playback evidence keeps updating after app moves to background`() {
        val sink = TrackerSink()
        val marker = TrackerMarker()
        val reporting = CrashReporting(TrackerConsentStore(), sink, enabledForBuild = true)
        val tracker = CrashContextTracker(reporting, marker)
        reporting.start()

        tracker.updateAppVisibility(AppVisibility.BACKGROUND)
        tracker.updatePlaybackService(DiagnosticPlaybackService.STARTED)
        tracker.updatePlayback(
            PlaybackDiagnosticSnapshot(
                state = DiagnosticPlaybackState.PLAYING,
                audioOrigin = DiagnosticAudioOrigin.REMOTE,
                castActive = true
            )
        )

        assertTrue(marker.active)
        assertEquals("background", sink.keys["app_visibility"])
        assertEquals("foreground", sink.keys["playback_service"])
        assertEquals("true", sink.keys["cast_active"])
    }

    @Test
    fun `explicit Cast transition is published without waiting for playback state`() {
        val sink = TrackerSink()
        val tracker = CrashContextTracker(
            CrashReporting(TrackerConsentStore(), sink, enabledForBuild = true).also { it.start() },
            TrackerMarker()
        )

        tracker.updateCastActive(true)

        assertEquals("true", sink.keys["cast_active"])
    }
}

private class TrackerMarker : PlaybackActivityMarker {
    var active = false
    override fun setPlaybackActive(active: Boolean) {
        this.active = active
    }
}

private class TrackerConsentStore : CrashConsentStore {
    override fun load() = CrashConsent.ALLOWED
    override fun save(consent: CrashConsent) = Unit
}

private class TrackerSink : CrashReportSink {
    var keys: Map<String, String> = emptyMap()
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
    override fun sendUnsentReports() = Unit
    override fun deleteUnsentReports() = Unit
    override fun setCustomKeys(keys: Map<String, String>) {
        this.keys = keys
    }
    override fun record(exception: Throwable) = Unit
}
