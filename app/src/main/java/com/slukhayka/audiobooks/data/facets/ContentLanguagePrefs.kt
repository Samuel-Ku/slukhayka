package com.slukhayka.audiobooks.data.facets

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec-45 (#405) T6 (#494) — the persisted Content Language preference: the
 * ONE source of truth every content surface reads (US6/US7). A listener
 * chooses which narration languages to show — «Українська» and/or «English»;
 * both on = «Усі» = every card (the shipped default). Unknown-language
 * (legacy `""`) rows are never hidden by any selection (US17), a rule the
 * consumers apply — this store only answers "which languages are on".
 *
 * SharedPreferences-backed and locally persisted, never synced. Writes are
 * synchronous and immediate; the [languages] flow carries every change so
 * the feed Pager, the union/search/new-arrival surfaces and the settings UI
 * all react live to one source. "Both off" is not a state: any write that
 * would empty the set normalizes back to the default (the UI keeps at least
 * one checkbox on, and the store enforces the invariant on reads too).
 */
class ContentLanguagePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("content_language_prefs", Context.MODE_PRIVATE)

    private val _languages = MutableStateFlow(read())
    val languages: StateFlow<Set<String>> = _languages.asStateFlow()

    /** Replaces the checked set (normalized); both-off writes become «Усі». */
    fun setLanguages(languages: Set<String>) {
        val normalized = normalize(languages)
        prefs.edit().putStringSet(KEY_LANGUAGES, normalized).apply()
        _languages.value = normalized
    }

    private fun read(): Set<String> {
        val stored = prefs.getStringSet(KEY_LANGUAGES, null)
        return normalize(stored?.toSet().orEmpty())
    }

    companion object {
        /** Every content language the app currently ships (BCP-47, normalized). */
        val KNOWN_CONTENT_LANGUAGES: Set<String> = setOf("uk", "en")

        /** Both on = «Усі» — the shipped default (US6). */
        val DEFAULT_LANGUAGES: Set<String> = setOf("uk", "en")

        private const val KEY_LANGUAGES = "content_languages"

        /** Intersects with the known languages; never empty (both off is not a state). */
        fun normalize(languages: Set<String>): Set<String> =
            (languages intersect KNOWN_CONTENT_LANGUAGES).ifEmpty { DEFAULT_LANGUAGES }
    }
}
