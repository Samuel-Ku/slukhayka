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

    override fun markFailurePromptPending() {
        prefs.edit().putBoolean(KEY_FAILURE_PROMPT_PENDING, true).apply()
    }

    override fun consumeFailurePromptPending(): Boolean {
        val pending = prefs.getBoolean(KEY_FAILURE_PROMPT_PENDING, false)
        if (pending) prefs.edit().remove(KEY_FAILURE_PROMPT_PENDING).apply()
        return pending
    }

    private companion object {
        const val PREFS_NAME = "crash_reporting_consent"
        const val KEY_CONSENT = "consent"
        const val KEY_FAILURE_PROMPT_PENDING = "failure_prompt_pending"
    }
}
