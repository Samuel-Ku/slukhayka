package com.slukhayka.audiobooks.ui.theme

import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 2026-2027 — the appearance choices are persisted facts, not a session
 * memory: a new store over the same context has to see what the previous one
 * wrote. Defaults are the written ones: auto theme, comfortable density and
 * the player's ink exception ON (decision A).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppearancePreferencesStoreTest {

    private val app: App = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults are auto appearance comfortable density and ink player`() {
        val store = AppearancePreferencesStore(app)
        assertEquals(AppThemeMode.AUTO, store.themeMode.value)
        assertEquals(ContentDensity.COMFORTABLE, store.density.value)
        assertEquals(true, store.playerAlwaysInk.value)
    }

    @Test
    fun `density survives a new store over the same context`() {
        AppearancePreferencesStore(app).setDensity(ContentDensity.COMPACT)

        val reopened = AppearancePreferencesStore(app)
        assertEquals(ContentDensity.COMPACT, reopened.density.value)
    }

    @Test
    fun `theme choice survives a new store over the same context`() {
        AppearancePreferencesStore(app).setThemeMode(AppThemeMode.INK)

        val reopened = AppearancePreferencesStore(app)
        assertEquals(AppThemeMode.INK, reopened.themeMode.value)
    }
}
