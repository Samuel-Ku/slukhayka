package com.slukhayka.audiobooks.data.privacy

import kotlin.random.Random

/**
 * Spec-38 (#252) — the human-rhythm pacing parameters behind the privacy
 * door. The values ship as defaults; the actual application to the download
 * loop is spec-38 T5 (#257) — this seam only owns and exposes them.
 */
data class PacingParams(
    val minPauseMillis: Long = 1_500,
    val maxPauseMillis: Long = 4_500,
    val burstLimit: Int = 6,
    val burstWindowMillis: Long = 60_000
)

/**
 * The pacing decision: a random pause inside [PacingParams]' range between
 * chapter fetches, plus a per-domain burst gate (no more than [PacingParams.burstLimit]
 * requests inside one window — a scraper-shaped pattern never forms).
 *
 * Deterministic by injection: callers pass a seeded [Random] in tests and
 * pin exact sequences without sleeping (spec-38 Testing Decisions).
 *
 * Spec-38 T5 (#257): one instance is shared by all concurrent download
 * workers, so both decisions are synchronized — the burst bookkeeping must
 * stay exact under racing workers, and a seeded generator must not be
 * corrupted by parallel draws.
 */
class PacingPolicy(
    private val params: PacingParams = PacingParams(),
    private val random: Random = Random.Default
) {

    private val hits = HashMap<String, MutableList<Long>>()

    /** One pause length, uniformly inside [minPauseMillis, maxPauseMillis]. */
    @Synchronized
    fun nextPauseMillis(): Long =
        params.minPauseMillis +
            random.nextLong(params.maxPauseMillis - params.minPauseMillis + 1)

    /**
     * Whether another request to [domain] at [nowMillis] fits the burst
     * budget. Accepted hits are recorded; refused ones are not.
     */
    @Synchronized
    fun allowsRequest(domain: String, nowMillis: Long): Boolean {
        val list = hits.getOrPut(domain) { mutableListOf() }
        val windowStart = nowMillis - params.burstWindowMillis
        list.removeAll { it < windowStart }
        if (list.size >= params.burstLimit) return false
        list.add(nowMillis)
        return true
    }
}
