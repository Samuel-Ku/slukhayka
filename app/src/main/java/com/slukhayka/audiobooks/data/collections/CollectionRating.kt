package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#694) — the honest arithmetic of collection stars.
 *
 * The rules live here, pure and testable, because the aggregate is what the
 * listener actually sees: one vote per person (the shared layer enforces the
 * key), a re-vote REPLACES the previous one, and a collection nobody rated has
 * NO average at all — a fabricated zero would be a lie (ADR-0014).
 */
object CollectionRating {

    const val MIN_STARS: Int = 1
    const val MAX_STARS: Int = 5

    fun isValidStars(stars: Int): Boolean = stars in MIN_STARS..MAX_STARS

    /**
     * The real average of [count] votes summing to [sum], or null when nobody
     * voted (or the stored aggregate is impossible) — a card then shows no
     * stars at all.
     */
    fun average(sum: Int, count: Int): Double? {
        if (count <= 0) return null
        if (sum <= 0) return null
        return sum.toDouble() / count
    }

    /**
     * The aggregate after one listener's vote: [previousStars] is that
     * listener's earlier vote (null on a first vote), so a re-vote REPLACES it
     * instead of adding a second one.
     *
     * @return the new `(sum, count)`.
     */
    fun applyVote(sum: Int, count: Int, previousStars: Int?, newStars: Int): Pair<Int, Int> {
        require(isValidStars(newStars)) { "stars must be $MIN_STARS..$MAX_STARS" }
        val hadPrevious = previousStars != null && isValidStars(previousStars)
        val baseSum = (sum - (if (hadPrevious) previousStars!! else 0)).coerceAtLeast(0)
        val baseCount = (count - (if (hadPrevious) 1 else 0)).coerceAtLeast(0)
        return (baseSum + newStars) to (baseCount + 1)
    }
}
