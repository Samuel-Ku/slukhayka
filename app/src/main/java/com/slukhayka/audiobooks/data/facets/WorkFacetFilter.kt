package com.slukhayka.audiobooks.data.facets

/** Frozen local query contract; dimensions compose with AND, values with OR. */
data class WorkFacetFilter(
    val genreIds: Set<String> = emptySet(),
    val durationBucketIds: Set<String> = emptySet(),
    val authorIds: Set<String> = emptySet()
) {
    init {
        require(genreIds.size <= MAX_VALUES_PER_DIMENSION)
        require(durationBucketIds.size <= MAX_VALUES_PER_DIMENSION)
        require(authorIds.size <= MAX_VALUES_PER_DIMENSION)
    }

    companion object {
        const val MAX_VALUES_PER_DIMENSION = 24
    }
}
