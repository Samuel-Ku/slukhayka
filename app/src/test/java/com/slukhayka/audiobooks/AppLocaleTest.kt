package com.slukhayka.audiobooks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 T9 (#497) — the App Locale setting: a concrete locale pins the
 * Activity context (app + library resources speak one language, even on a
 * foreign-language device), SYSTEM follows the device, and the preference
 * round-trips through its store with the shipped default (uk).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "pl")
class AppLocaleTest {

    private val deviceContext: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        deviceContext.getSharedPreferences("app_locale_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun ukrainianLocalePinsUkrainianEvenOnAPolishDevice() {
        assertEquals("pl", deviceContext.resources.configuration.locales[0].language)

        val appUiContext = deviceContext.withAppLocale(AppLocale.UKRAINIAN)

        assertEquals("uk", appUiContext.resources.configuration.locales[0].language)
    }

    @Test
    fun englishLocalePinsEnglish() {
        val appUiContext = deviceContext.withAppLocale(AppLocale.ENGLISH)

        assertEquals("en", appUiContext.resources.configuration.locales[0].language)
    }

    @Test
    fun systemLocaleFollowsTheDevice() {
        val appUiContext = deviceContext.withAppLocale(AppLocale.SYSTEM)

        assertEquals("pl", appUiContext.resources.configuration.locales[0].language)
    }

    @Test
    fun storeDefaultsToUkrainianAndRoundTrips() {
        val prefs = AppLocalePrefs(deviceContext)

        assertEquals(AppLocale.UKRAINIAN, prefs.locale)

        prefs.locale = AppLocale.ENGLISH
        assertEquals(AppLocale.ENGLISH, AppLocalePrefs(deviceContext).locale)

        prefs.locale = AppLocale.SYSTEM
        assertEquals(AppLocale.SYSTEM, AppLocalePrefs(deviceContext).locale)
    }

    @Test
    fun unknownStoredValueFallsBackToUkrainian() {
        deviceContext.getSharedPreferences("app_locale_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_locale", "klingon").commit()

        assertEquals(AppLocale.UKRAINIAN, AppLocalePrefs(deviceContext).locale)
    }
}
