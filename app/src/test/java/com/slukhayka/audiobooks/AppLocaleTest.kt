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
 * Spec-45 T9 (#497) + R7 (#514) — the App Locale setting: a concrete locale
 * pins the Activity context (app + library resources speak one language,
 * even on a foreign-language device), SYSTEM follows the device, and the
 * preference round-trips through its store. R7 changed the shipped DEFAULT
 * to SYSTEM (a fresh install follows the device — an English-system device
 * speaks English per US15) while an EXPLICITLY stored choice from any
 * earlier store version is preserved; the R7 review fixed the test that
 * wrongly pinned the old Ukrainian default.
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
    fun storeDefaultsToSystemAndRoundTrips() {
        val prefs = AppLocalePrefs(deviceContext)

        // R7 (#514): a fresh install follows the system — never a pinned
        // default (the old test wrongly pinned Ukrainian).
        assertEquals(AppLocale.SYSTEM, prefs.locale)

        prefs.locale = AppLocale.ENGLISH
        assertEquals(AppLocale.ENGLISH, AppLocalePrefs(deviceContext).locale)

        prefs.locale = AppLocale.UKRAINIAN
        assertEquals(AppLocale.UKRAINIAN, AppLocalePrefs(deviceContext).locale)

        prefs.locale = AppLocale.SYSTEM
        assertEquals(AppLocale.SYSTEM, AppLocalePrefs(deviceContext).locale)
    }

    @Test
    fun anExplicitlyStoredChoiceSurvivesTheSystemDefaultMigration() {
        // A T9-era store may hold an explicit "UKRAINIAN"; the R7 default
        // change must never reinterpret a saved selection as absent.
        deviceContext.getSharedPreferences("app_locale_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_locale", "UKRAINIAN").commit()

        assertEquals(AppLocale.UKRAINIAN, AppLocalePrefs(deviceContext).locale)
    }

    @Test
    fun unknownStoredValueFallsBackToSystem() {
        deviceContext.getSharedPreferences("app_locale_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_locale", "klingon").commit()

        assertEquals(AppLocale.SYSTEM, AppLocalePrefs(deviceContext).locale)
    }

    @Test
    fun theApplierPersistsTheChoiceBeforeApplyingIt() {
        // Robolectric runs sdk 34 (>= 33): the applier takes the LocaleManager
        // branch (best-effort if the platform service is unavailable) after
        // persisting the choice. The persisted value is the observable
        // contract — the next Activity creation reads it in attachBaseContext.
        val activity = org.robolectric.Robolectric
            .buildActivity(androidx.fragment.app.FragmentActivity::class.java)
            .setup().get()

        AppLocaleApplier.apply(activity, AppLocale.ENGLISH)
        assertEquals(AppLocale.ENGLISH, AppLocalePrefs(deviceContext).locale)

        AppLocaleApplier.apply(activity, AppLocale.SYSTEM)
        assertEquals(AppLocale.SYSTEM, AppLocalePrefs(deviceContext).locale)
    }
}
