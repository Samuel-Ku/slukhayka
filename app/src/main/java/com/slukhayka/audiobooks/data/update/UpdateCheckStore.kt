package com.slukhayka.audiobooks.data.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the update-check bookkeeping lives (spec-36 #244 + #245): the
 * timestamp of the last SUCCESSFUL check — failures never anchor the daily
 * window, so a launch with no network retries on the next one — and the
 * release version the listener dismissed («Приховати»), which keeps the
 * banner hidden until a STRICTLY newer release appears.
 */
interface UpdateCheckStore {

    /** When the last successful releases/latest check completed, in ms. */
    var lastCheckAtMillis: Long

    /** The release version the listener dismissed, or null when none. */
    var dismissedVersionName: String?
}

/**
 * SharedPreferences-backed [UpdateCheckStore] — the same tiny-prefs pattern
 * as the other non-Room stores (ListenPrefsStore, ImportGrantStore).
 * Synchronous reads, apply() writes; a process death loses nothing.
 */
class SharedPreferencesUpdateCheckStore(context: Context) : UpdateCheckStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastCheckAtMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()

    override var dismissedVersionName: String?
        get() = prefs.getString(KEY_DISMISSED_VERSION, null)
        set(value) = prefs.edit().putString(KEY_DISMISSED_VERSION, value).apply()

    private companion object {
        const val PREFS_NAME = "update_check_prefs"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_DISMISSED_VERSION = "dismissed_version_name"
    }
}
