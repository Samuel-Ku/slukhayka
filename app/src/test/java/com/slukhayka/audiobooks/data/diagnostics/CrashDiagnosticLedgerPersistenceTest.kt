package com.slukhayka.audiobooks.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class BareDiagnosticLedgerApp : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = BareDiagnosticLedgerApp::class)
class CrashDiagnosticLedgerPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("crash_diagnostic_ledger", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `new ledger instance prevents the same exit from being recorded again`() {
        CrashDiagnosticLedger(context).apply {
            setPlaybackActive(true)
            markExitInspected(42L)
        }
        val recreatedLedger = CrashDiagnosticLedger(context)
        val sink = PersistenceSink()
        val reporting = CrashReporting(PersistenceConsentStore(), sink, enabledForBuild = true)
        reporting.start()

        UnexpectedPlaybackExitDetector(
            ProcessExitHistory { HistoricalProcessExit(42L, ExitReason.LOW_MEMORY) },
            recreatedLedger
        ).inspect(reporting)

        assertEquals(0, sink.recorded)
        assertEquals(42L, recreatedLedger.lastInspectedExitTimestamp())
    }
}

private class PersistenceConsentStore : CrashConsentStore {
    override fun load() = CrashConsent.ALLOWED
    override fun save(consent: CrashConsent) = Unit
}

private class PersistenceSink : CrashReportSink {
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
