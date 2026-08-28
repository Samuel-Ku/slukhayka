package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.diagnostics.CrashConsent
import com.slukhayka.audiobooks.data.diagnostics.CrashConsentStore
import com.slukhayka.audiobooks.data.diagnostics.CrashReportSink
import com.slukhayka.audiobooks.data.diagnostics.CrashReporting
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashReportsSettingsRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `listener can reverse the stored choice`() {
        val store = SettingsConsentStore()
        val reporting = CrashReporting(store, SettingsSink(), enabledForBuild = true)
        reporting.start()
        compose.setContent {
            AudiobookTheme { CrashReportsSettingsRow(reporting) }
        }

        compose.onNodeWithTag("profile_crash_reports_switch").assertIsOff().performClick()
        compose.onNodeWithTag("profile_crash_reports_switch").assertIsOn().performClick()
        compose.onNodeWithTag("profile_crash_reports_switch").assertIsOff()
        assertEquals(CrashConsent.DENIED, store.consent)
    }
}

private class SettingsConsentStore : CrashConsentStore {
    var consent = CrashConsent.DENIED
    override fun load() = consent
    override fun save(consent: CrashConsent) {
        this.consent = consent
    }
}

private class SettingsSink : CrashReportSink {
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
    override fun sendUnsentReports() = Unit
    override fun deleteUnsentReports() = Unit
    override fun setCustomKeys(keys: Map<String, String>) = Unit
    override fun record(exception: Throwable) = Unit
}
