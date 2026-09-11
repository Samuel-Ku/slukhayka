package com.slukhayka.audiobooks.data.facets

import android.content.Context
import android.content.SharedPreferences
import com.slukhayka.audiobooks.data.LanguageCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec-45 (#405) T6 (#494), spec-51 (#742) T1 — the persisted Content
 * Language preference: the ONE source of truth every content surface reads.
 * A listener chooses which narration languages to show; «Усі» is the shipped
 * default and is carried as the EMPTY set, exactly like the web client's
 * «empty = all» (the filter already treats an empty selection as inactive).
 * Unknown-language (legacy `""`) rows are never hidden by any selection
 * (US17), a rule the consumers apply — this store only answers "which
 * languages are on".
 *
 * Spec-51 widened the vocabulary from two languages to the source's real one
 * ([LanguageCode.vocabulary]): a write of a code the normalizer cannot
 * produce is not a filter state, so it is dropped rather than stored.
 *
 * SharedPreferences-backed and locally persisted, never synced. Writes are
 * synchronous and immediate; the [languages] flow carries every change so the
 * feed Pager, the union/search/new-arrival surfaces and the settings UI all
 * react live to one source.
 */
class ContentLanguagePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("content_language_prefs", Context.MODE_PRIVATE)

    private val _languages = MutableStateFlow(read())
    val languages: StateFlow<Set<String>> = _languages.asStateFlow()

    /** True when the listener shows every language («Усі») — the default. */
    val isAll: Boolean get() = _languages.value.isEmpty()

    /** Replaces the checked set (normalized); an empty write means «Усі». */
    fun setLanguages(languages: Set<String>) {
        val normalized = normalize(languages)
        prefs.edit().putStringSet(KEY_LANGUAGES, normalized).apply()
        _languages.value = normalized
    }

    /**
     * Spec-51 (#742) T2: whether the one-time First Language Choice was
     * answered (either branch). Persisted, so the question never returns
     * across restarts once the listener chose. The KEY keeps its spec-45
     * name: renaming it would reset every existing answer and re-ask.
     */
    val answerRecorded: Boolean
        get() = prefs.getBoolean(KEY_CHOICE_ANSWERED, false)

    /** Records that the listener answered the First Language Choice. */
    fun markAnswered() {
        prefs.edit().putBoolean(KEY_CHOICE_ANSWERED, true).apply()
    }

    private fun read(): Set<String> {
        migrateLegacyDefault()
        return normalize(prefs.getStringSet(KEY_LANGUAGES, null)?.toSet().orEmpty())
    }

    /**
     * Spec-51 (#742) T5 — the one-time migration. A stored selection of
     * exactly {uk, en} is the OLD default: «Залишити», the only «Усі» the app
     * had when those were the only two languages, and a question answered
     * before the wider catalogue existed. It widens to «Усі» once, so the
     * newly admitted languages cannot hide behind a choice nobody made. A
     * selection the listener narrowed themselves (e.g. {uk}) is never
     * touched, and the marker makes this fire exactly once — a later,
     * deliberate uk+en selection stays exactly that.
     */
    private fun migrateLegacyDefault() {
        if (prefs.getBoolean(KEY_LEGACY_DEFAULT_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_LEGACY_DEFAULT_MIGRATED, true).apply()
        val stored = prefs.getStringSet(KEY_LANGUAGES, null)?.toSet()
        if (stored == LEGACY_DEFAULT_LANGUAGES) {
            prefs.edit().putStringSet(KEY_LANGUAGES, emptySet()).apply()
        }
    }

    companion object {
        /**
         * Every content language the app can filter on: the ONE normalizer's
         * vocabulary (spec-51 replaced the hardcoded uk/en pair, which would
         * have silently dropped every wider language a write carried).
         */
        val KNOWN_CONTENT_LANGUAGES: Set<String> = LanguageCode.vocabulary

        /** The pre-spec-51 «Усі» — the only value the migration widens. */
        val LEGACY_DEFAULT_LANGUAGES: Set<String> = setOf("uk", "en")

        private const val KEY_LANGUAGES = "content_languages"

        private const val KEY_CHOICE_ANSWERED = "bilingual_prompt_answered"

        private const val KEY_LEGACY_DEFAULT_MIGRATED = "legacy_uk_en_default_migrated"

        /** Intersects with the vocabulary; empty = «Усі» is a real state. */
        fun normalize(languages: Set<String>): Set<String> =
            languages intersect KNOWN_CONTENT_LANGUAGES
    }
}
