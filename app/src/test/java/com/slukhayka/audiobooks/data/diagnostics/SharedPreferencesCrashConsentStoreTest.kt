package com.slukhayka.audiobooks.data.diagnostics

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPreferencesCrashConsentStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearStoredChoice() {
        context.getSharedPreferences("crash_reporting_consent", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `new install is undecided and each explicit choice survives restart`() {
        val first = SharedPreferencesCrashConsentStore(context)
        assertEquals(CrashConsent.UNDECIDED, first.load())

        first.save(CrashConsent.DENIED)
        assertEquals(CrashConsent.DENIED, SharedPreferencesCrashConsentStore(context).load())

        first.save(CrashConsent.ALLOWED)
        assertEquals(CrashConsent.ALLOWED, SharedPreferencesCrashConsentStore(context).load())
    }
}
