package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnexpectedPlaybackExitDetectorTest {
    @Test
    fun `one persisted exit is recorded no more than once`() {
        val ledger = DetectorLedger(active = true)
        val sink = DetectorSink()
        val reporting = CrashReporting(DetectorConsentStore(), sink, enabledForBuild = true)
        val detector = UnexpectedPlaybackExitDetector(
            ProcessExitHistory { HistoricalProcessExit(42L, ExitReason.LOW_MEMORY) },
            ledger
        )
        reporting.start()

        detector.inspect(reporting)
        ledger.active = true
        detector.inspect(reporting)

        assertEquals(1, sink.recorded)
        assertEquals(42L, ledger.lastExit)
        assertFalse(ledger.active)
    }
}

private class DetectorLedger(var active: Boolean) : ExitInspectionLedger {
    var lastExit = 0L
    override fun playbackWasActive() = active
    override fun setPlaybackActive(active: Boolean) {
        this.active = active
    }
    override fun lastInspectedExitTimestamp() = lastExit
    override fun markExitInspected(timestamp: Long) {
        lastExit = timestamp
    }
}

private class DetectorConsentStore : CrashConsentStore {
    override fun load() = CrashConsent.ALLOWED
    override fun save(consent: CrashConsent) = Unit
}

private class DetectorSink : CrashReportSink {
    var recorded = 0
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
    override fun sendUnsentReports() = Unit
    override fun deleteUnsentReports() = Unit
    override fun setCustomKeys(keys: Map<String, String>) = Unit
    override fun record(exception: Throwable) {
        recorded++
    }
}
