package com.slukhayka.audiobooks.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plan 2026-2027 — the theme choice the direction asks for: «Авто / Папір /
 * Чорнило».
 *
 * AUTO follows the system. PAPER pins the light scheme, INK the dark one; on a
 * migrated screen those become the paper and ink palettes themselves, because
 * [PaperInkThemedContent] reads the outer background, while un-migrated screens
 * still get the legacy light/dark scheme. That is the honest behaviour of a
 * half-migrated app: one switch, both worlds follow it.
 */
enum class AppThemeMode { AUTO, PAPER, INK }

/**
 * The direction's second appearance choice: «Зручно» (the measured defaults)
 * or «Компактно» (denser rows and posters for a listener who scans more).
 * It is a LAYOUT preference, not a scale: tap targets stay 48 dp, only the
 * vertical rhythm and cover sizes shrink.
 */
enum class ContentDensity { COMFORTABLE, COMPACT }

/**
 * Small persisted appearance state (SharedPreferences — the same idiom as the
 * other lightweight stores; no new DataStore dependency for two booleans and an
 * enum).
 *
 * `playerAlwaysInk` is the listener's answer to decision A: the player is ink
 * by default, and the switch exists so that a light-app listener can keep it
 * dark (or turn the exception off) without losing the direction.
 */
class AppearancePreferencesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { AppThemeMode.valueOf(prefs.getString(KEY_THEME, null).orEmpty()) }
            .getOrDefault(AppThemeMode.AUTO)
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _playerAlwaysInk = MutableStateFlow(prefs.getBoolean(KEY_PLAYER_INK, true))
    val playerAlwaysInk: StateFlow<Boolean> = _playerAlwaysInk.asStateFlow()

    private val _density = MutableStateFlow(
        runCatching { ContentDensity.valueOf(prefs.getString(KEY_DENSITY, null).orEmpty()) }
            .getOrDefault(ContentDensity.COMFORTABLE)
    )
    val density: StateFlow<ContentDensity> = _density.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setPlayerAlwaysInk(alwaysInk: Boolean) {
        if (_playerAlwaysInk.value == alwaysInk) return
        _playerAlwaysInk.value = alwaysInk
        prefs.edit().putBoolean(KEY_PLAYER_INK, alwaysInk).apply()
    }

    fun setDensity(density: ContentDensity) {
        if (_density.value == density) return
        _density.value = density
        prefs.edit().putString(KEY_DENSITY, density.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "appearance"
        const val KEY_THEME = "theme_mode"
        const val KEY_PLAYER_INK = "player_always_ink"
        const val KEY_DENSITY = "content_density"
    }
}
