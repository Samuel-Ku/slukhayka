package com.slukhayka.audiobooks.data.source

import android.content.Context
import android.content.SharedPreferences

/**
 * ADR-0039 / spec #681 T1 (#682) — the persistent carrier of the per-domain
 * budget, beside the existing small-state stores (the `PrivacySettingsStore`
 * precedent). One string per host keeps the write tiny and the format
 * human-readable in `adb shell run-as` debugging; a new process — or a
 * restored backup — reads the same tokens and refill anchor, so politeness
 * never resets with a restart.
 */
class SharedPreferencesSourceGateBudgetStore(context: Context) : SourceGateBudgetStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(host: String): SourceBucketState? {
        val raw = prefs.getString(key(host), null) ?: return null
        val separator = raw.indexOf(SEPARATOR)
        if (separator <= 0) return null
        val tokens = raw.substring(0, separator).toIntOrNull() ?: return null
        val lastRefillAtMs = raw.substring(separator + 1).toLongOrNull() ?: return null
        return SourceBucketState(tokens, lastRefillAtMs)
    }

    override fun save(host: String, state: SourceBucketState) {
        prefs.edit()
            .putString(key(host), "${state.tokens}$SEPARATOR${state.lastRefillAtMs}")
            .apply()
    }

    private fun key(host: String): String = "$PREFIX$host"

    private companion object {
        const val PREFS_NAME = "source_gate_budget"
        const val PREFIX = "bucket."
        const val SEPARATOR = '|'
    }
}
