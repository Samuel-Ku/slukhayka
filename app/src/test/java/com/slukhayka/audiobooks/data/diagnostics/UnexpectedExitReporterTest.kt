package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnexpectedExitReporterTest {

    @Test
    fun `consented actionable exit is emitted once and deduplicated by timestamp plus hash`() {
        val exit = actionableExit()
        val source = FakeHistoricalExitSource(supported = true, latest = exit)
        val cursorStore = FakeExitCursorStore()
        val sink = FakeCrashReportSink()
        val reporting = CrashReporting(FakeConsentStore(CrashConsent.ALLOWED), sink, true)
        val reporter = UnexpectedExitReporter(source, cursorStore, reporting, enabledForBuild = true)

        reporter.inspectLatest()
        reporter.inspectLatest()

        assertEquals(listOf(exit.timestampMillis), sink.unexpectedExits.map { it.timestampMillis })
        assertEquals(exit.timestampMillis, cursorStore.cursor?.timestampMillis)
        assertNotNull(cursorStore.cursor?.stableHash)
        assertEquals(64, cursorStore.cursor?.stableHash?.length)
    }

    @Test
    fun `undecided actionable exit schedules the same prompt for next launch while denial stays silent`() {
        val undecidedSink = FakeCrashReportSink()
        val undecidedStore = FakeConsentStore(CrashConsent.UNDECIDED)
        val undecidedReporting = CrashReporting(
            undecidedStore,
            undecidedSink,
            true
        )
        UnexpectedExitReporter(
            FakeHistoricalExitSource(true, actionableExit()),
            FakeExitCursorStore(),
            undecidedReporting,
            true
        ).inspectLatest()

        assertEquals(1, undecidedSink.unexpectedExits.size)
        assertFalse(undecidedReporting.state.value.shouldShowPrompt)
        val nextLaunch = CrashReporting(undecidedStore, FakeCrashReportSink(), true)
        nextLaunch.start()
        assertTrue(nextLaunch.state.value.shouldShowPrompt)

        val deniedSink = FakeCrashReportSink()
        val deniedReporting = CrashReporting(FakeConsentStore(CrashConsent.DENIED), deniedSink, true)
        UnexpectedExitReporter(
            FakeHistoricalExitSource(true, actionableExit()),
            FakeExitCursorStore(),
            deniedReporting,
            true
        ).inspectLatest()

        assertTrue(deniedSink.unexpectedExits.isEmpty())
        assertFalse(deniedReporting.state.value.shouldShowPrompt)
    }

    @Test
    fun `unsupported platform does not inspect or report exits`() {
        val sink = FakeCrashReportSink()
        val reporting = CrashReporting(FakeConsentStore(CrashConsent.ALLOWED), sink, true)
        val unsupported = FakeHistoricalExitSource(supported = false, latest = actionableExit())

        UnexpectedExitReporter(
            unsupported,
            FakeExitCursorStore(),
            reporting,
            enabledForBuild = true
        ).inspectLatest()

        assertEquals(0, unsupported.reads)
        assertTrue(sink.unexpectedExits.isEmpty())
    }

    @Test
    fun `debug build does not inspect supported platform`() {
        val sink = FakeCrashReportSink()
        val reporting = CrashReporting(FakeConsentStore(CrashConsent.ALLOWED), sink, false)
        val source = FakeHistoricalExitSource(supported = true, latest = actionableExit())

        UnexpectedExitReporter(
            source,
            FakeExitCursorStore(),
            reporting,
            enabledForBuild = false
        ).inspectLatest()

        assertEquals(0, source.reads)
        assertTrue(sink.unexpectedExits.isEmpty())
    }

    private fun actionableExit() = ProcessExitSnapshot(
        timestampMillis = 1_725_000_123_456L,
        reason = ProcessExitReason.LOW_MEMORY,
        status = 9,
        importance = ProcessImportance.FOREGROUND_SERVICE,
        rssKb = 131_072L,
        pssKb = 65_536L,
        appVersionCode = 7L,
        androidApi = 35,
        state = CrashContext(
            appVisibility = AppVisibility.BACKGROUND,
            playbackState = DiagnosticPlaybackState.PLAYING,
            playbackService = DiagnosticPlaybackService.STARTED,
            audioOrigin = DiagnosticAudioOrigin.REMOTE,
            castActive = false
        )
    )

    private class FakeHistoricalExitSource(
        override val supported: Boolean,
        private val latest: ProcessExitSnapshot?
    ) : HistoricalProcessExitSource {
        var reads = 0

        override fun latest(): ProcessExitSnapshot? {
            reads += 1
            return latest
        }
    }

    private class FakeExitCursorStore : ProcessExitCursorStore {
        var cursor: ProcessExitCursor? = null

        override fun load(): ProcessExitCursor? = cursor
        override fun save(cursor: ProcessExitCursor) {
            this.cursor = cursor
        }
    }

    private class FakeConsentStore(initial: CrashConsent) : CrashConsentStore {
        private var consent = initial
        private var failurePromptPending = false
        override fun load(): CrashConsent = consent
        override fun save(consent: CrashConsent) {
            this.consent = consent
        }
        override fun markFailurePromptPending() {
            failurePromptPending = true
        }
        override fun consumeFailurePromptPending(): Boolean = failurePromptPending.also { failurePromptPending = false }
    }

    private class FakeCrashReportSink : CrashReportSink {
        val unexpectedExits = mutableListOf<UnexpectedPlaybackExitEvent>()
        override fun setCollectionEnabled(enabled: Boolean) = Unit
        override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
        override fun sendUnsentReports() = Unit
        override fun deleteUnsentReports() = Unit
        override fun setContext(context: CrashContext) = Unit
        override fun recordUnexpectedPlaybackExit(event: UnexpectedPlaybackExitEvent) {
            unexpectedExits += event
        }
    }
}
