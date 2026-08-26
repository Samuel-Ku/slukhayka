package com.slukhayka.audiobooks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "pl")
class UkrainianUiLocaleTest {

    @Test
    fun appUiContextUsesUkrainianEvenWhenTheDeviceUsesPolish() {
        val deviceContext = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("pl", deviceContext.resources.configuration.locales[0].language)

        val appUiContext = deviceContext.withUkrainianUiLocale()

        assertEquals("uk", appUiContext.resources.configuration.locales[0].language)
    }
}
