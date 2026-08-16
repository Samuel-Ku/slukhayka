package com.slukhayka.audiobooks.player

import android.content.Context

/**
 * App-level playback preferences (wayfinder #26): the global default playback
 * speed, applied to books that have no per-book speed saved. Backed by
 * [android.content.SharedPreferences] so the choice survives restarts.
 */
class PlaybackSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_DEFAULT_SPEED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "playback_settings"
        private const val KEY_DEFAULT_SPEED = "default_speed"
    }
}
