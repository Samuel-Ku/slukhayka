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

private class BareCrashConsentApp : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = BareCrashConsentApp::class)
class SharedPreferencesCrashConsentStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("crash_reporting_consent", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `choice survives a new store instance`() {
        assertEquals(CrashConsent.UNDECIDED, SharedPreferencesCrashConsentStore(context).load())

        SharedPreferencesCrashConsentStore(context).save(CrashConsent.DENIED)

        assertEquals(CrashConsent.DENIED, SharedPreferencesCrashConsentStore(context).load())
    }
}
