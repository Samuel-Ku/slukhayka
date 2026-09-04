package com.slukhayka.audiobooks.data.facets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec-45 (#405) T8 (#496) — the one-time bilingual prompt (US9): after the
 * first catalogue sync that actually wrote an English rendition, the listener
 * is asked once «Залишити чи сховати?». «Залишити» keeps both languages on;
 * «Лише українські» writes the preference to uk-only. Either answer is
 * terminal — [ContentLanguagePrefs.promptAnswered] survives restarts, so the
 * prompt never returns (fires at most once ever).
 *
 * The engine is the ONLY owner of the prompt's visibility state; it is pure
 * Kotlin over two injected seams ([ContentLanguagePrefs] and the English
 * probe) so the fires-once branches are JVM-testable without Room. [evaluate]
 * is called after every catalogue sync (idempotent): it shows the prompt only
 * when the listener has NOT already answered, has NOT already narrowed the
 * languages away from «Усі» in ⚙️ (an active choice is an answer), and the
 * catalogue really holds an `en` Edition.
 */
class BilingualPromptEngine(
    private val prefs: ContentLanguagePrefs,
    private val hasEnglishEditions: suspend () -> Boolean
) {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /**
     * Re-evaluates after a catalogue sync. Suspend: probes the database.
     * Idempotent — repeated syncs never re-ask, and a running prompt is never
     * dismissed by a concurrent sync.
     */
    suspend fun evaluate() {
        if (_visible.value) return
        if (prefs.promptAnswered) return
        // An active language choice in ⚙️ (anything but the «Усі» default)
        // already answers the question — never ask over it.
        if (prefs.languages.value != ContentLanguagePrefs.DEFAULT_LANGUAGES) return
        if (!hasEnglishEditions()) return
        _visible.value = true
    }

    /** «Залишити» — keeps both languages on; dismissed permanently. */
    fun keepEnglish() = answer()

    /** «Лише українські» — narrows the content languages to uk-only. */
    fun ukrainianOnly() {
        prefs.setLanguages(setOf("uk"))
        answer()
    }

    private fun answer() {
        prefs.markPromptAnswered()
        _visible.value = false
    }
}
