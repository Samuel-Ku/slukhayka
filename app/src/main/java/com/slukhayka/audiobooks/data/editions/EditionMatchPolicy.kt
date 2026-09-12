package com.slukhayka.audiobooks.data.editions

import com.slukhayka.audiobooks.data.LanguageCode

/**
 * #530 — the facts that decide whether a candidate Source serves the SAME
 * Edition: the Work identity, the narration's language and narrator, the
 * claimed duration and the chapter shape. Absent facts stay absent — they are
 * never guessed (ADR-0014).
 */
data class EditionMatchFacts(
    val workKey: String,
    val language: String = "",
    val narrator: String = "",
    val durationSeconds: Long? = null,
    val chapterCount: Int? = null
)

/** #530 — how a candidate relates to the Edition the listener already has. */
enum class EditionMatchVerdict {
    /**
     * The same narration: every fact is present AND compatible, so a switch
     * may continue the chapter/position without asking.
     */
    SAME_EDITION,

    /** A different narration of the same Work: offer it, never switch silently. */
    OTHER_EDITION,

    /** A different Work, or an identity too thin to relate at all. */
    INCOMPATIBLE
}

/**
 * #530 — the same-Edition auto-match rule, fail closed by construction. A
 * candidate is [EditionMatchVerdict.SAME_EDITION] only when the Work identity
 * matches AND language, normalized narrator, duration (within
 * [DURATION_TOLERANCE]) and chapter shape all agree. An unknown narrator, a
 * language mismatch, a duration conflict or incompatible chapters can never
 * auto-merge — they degrade to [EditionMatchVerdict.OTHER_EDITION], which the
 * listener must confirm (#519's action), or to
 * [EditionMatchVerdict.INCOMPATIBLE] when even the Work differs.
 */
object EditionMatchPolicy {

    /** The honest window: a re-encode may shift the total by about this much. */
    const val DURATION_TOLERANCE: Double = 0.02

    fun verdict(
        observed: EditionMatchFacts,
        candidate: EditionMatchFacts
    ): EditionMatchVerdict {
        if (observed.workKey.isBlank() || candidate.workKey.isBlank()) {
            return EditionMatchVerdict.INCOMPATIBLE
        }
        if (observed.workKey != candidate.workKey) {
            return EditionMatchVerdict.INCOMPATIBLE
        }
        return if (sameEdition(observed, candidate)) {
            EditionMatchVerdict.SAME_EDITION
        } else {
            EditionMatchVerdict.OTHER_EDITION
        }
    }

    /** The strict conjunction: every fact present and compatible. */
    fun sameEdition(observed: EditionMatchFacts, candidate: EditionMatchFacts): Boolean {
        if (!languagesCompatible(observed.language, candidate.language)) return false
        val observedNarrator = narratorKey(observed.narrator)
        val candidateNarrator = narratorKey(candidate.narrator)
        // An unknown narrator on EITHER side is never proof of the same voice.
        if (observedNarrator.isEmpty() || candidateNarrator.isEmpty()) return false
        if (observedNarrator != candidateNarrator) return false
        if (!durationCompatible(observed.durationSeconds, candidate.durationSeconds)) return false
        val observedChapters = observed.chapterCount
        val candidateChapters = candidate.chapterCount
        if (observedChapters == null || candidateChapters == null) return false
        return observedChapters == candidateChapters
    }

    /** Blank on either side is unknown, not a match — the caller decides. */
    fun languagesCompatible(observed: String, candidate: String): Boolean {
        val left = LanguageCode.normalize(observed).orEmpty()
        val right = LanguageCode.normalize(candidate).orEmpty()
        if (left.isBlank() || right.isBlank()) return false
        return left == right
    }

    /**
     * Both durations must be known and within [DURATION_TOLERANCE] of the
     * observed one. A missing duration can never confirm sameness.
     */
    fun durationCompatible(observed: Long?, candidate: Long?): Boolean {
        if (observed == null || candidate == null) return false
        if (observed <= 0L || candidate <= 0L) return false
        val delta = kotlin.math.abs(observed - candidate).toDouble()
        return delta <= observed.toDouble() * DURATION_TOLERANCE
    }

    /**
     * The narration-identity key: case/space/diacritic-insensitive, with the
     * boilerplate words that carry no voice identity removed.
     */
    fun narratorKey(narrator: String): String = narrator
        .lowercase()
        .replace('’', '\'')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() && it !in NARRATOR_NOISE }
        .joinToString(" ")
        .trim()

    private val NARRATOR_NOISE = setOf("читає", "начитав", "начитала", "читання", "озвучка", "голос")
}
