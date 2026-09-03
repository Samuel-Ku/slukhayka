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
    val languages: Set<String> = emptySet()
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
