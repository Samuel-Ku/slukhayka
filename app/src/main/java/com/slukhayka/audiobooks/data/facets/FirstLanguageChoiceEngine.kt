package com.slukhayka.audiobooks.data.facets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec-51 (#742) T2 — the one-time First Language Choice: after the first
 * catalogue sync that wrote any rendition, the listener is asked once
 * «якими мовами хочеш книжки» over the languages that actually have content,
 * all of them on by default, with the quick actions «Лише українські» and
 * «Готово»/«Усі». Any answer — an explicit selection or a quick action — is
 * terminal: [ContentLanguagePrefs.markAnswered] survives restarts, so the
 * question never returns.
 *
 * The engine is the ONLY owner of the question's visibility state; it is pure
 * Kotlin over two injected seams ([ContentLanguagePrefs] and the content
 * probe) so the fires-once branches are JVM-testable without Room.
 * [evaluate] is called after every catalogue sync (idempotent): it shows the
 * question only when the listener has NOT already answered, has NOT already
 * narrowed the languages away from «Усі» (an active choice is an answer), and
 * the catalogue really holds at least one known rendition.
 *
 * It replaces the spec-45 bilingual prompt (uk/en only) with the wider
 * question the multilingual catalogue needs.
 */
class FirstLanguageChoiceEngine(
    private val prefs: ContentLanguagePrefs,
    private val knownContentLanguages: suspend () -> Collection<String>
) {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _languages = MutableStateFlow<List<String>>(emptyList())

    /**
     * The languages with actual content the question offers, in the repo
     * order (uk first, then alphabetical). Populated by [evaluate] — the
     * sheet renders exactly what the probe found, never a hardcoded list.
     */
    val languages: StateFlow<List<String>> = _languages.asStateFlow()

    /**
     * Re-evaluates after a catalogue sync. Suspend: probes the database.
     * Idempotent — repeated syncs never re-ask, and a running question is
     * never dismissed by a concurrent sync.
     */
    suspend fun evaluate() {
        if (_visible.value) return
        if (prefs.answerRecorded) return
        // «Усі» is the empty set; anything narrower is a choice already made —
        // record it as the terminal answer exactly once, so a later reset back
        // to «Усі» can never re-ask (US15/US16).
        if (!prefs.isAll) {
            prefs.markAnswered()
            return
        }
        val known = orderContentLanguages(
            knownContentLanguages().filter { it in ContentLanguagePrefs.KNOWN_CONTENT_LANGUAGES }
        )
        // Nothing catalogued yet: ask after the sync that actually finds books.
        if (known.isEmpty()) return
        _languages.value = known
        _visible.value = true
    }

    /** «Готово» with everything on (or «Усі») — keeps every language. */
    fun keepAll() = answer { prefs.setLanguages(emptySet()) }

    /** «Лише українські» — narrows the content languages to uk-only. */
    fun ukrainianOnly() = answer { prefs.setLanguages(setOf("uk")) }

    /** An explicit checkbox selection from the sheet. */
    fun apply(languages: Set<String>) = answer { prefs.setLanguages(languages) }

    private inline fun answer(write: () -> Unit) {
        write()
        prefs.markAnswered()
        _visible.value = false
    }
}
