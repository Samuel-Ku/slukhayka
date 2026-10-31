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

    private val appContext = context.applicationContext
    // #1723 — the preferences object is created lazily: this store is
    // constructed while the ViewModel is being built on the main thread, and
    // getSharedPreferences() is a disk read on first touch. StartupReads
    // loads the persisted value on IO and [seed] publishes it; grant/withdraw
    // touch the file only on an explicit listener action.
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _published = MutableStateFlow(false)
    private val seedLock = Any()
    private var mutated = false

    /** True once the listener has confirmed publishing their showcase. */
    val published: StateFlow<Boolean> = _published.asStateFlow()

    fun isPublished(): Boolean = _published.value

    /**
     * Publishes the value the startup batch already read (#1723). It never
     * overwrites a [grant]/[withdraw] that raced it: the listener's explicit
     * action is the newer fact.
     */
    fun seed(published: Boolean) {
        synchronized(seedLock) {
            if (!mutated) _published.value = published
        }
    }

    /** The listener confirmed. Recorded only AFTER the write succeeded. */
    fun grant() {
        synchronized(seedLock) {
            prefs.edit().putBoolean(KEY_PUBLISHED, true).apply()
            mutated = true
            _published.value = true
        }
    }

    /**
     * The listener took the showcase down, or there is nothing left to publish.
     * After this, changing the pins sends nothing until they confirm again.
     */
    fun withdraw() {
        synchronized(seedLock) {
            prefs.edit().putBoolean(KEY_PUBLISHED, false).apply()
            mutated = true
            _published.value = false
        }
    }

    companion object {
        private const val PREFS_NAME = "showcase_consent_prefs"
        private const val KEY_PUBLISHED = "showcase_published"

        /**
         * The startup read (#1723), on whatever thread the caller provides.
         * [StartupReads] is the IO caller; keeping the key names here means
         * the store owns its storage format.
         */
        internal fun readPublished(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PUBLISHED, false)
    }
}
