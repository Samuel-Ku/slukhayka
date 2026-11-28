package com.slukhayka.audiobooks.data.notifications

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-0052 §1–5 — the listener's app-level control over the two EVENT
 * notification families: «Поява джерела» ([SourceWatchNotifier]) and
 * «Новинки від людей» ([PeopleNewArrivalWorker]).
 *
 * Two booleans, local only, never synced (ADR-0052: the state is personal and
 * rides with the feature's own opt-in). Both default to `true`: the features
 * are already opt-in — a Work was put on the watch, a person was bookmarked
 * with notifications on — so the global switch only adds a way to say "less
 * noise", not a second consent.
 *
 * Muting posts, not work (ADR-0052 §3): this store is read at the POSTING
 * seam only. Watches, bookmarks, seen-state and the in-app appearance remain
 * untouched, so the Work page still shows that the source arrived.
 *
 * SharedPreferences — the same tiny-prefs idiom as
 * [com.slukhayka.audiobooks.data.listening.ProgressSyncSettingsStore] and
 * [com.slukhayka.audiobooks.ui.theme.AppearancePreferencesStore]; the flows
 * carry every change to the settings screen and the background posters.
 */
class NotificationPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _sourceAppearanceEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_SOURCE_APPEARANCE, true))

    /** Whether «Поява джерела» notifications may be posted. */
    val sourceAppearanceEnabled: StateFlow<Boolean> = _sourceAppearanceEnabled.asStateFlow()

    private val _peopleNewArrivalsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_PEOPLE_NEW_ARRIVALS, true))

    /** Whether «Новинки від людей» notifications may be posted. */
    val peopleNewArrivalsEnabled: StateFlow<Boolean> = _peopleNewArrivalsEnabled.asStateFlow()

    fun setSourceAppearanceEnabled(enabled: Boolean) {
        if (_sourceAppearanceEnabled.value == enabled) return
        prefs.edit { putBoolean(KEY_SOURCE_APPEARANCE, enabled) }
        _sourceAppearanceEnabled.value = enabled
    }

    fun setPeopleNewArrivalsEnabled(enabled: Boolean) {
        if (_peopleNewArrivalsEnabled.value == enabled) return
        prefs.edit { putBoolean(KEY_PEOPLE_NEW_ARRIVALS, enabled) }
        _peopleNewArrivalsEnabled.value = enabled
    }

    private companion object {
        const val FILE = "notification_preferences"
        const val KEY_SOURCE_APPEARANCE = "source_appearance_enabled"
        const val KEY_PEOPLE_NEW_ARRIVALS = "people_new_arrivals_enabled"
    }
}
