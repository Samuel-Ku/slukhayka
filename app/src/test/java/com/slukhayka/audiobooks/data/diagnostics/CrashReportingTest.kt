package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // #1723 — the constructor no longer reads consent; the running app
        // starts the module before publishing diagnostic context.
        reporting.start()
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

    @Test
    fun `context before start is not published to a listener who later denies`() {
        val sink = FakeCrashReportSink(hasUnsentReports = false)
        val reporting = CrashReporting(
            FakeConsentStore(CrashConsent.DENIED),
            sink,
            enabledForBuild = true
        )

        // Activity.onStart can land before the IO batch has loaded consent.
        reporting.setAppVisibility(AppVisibility.BACKGROUND)
        assertNull("no sink write before consent is known", sink.reportedContext)

        reporting.start()
        assertNull("a denied listener's context is never published", sink.reportedContext)
        assertFalse(sink.automaticCollectionEnabled)
    }

    @Test
    fun `context accumulated before start is published once consent allows it`() {
        val sink = FakeCrashReportSink(hasUnsentReports = false)
        val reporting = CrashReporting(
            FakeConsentStore(CrashConsent.ALLOWED),
            sink,
            enabledForBuild = true
        )

        reporting.setAppVisibility(AppVisibility.BACKGROUND)
        assertNull(sink.reportedContext)

        reporting.start(CrashConsent.ALLOWED)
        assertEquals(AppVisibility.BACKGROUND, sink.reportedContext?.appVisibility)
    }

    @Test
    fun `a context update that lands while start publishes is not overwritten`() {
        val sink = FakeCrashReportSink(hasUnsentReports = false)
        val summaries = mutableListOf<CrashContext>()
        var reporting: CrashReporting? = null
        val summarySink = ProcessStateSummarySink { context ->
            summaries += context
            if (context.appVisibility == AppVisibility.UNKNOWN) {
                // Activity.onStart can update visibility while start() is
                // publishing the accumulated context. On the old code
                // started was still false here, so this update never reached
                // the sinks and start() then published the stale UNKNOWN.
                reporting?.setAppVisibility(AppVisibility.FOREGROUND)
            }
        }
        reporting = CrashReporting(
            FakeConsentStore(CrashConsent.ALLOWED),
            sink,
            enabledForBuild = true,
            processStateSummarySink = summarySink
        )

        reporting.start(CrashConsent.ALLOWED)

        assertEquals(AppVisibility.FOREGROUND, summaries.last().appVisibility)
        assertEquals(AppVisibility.FOREGROUND, sink.reportedContext?.appVisibility)
    }

    @Test
    fun `construction performs no consent read and start accepts the startup batch value`() {
        val store = CountingConsentStore(CrashConsent.ALLOWED)
        val sink = FakeCrashReportSink(hasUnsentReports = false)

        val reporting = CrashReporting(store, sink, enabledForBuild = true)
        assertEquals("#1723: the constructor can run on main", 0, store.loads)

        // The composition root already loaded the value on IO; start() must use
        // it rather than reading prefs again on whichever thread constructed it.
        reporting.start(CrashConsent.ALLOWED)
        assertEquals(0, store.loads)
        assertEquals(CrashConsent.ALLOWED, reporting.state.value.consent)
        assertTrue(sink.automaticCollectionEnabled)
    }

    private class CountingConsentStore(initial: CrashConsent) : CrashConsentStore {
        var loads = 0
            private set
        private var consent = initial

        override fun load(): CrashConsent {
            loads++
            return consent
        }

        override fun save(consent: CrashConsent) {
            this.consent = consent
        }
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
