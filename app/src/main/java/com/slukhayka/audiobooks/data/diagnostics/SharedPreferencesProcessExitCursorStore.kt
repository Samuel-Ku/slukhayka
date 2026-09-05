package com.slukhayka.audiobooks.data.diagnostics

import android.content.Context

/** Local-only watermark. It is intentionally separate from backed-up settings. */
class SharedPreferencesProcessExitCursorStore(context: Context) : ProcessExitCursorStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): ProcessExitCursor? {
        if (!prefs.contains(KEY_TIMESTAMP) || !prefs.contains(KEY_HASH)) return null
        return ProcessExitCursor(
            timestampMillis = prefs.getLong(KEY_TIMESTAMP, 0L),
            stableHash = prefs.getString(KEY_HASH, null) ?: return null
        )
    }

    override fun save(cursor: ProcessExitCursor) {
        prefs.edit()
            .putLong(KEY_TIMESTAMP, cursor.timestampMillis)
            .putString(KEY_HASH, cursor.stableHash)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "crash_exit_cursor"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_HASH = "stable_hash"
    }
}
