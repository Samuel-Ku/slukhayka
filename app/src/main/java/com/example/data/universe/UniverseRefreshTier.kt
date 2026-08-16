package com.example.data.universe

import java.util.Calendar
import java.util.TimeZone

/**
 * Spec-26 T7 (#181) — the tiered refresh rule: a cached universe resolution
 * re-resolves on a schedule shaped by two signals instead of a flat TTL.
 *
 * - **Signal 1 — chain tail:** the book's series is the LAST in its universe
 *   (no follower in the chain) → a continuation may appear → hot-ish.
 * - **Signal 2 — series age:** the last publication year of the series'
 *   works (Wikidata P577, captured at resolution) — a young series may still
 *   grow.
 *
 * Tiers: **hot** (~7 days) — tail AND younger than ~3 years; **warm**
 * (~30 days) — tail-but-old, or fresh-in-the-middle; **cold** (~180 days,
 * the floor) — everything else, so even a cold membership eventually
 * re-resolves and spreads Wikidata fixes.
 *
 * Pure JVM: the rule is unit-tested without Room or Firebase; the caller
 * feeds it the cached series row's signals.
 */
object UniverseRefreshTier {

    const val HOT_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    const val WARM_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    const val COLD_MILLIS: Long = 180L * 24 * 60 * 60 * 1000

    /** A series is "young" while its last publication is under 3 years old. */
    const val YOUNG_SERIES_YEARS = 3

    /**
     * The refresh interval of one membership, from its cached signals:
     * `isTail` (the series sits at the chain tail) and `publicationYear`
     * (P577; null = unknown age — never hot). The cold tier is the floor.
     */
    fun tierTtlMillis(isTail: Boolean, publicationYear: Int?, nowYear: Int): Long = when {
        isTail && isYoung(publicationYear, nowYear) -> HOT_MILLIS
        isTail || isYoung(publicationYear, nowYear) -> WARM_MILLIS
        else -> COLD_MILLIS
    }

    /** True while the series' last publication is under [YOUNG_SERIES_YEARS] years old. */
    fun isYoung(publicationYear: Int?, nowYear: Int): Boolean =
        publicationYear != null && nowYear - publicationYear < YOUNG_SERIES_YEARS

    /** The calendar year of an epoch-millis instant (UTC) — pure JVM, so the
     *  tier rule stays testable without Android. */
    fun epochYear(epochMillis: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMillis }
            .get(Calendar.YEAR)
}
