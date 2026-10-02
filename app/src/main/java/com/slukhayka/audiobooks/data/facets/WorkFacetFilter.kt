package com.slukhayka.audiobooks.data.facets

/** Frozen local query contract; dimensions compose with AND, values with OR. */
data class WorkFacetFilter(
    val genreIds: Set<String> = emptySet(),
    val durationBucketIds: Set<String> = emptySet(),
    val authorIds: Set<String> = emptySet(),
    /**
     * Spec-45 (#405) T4 (#492) — BCP-47 content languages. A Work matches
     * when ANY of its Edition signals speaks a selected language, and a
     * Work with NO language signal (or an unknown `""` one) is never hidden
     * (US17). An EMPTY selection is inactive — everything shows (the "both
     * content languages on" state maps to this at the preference layer).
     */
    val languages: Set<String> = emptySet(),
    /**
     * #831 AC3 — «Лише зі спільної бібліотеки»: keep only Works whose audio
     * came from the registered community group. A BOOLEAN, not a set: the
     * ticket asks for one narrowing, and a second dimension of provenance
     * values would invent a taxonomy the sources do not state (ADR-0014).
     *
     * `false` (the default) is INACTIVE — everything shows, matching the
     * language dimension's contract, so "off" never means "hide foreign
     * entries".
     */
    val sharedLibraryOnly: Boolean = false
) {
    init {
        require(genreIds.size <= MAX_VALUES_PER_DIMENSION)
        require(durationBucketIds.size <= MAX_VALUES_PER_DIMENSION)
        require(authorIds.size <= MAX_VALUES_PER_DIMENSION)
        require(languages.size <= MAX_VALUES_PER_DIMENSION)
    }

    companion object {
        const val MAX_VALUES_PER_DIMENSION = 24
    }
}
