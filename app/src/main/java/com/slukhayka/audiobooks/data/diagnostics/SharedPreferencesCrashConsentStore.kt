package com.slukhayka.audiobooks.data.diagnostics

import android.content.Context

/** Persists only the listener's explicit crash-reporting choice. */
class SharedPreferencesCrashConsentStore(context: Context) : CrashConsentStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): CrashConsent = prefs.getString(KEY_CONSENT, null)
        ?.let { stored -> CrashConsent.entries.firstOrNull { it.name == stored } }
        ?: CrashConsent.UNDECIDED

    override fun save(consent: CrashConsent) {
        prefs.edit().putString(KEY_CONSENT, consent.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "crash_reporting_consent"
        const val KEY_CONSENT = "consent"
    }
}
