package com.slukhayka.audiobooks.data.listening

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-0023 (spec-43 T6) — the visible Progress Sync switch (⚙️ Профіль):
 * on by default, because the sync carries nothing until the listener's own
 * devices share one profile — and the switch turns it off for good, keeping
 * the local Listening State untouched.
 */
class ProgressSyncSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val FILE = "progress_sync_settings"
        const val KEY_ENABLED = "progress_sync_enabled"
    }
}
