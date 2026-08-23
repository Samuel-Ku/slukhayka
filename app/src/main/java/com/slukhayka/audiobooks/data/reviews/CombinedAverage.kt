package com.slukhayka.audiobooks.data.reviews

/**
 * Spec-40 #279 — the honest headline of a book's «Відгуки» block.
 *
 * [value] is the raw (unrounded) combined average, [count] the real number
 * of addends behind it — so the UI's «джерела і слухачі · N оцінок» label can
 * never disagree with the number it labels (ADR-0014 honest data).
 */
data class CombinedAverageResult(val value: Double, val count: Int)

/**
 * Спільна середня (spec-40 #279, ADR-0014 appendix): one flat arithmetic
 * mean over two vote pools — every SOURCE that carries a rating contributes
 * one vote (a null source rating = absent: excluded, never fabricated as 0),
 * and every LISTENER rating of 1..5 contributes one vote. Zero addends →
 * null: nobody rated the book, so no stars are shown — a fabricated zero
 * would be a lie. Listener ratings outside 1..5 are ignored defensively
 * (hostile/corrupt input must not poison the number). Pure and
 * deterministic — JVM-tested directly (CI, not phones).
 */
object CombinedAverage {

    const val MIN_LISTENER_RATING: Int = 1
    const val MAX_LISTENER_RATING: Int = 5

    fun average(sourceRatings: List<Double?>, listenerRatings: List<Int>): CombinedAverageResult? {
        val addends = ArrayList<Double>(sourceRatings.size + listenerRatings.size)
        for (rating in sourceRatings) {
            if (rating != null) addends += rating
        }
        for (rating in listenerRatings) {
            if (rating in MIN_LISTENER_RATING..MAX_LISTENER_RATING) addends += rating.toDouble()
        }
        if (addends.isEmpty()) return null
        return CombinedAverageResult(value = addends.sum() / addends.size, count = addends.size)
    }
}
