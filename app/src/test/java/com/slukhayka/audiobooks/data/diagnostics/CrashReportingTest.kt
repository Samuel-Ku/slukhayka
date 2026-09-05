package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingTest {

    @Test
    fun `first held failure asks once and approval sends it plus future reports`() {
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
        assertFalse(reporting.state.value.shouldShowPrompt)
    }

    @Test
    fun `denial deletes the held report and never asks again`() {
        val store = FakeConsentStore(CrashConsent.UNDECIDED)
        val firstSink = FakeCrashReportSink(hasUnsentReports = true)
        val firstRun = CrashReporting(store, firstSink, enabledForBuild = true)
        firstRun.start()

        firstRun.denyTriggeringReport()

        assertEquals(CrashConsent.DENIED, store.consent)
        assertTrue(firstSink.deletedReports)
        assertFalse(firstSink.automaticCollectionEnabled)

        val nextSink = FakeCrashReportSink(hasUnsentReports = true)
        val nextRun = CrashReporting(store, nextSink, enabledForBuild = true)
        nextRun.start()

        assertFalse(nextRun.state.value.shouldShowPrompt)
        assertTrue(nextSink.deletedReports)
        assertFalse(nextSink.sentReports)
    }

    @Test
    fun `enabling in settings after denial deletes old reports and enables only the future`() {
        val store = FakeConsentStore(CrashConsent.DENIED)
        val sink = FakeCrashReportSink(hasUnsentReports = true)
        val reporting = CrashReporting(store, sink, enabledForBuild = true)
        reporting.start()

        reporting.setAllowedFromSettings(true)

        assertEquals(CrashConsent.ALLOWED, store.consent)
        assertTrue(sink.deletedReports)
        assertFalse(sink.sentReports)
        assertTrue(sink.automaticCollectionEnabled)

        reporting.setAllowedFromSettings(false)

        assertEquals(CrashConsent.DENIED, store.consent)
        assertFalse(sink.automaticCollectionEnabled)
    }

    @Test
    fun `debug build never collects even when persisted consent is allowed`() {
        val store = FakeConsentStore(CrashConsent.ALLOWED)
        val sink = FakeCrashReportSink(hasUnsentReports = true)
        val reporting = CrashReporting(store, sink, enabledForBuild = false)

        reporting.start()
        reporting.setAppVisibility(AppVisibility.BACKGROUND)
        reporting.setPlayback(DiagnosticPlaybackState.PLAYING, DiagnosticAudioOrigin.REMOTE)

        assertFalse(sink.automaticCollectionEnabled)
        assertFalse(sink.sentReports)
        assertEquals(null, sink.reportedContext)
        assertFalse(reporting.state.value.shouldShowPrompt)
    }

    @Test
    fun `bounded context exposes exactly the five approved Crashlytics keys`() {
        val sink = FakeCrashReportSink(hasUnsentReports = false)
        val reporting = CrashReporting(
            consentStore = FakeConsentStore(CrashConsent.ALLOWED),
            sink = sink,
            enabledForBuild = true
        )
        reporting.setAppVisibility(AppVisibility.BACKGROUND)
        reporting.setPlayback(DiagnosticPlaybackState.BUFFERING, DiagnosticAudioOrigin.REMOTE)
        reporting.setPlaybackService(DiagnosticPlaybackService.STARTED)
        reporting.setCastActive(true)

        assertEquals(
            mapOf(
                "app_visibility" to "background",
                "playback_state" to "buffering",
                "playback_service" to "started",
                "audio_origin" to "remote",
                "cast_active" to "true"
            ),
            sink.reportedContext?.customKeys
        )
    }

    @Test
    fun `release context is also published as a bounded process state summary`() {
        val summarySink = FakeProcessStateSummarySink()
        val reporting = CrashReporting(
            consentStore = FakeConsentStore(CrashConsent.DENIED),
            sink = FakeCrashReportSink(hasUnsentReports = false),
            enabledForBuild = true,
            processStateSummarySink = summarySink
        )

        reporting.start()
        reporting.setPlayback(DiagnosticPlaybackState.PLAYING, DiagnosticAudioOrigin.LOCAL)

        assertEquals(DiagnosticPlaybackState.PLAYING, summarySink.context?.playbackState)
        assertEquals(DiagnosticAudioOrigin.LOCAL, summarySink.context?.audioOrigin)
    }

    private class FakeConsentStore(initial: CrashConsent) : CrashConsentStore {
        var consent = initial
            private set

        override fun load(): CrashConsent = consent

        override fun save(consent: CrashConsent) {
            this.consent = consent
        }
    }

    private class FakeCrashReportSink(
        private val hasUnsentReports: Boolean
    ) : CrashReportSink {
        var automaticCollectionEnabled = false
        var sentReports = false
        var deletedReports = false
        var reportedContext: CrashContext? = null

        override fun setCollectionEnabled(enabled: Boolean) {
            automaticCollectionEnabled = enabled
        }

        override fun checkForUnsentReports(onResult: (Boolean) -> Unit) {
            onResult(hasUnsentReports)
        }

        override fun sendUnsentReports() {
            sentReports = true
        }

        override fun deleteUnsentReports() {
            deletedReports = true
        }

        override fun setContext(context: CrashContext) {
            reportedContext = context
        }

        override fun recordUnexpectedPlaybackExit(event: UnexpectedPlaybackExitEvent) = Unit
    }

    private class FakeProcessStateSummarySink : ProcessStateSummarySink {
        var context: CrashContext? = null
        override fun set(context: CrashContext) {
            this.context = context
        }
    }
}
