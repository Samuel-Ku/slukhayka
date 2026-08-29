package com.slukhayka.audiobooks.data.metadata

/** Honest bounded summary rendered on one bibliographic Work card. */
sealed interface EditionDurationSummary {
    data class Single(val seconds: Long) : EditionDurationSummary
    data class Range(val shortestSeconds: Long, val longestSeconds: Long) : EditionDurationSummary
}

/**
 * One duration policy shared by local facet writes, queries and UI labels.
 * Duration remains Edition-owned; this object only derives a read-model bucket
 * and a bounded Work-card summary from known Edition facts.
 */
object EditionDurationPolicy {
    private const val FIVE_HOURS_SECONDS = 5L * 60 * 60
    private const val TEN_HOURS_SECONDS = 10L * 60 * 60
    private const val TWENTY_HOURS_SECONDS = 20L * 60 * 60
    private const val ROUNDING_SECONDS = 60L

    val buckets: List<FacetDurationBucket> = FacetDurationBucket.entries

    internal fun secondsRangeFor(bucket: FacetDurationBucket): LongRange = when (bucket) {
        FacetDurationBucket.UNDER_FIVE_HOURS -> 1L until FIVE_HOURS_SECONDS
        FacetDurationBucket.FIVE_TO_TEN_HOURS -> FIVE_HOURS_SECONDS until TEN_HOURS_SECONDS
        FacetDurationBucket.TEN_TO_TWENTY_HOURS -> TEN_HOURS_SECONDS until TWENTY_HOURS_SECONDS
        FacetDurationBucket.TWENTY_HOURS_OR_MORE ->
            TWENTY_HOURS_SECONDS..DurationSanity.MAX_PLAUSIBLE_SECONDS
    }

    fun bucketFor(durationSeconds: Long): FacetDurationBucket? {
        if (!DurationSanity.isPlausible(durationSeconds)) return null
        return when {
            durationSeconds < FIVE_HOURS_SECONDS -> FacetDurationBucket.UNDER_FIVE_HOURS
            durationSeconds < TEN_HOURS_SECONDS -> FacetDurationBucket.FIVE_TO_TEN_HOURS
            durationSeconds < TWENTY_HOURS_SECONDS -> FacetDurationBucket.TEN_TO_TWENTY_HOURS
            else -> FacetDurationBucket.TWENTY_HOURS_OR_MORE
        }
    }

    fun labelFor(bucket: FacetDurationBucket): String = when (bucket) {
        FacetDurationBucket.UNDER_FIVE_HOURS -> "До 5 год"
        FacetDurationBucket.FIVE_TO_TEN_HOURS -> "5–10 год"
        FacetDurationBucket.TEN_TO_TWENTY_HOURS -> "10–20 год"
        FacetDurationBucket.TWENTY_HOURS_OR_MORE -> "Понад 20 год"
    }

    fun summarize(durationSeconds: Collection<Long>): EditionDurationSummary? {
        val known = durationSeconds.filter(DurationSanity::isPlausible).distinct().sorted()
        if (known.isEmpty()) return null
        if (known.size == 1) return EditionDurationSummary.Single(known.single())

        val shortest = known.first()
        val longest = known.last()
        val materiallyEquivalent = DurationObservationPolicy.decide(
            canonicalDocumentExists = true,
            canonicalDurationSeconds = shortest,
            candidateSeconds = longest
        ) == DurationWriteDecision.NoOp
        if (!materiallyEquivalent) return EditionDurationSummary.Range(shortest, longest)

        val average = known.sum() / known.size
        val rounded = ((average + ROUNDING_SECONDS / 2) / ROUNDING_SECONDS) * ROUNDING_SECONDS
        return EditionDurationSummary.Single(rounded)
    }
}
