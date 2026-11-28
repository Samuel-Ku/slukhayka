package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.diagnostics.CrashConsent
import com.slukhayka.audiobooks.data.diagnostics.CrashConsentStore
import com.slukhayka.audiobooks.data.diagnostics.CrashReportSink
import com.slukhayka.audiobooks.data.diagnostics.CrashReporting
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1774 — Приватність kept its intro, both address-field labels/formats and the
 * route-failure note as Ukrainian literals. Switching the relay route on
 * renders the address-field pair too, so the EN and PL walks see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class NetworkPrivacyScreenEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<App>()

    private fun setScreen() {
        val viewModel = MainViewModel(app)
        val crashReporting = CrashReporting(QuietConsentStore(), QuietSink(), enabledForBuild = true)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(1200.dp)) {
                    NetworkPrivacyScreen(
                        viewModel = viewModel,
                        crashReporting = crashReporting,
                        onBackClick = {}
                    )
                }
            }
        }
    }

    @Test
    fun privacyChromeSpeaksEnglish() {
        setScreen()
        composeTestRule.onNodeWithTag("privacy_route_relay").performClick()

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "network privacy")
        composeTestRule.onNodeWithText("Relay address").assertExists()
        composeTestRule.onNodeWithText("Format: a full https:// link to the relay worker")
            .assertExists()
        composeTestRule.onNodeWithText("Direct connection").assertExists()
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun privacyChromeSpeaksPolish() {
        setScreen()
        composeTestRule.onNodeWithTag("privacy_route_relay").performClick()

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "network privacy (pl)")
        composeTestRule.onNodeWithText("Adres przekaźnika").assertExists()
        composeTestRule.onNodeWithText("Format: pełny link https:// do workera przekaźnika")
            .assertExists()
        composeTestRule.onNodeWithText("Połączenie bezpośrednie").assertExists()
    }
}

private class QuietConsentStore : CrashConsentStore {
    override fun load() = CrashConsent.DENIED
    override fun save(consent: CrashConsent) = Unit
}

private class QuietSink : CrashReportSink {
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
    override fun sendUnsentReports() = Unit
    override fun deleteUnsentReports() = Unit
}
