package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingTest {
    @Test
    fun `held failure asks once and approval sends it plus future reports`() {
        val store = FakeConsentStore(CrashConsent.UNDECIDED)
        val sink = FakeCrashReportSink(hasUnsentReports = true)
        val reporting = CrashReporting(store, sink, enabledForBuild = true)

        reporting.start()
        assertFalse(sink.automaticCollectionEnabled)
        assertTrue(reporting.state.value.shouldShowPrompt)

        reporting.allowTriggeringReport()
        assertEquals(CrashConsent.ALLOWED, store.consent)
        assertTrue(sink.sentReports)
        assertTrue(sink.automaticCollectionEnabled)
    }

    @Test
    fun `denial deletes held evidence and is not asked again`() {
        val store = FakeConsentStore(CrashConsent.UNDECIDED)
        val firstSink = FakeCrashReportSink(hasUnsentReports = true)
        CrashReporting(store, firstSink, enabledForBuild = true).apply {
            start()
            denyTriggeringReport()
        }

        val nextSink = FakeCrashReportSink(hasUnsentReports = true)
        val nextRun = CrashReporting(store, nextSink, enabledForBuild = true)
        nextRun.start()

        assertEquals(CrashConsent.DENIED, store.consent)
        assertFalse(nextRun.state.value.shouldShowPrompt)
        assertTrue(nextSink.deletedReports)
        assertFalse(nextSink.sentReports)
    }

    @Test
    fun `settings approval after denial is future-only`() {
        val store = FakeConsentStore(CrashConsent.DENIED)
        val sink = FakeCrashReportSink(hasUnsentReports = true)
        val reporting = CrashReporting(store, sink, enabledForBuild = true)

        reporting.start()
        reporting.setAllowedFromSettings(true)

        assertTrue(sink.deletedReports)
        assertFalse(sink.sentReports)
        assertTrue(sink.automaticCollectionEnabled)
    }

    @Test
    fun `debug build never collects even with persisted approval`() {
        val sink = FakeCrashReportSink(hasUnsentReports = true)
        val reporting = CrashReporting(
            FakeConsentStore(CrashConsent.ALLOWED),
            sink,
            enabledForBuild = false
        )

        reporting.start()

        assertFalse(sink.automaticCollectionEnabled)
        assertFalse(sink.sentReports)
        assertFalse(reporting.state.value.shouldShowPrompt)
    }

    @Test
    fun `denied listener retains no diagnostic context or controlled exit`() {
        val sink = FakeCrashReportSink(hasUnsentReports = false)
        val reporting = CrashReporting(
            FakeConsentStore(CrashConsent.DENIED),
            sink,
            enabledForBuild = true
        )
        reporting.start()

        reporting.updateContext(CrashContext(appVisibility = AppVisibility.BACKGROUND))
        reporting.record(UnexpectedPlaybackExit(ExitReason.LOW_MEMORY))

        assertTrue(sink.keys.isEmpty())
        assertFalse(sink.recordedException)
    }

    @Test
    fun `context exposes exactly the five approved bounded keys`() {
        assertEquals(
            mapOf(
                "app_visibility" to "background",
                "playback_state" to "buffering",
                "playback_service" to "foreground",
                "audio_origin" to "remote",
                "cast_active" to "true"
            ),
            CrashContext(
                appVisibility = AppVisibility.BACKGROUND,
                playbackState = DiagnosticPlaybackState.BUFFERING,
                playbackService = DiagnosticPlaybackService.FOREGROUND,
                audioOrigin = DiagnosticAudioOrigin.REMOTE,
                castActive = true
            ).customKeys
        )
    }

    private class FakeConsentStore(initial: CrashConsent) : CrashConsentStore {
        var consent = initial
        override fun load() = consent
        override fun save(consent: CrashConsent) { this.consent = consent }
    }

    private class FakeCrashReportSink(
        private val hasUnsentReports: Boolean
    ) : CrashReportSink {
        var automaticCollectionEnabled = false
        var sentReports = false
        var deletedReports = false
        var keys: Map<String, String> = emptyMap()
        var recordedException = false

        override fun setCollectionEnabled(enabled: Boolean) { automaticCollectionEnabled = enabled }
        override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(hasUnsentReports)
        override fun sendUnsentReports() { sentReports = true }
        override fun deleteUnsentReports() { deletedReports = true }
        override fun setCustomKeys(keys: Map<String, String>) { this.keys = keys }
        override fun record(exception: Throwable) { recordedException = true }
    }
}
