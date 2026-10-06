package com.slukhayka.audiobooks.data.achievements

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * #705 (T7) — whether the listener chose to make their showcase public.
 *
 * Pinning an award is a LOCAL act: it changes what the listener sees on their
 * own screen and nothing else. Publishing the showcase is a separate, explicit
 * act, and this remembers that it happened — which is what the ticket's third
 * criterion needs («зміна вітрини оновлює профіль»). Without it, a later pin
 * would have to either ask again every time or silently re-publish something
 * the listener had not confirmed a second time.
 *
 * Local and never synced, exactly like the pins themselves: it records a
 * decision, it is not a second copy of the showcase.
 *
 * SharedPreferences-backed with synchronous writes, following
 * [com.slukhayka.audiobooks.data.availability.LibraryAvailabilityStore].
 */
class ShowcaseConsentStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("showcase_consent_prefs", Context.MODE_PRIVATE)

    private val _published = MutableStateFlow(prefs.getBoolean(KEY_PUBLISHED, false))

    /** True once the listener has confirmed publishing their showcase. */
    val published: StateFlow<Boolean> = _published.asStateFlow()

    fun isPublished(): Boolean = _published.value

    /** The listener confirmed. Recorded only AFTER the write succeeded. */
    fun grant() {
        prefs.edit().putBoolean(KEY_PUBLISHED, true).apply()
        _published.value = true
    }

    /**
     * The listener took the showcase down, or there is nothing left to publish.
     * After this, changing the pins sends nothing until they confirm again.
     */
    fun withdraw() {
        prefs.edit().putBoolean(KEY_PUBLISHED, false).apply()
        _published.value = false
    }

    private companion object {
        const val KEY_PUBLISHED = "showcase_published"
    }
}
