package com.slukhayka.audiobooks.data.recommend

import kotlin.math.ln

/**
 * The leave-one-out evaluation gate of the recommendation row (spec-19
 * US11 / Q6): the semantic ranking must beat the genre+author baseline on
 * real completions before the row ships.
 *
 * Method: for each held-out completed book (the signal), rank a random
 * catalogue sample (other completions + distractors) by similarity to that
 * signal, and measure how high the other real completions rank — recall@K
 * and NDCG@K. The same sample and signals feed both the candidate
 * [semanticEmbedder] and the [baselineEmbedder], so the comparison is fair.
 *
 * Pure JVM, deterministic under a seeded RNG — a regression corpus pins it.
 */
object RecommendationEval {

    data class Report(
        val semanticRecallAtK: Double,
        val semanticNdcgAtK: Double,
        val baselineRecallAtK: Double,
        val baselineNdcgAtK: Double
    ) {
        /** Q6 gate: the semantic row only ships if it beats the baseline. */
        val semanticWins: Boolean
            get() = semanticRecallAtK > baselineRecallAtK ||
                (semanticRecallAtK == baselineRecallAtK && semanticNdcgAtK > baselineNdcgAtK)
    }

    /**
     * @param completions ids of books the listener actually finished (the
     *   positive evidence, US2).
     * @param candidates the full catalogue (id → text) to rank from.
     * @param distractorCount how many non-completion books join each fold's
     *   ranked pool.
     * @param k the cutoff (recall@k / NDCG@k).
     */
    fun evaluate(
        completions: List<String>,
        candidates: Map<String, String>,
        semanticEmbedder: TextEmbedder,
        baselineEmbedder: TextEmbedder,
        distractorCount: Int = 40,
        k: Int = 20,
        seed: Long = 42L
    ): Report {
        if (completions.size < 2) {
            return Report(0.0, 0.0, 0.0, 0.0)
        }
        val rng = java.util.Random(seed)
        val completionSet = completions.toSet()
        val distractorPool = candidates.keys
            .filter { it !in completionSet }
            .toMutableList()
            .also { it.shuffle(rng) }

        var semanticHits = 0
        var semanticDcg = 0.0
        var baselineHits = 0
        var baselineDcg = 0.0
        // Ideal DCG: every relevant (other) completion ranked 1..n.
        val idealDcg = dcgAtK((0 until (completionSet.size - 1)).toList(), k)

        // One fold per held-out completion: the signal is the held-out book,
        // the relevance set is the OTHER completions.
        for (signalId in completions) {
            val pool = (completionSet - signalId).toMutableList()
            pool += distractorPool.take(distractorCount)
            val poolSignals = listOf(
                RecommendationEngine.Signal(
                    id = signalId,
                    title = candidates[signalId] ?: signalId,
                    weight = 1.0
                )
            )
            val poolCandidates = pool.map { id ->
                RecommendationEngine.Candidate(id = id, title = candidates[id] ?: id)
            }

            val semanticTop = RecommendationEngine.recommend(
                candidates = poolCandidates,
                signals = poolSignals,
                embedder = semanticEmbedder,
                excludeIds = setOf(signalId),
                topN = k
            )
            val baselineTop = RecommendationEngine.recommend(
                candidates = poolCandidates,
                signals = poolSignals,
                embedder = baselineEmbedder,
                excludeIds = setOf(signalId),
                topN = k
            )

            semanticHits += countRelevantInTop(semanticTop, completionSet - signalId)
            semanticDcg += dcgAtK(
                semanticTop.mapIndexedNotNull { index, rec ->
                    if (rec.candidate.id in completionSet - signalId) index else null
                },
                k
            )
            baselineHits += countRelevantInTop(baselineTop, completionSet - signalId)
            baselineDcg += dcgAtK(
                baselineTop.mapIndexedNotNull { index, rec ->
                    if (rec.candidate.id in completionSet - signalId) index else null
                },
                k
            )
        }

        val folds = completions.size
        return Report(
            semanticRecallAtK = semanticHits.toDouble() / folds,
            semanticNdcgAtK = if (folds > 0) semanticDcg / (folds * idealDcg) else 0.0,
            baselineRecallAtK = baselineHits.toDouble() / folds,
            baselineNdcgAtK = if (folds > 0) baselineDcg / (folds * idealDcg) else 0.0
        )
    }

    private fun countRelevantInTop(
        ranked: List<RecommendationEngine.Recommendation>,
        relevant: Set<String>
    ): Int = ranked.count { it.candidate.id in relevant }

    /** DCG@K over the (0-based) ranks of the relevant items. */
    private fun dcgAtK(relevantRanks: List<Int>, k: Int): Double =
        relevantRanks
            .filter { it < k }
            .sumOf { 1.0 / ln((it + 2).toDouble()) }
}
