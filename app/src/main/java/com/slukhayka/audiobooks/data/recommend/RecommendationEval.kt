package com.slukhayka.audiobooks.data.recommend

import kotlin.math.ln

/**
 * Offline recommendation metrics. [evaluateLeaveOneOut] uses the entire
 * candidate catalog and predicts the held-out positive from remaining signals.
 * Both backends share the production personalization and displayed top-K policy.
 * The older sampled, inverted [evaluate] remains only for historical fixtures;
 * its result does not establish the real-catalog acceptance gate.
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

    data class Fold(
        val cohortIndex: Int,
        val heldOutId: String,
        val candidateCount: Int,
        val semanticRank: Int?,
        val baselineRank: Int?,
        val semanticTopIds: List<String>,
        val baselineTopIds: List<String>
    )

    data class LeaveOneOutReport(val report: Report, val folds: List<Fold>) {
        /** #487 asks for more hits in the top K; an ordering-only tie cannot pass. */
        val passesGate: Boolean get() = report.semanticRecallAtK > report.baselineRecallAtK &&
            report.semanticNdcgAtK >= report.baselineNdcgAtK
    }

    /** Full catalog LOO: all other positive Works train the profile; only the held-out Work is relevant. */
    fun evaluateLeaveOneOut(
        completionCohorts: List<List<String>>,
        candidates: List<RecommendationEngine.Candidate>,
        semanticVectors: Map<String, FloatArray>,
        baselineVectors: Map<String, FloatArray>,
        k: Int = 20
    ): LeaveOneOutReport {
        require(k > 0) { "k must be positive" }
        require(completionCohorts.isNotEmpty()) { "At least one cohort is required" }
        require(candidates.isNotEmpty()) { "Catalog must be nonempty" }
        val catalog = candidates.sortedBy { it.id }
        val byId = catalog.associateBy { it.id }
        require(byId.size == catalog.size && byId.keys.none { it.isBlank() }) { "Candidate Work ids must be unique and nonblank" }
        for ((name, vectors) in listOf("semantic" to semanticVectors, "baseline" to baselineVectors)) {
            require(catalog.all { it.id in vectors }) { "$name vectors must cover the entire catalog" }
            val dimension = vectors.getValue(catalog.first().id).size
            require(dimension > 0 && catalog.all { candidate ->
                val vector = vectors.getValue(candidate.id)
                vector.size == dimension && vector.all { it.isFinite() } &&
                    vector.sumOf { it.toDouble() * it } > 1e-12
            }) { "$name backend produced a missing, degenerate or nonfinite vector" }

        }
        val folds = completionCohorts.flatMapIndexed { cohortIndex, cohort ->
            require(cohort.size >= 2 && cohort.toSet().size == cohort.size) { "Cohort $cohortIndex needs at least two distinct Works" }
            require(cohort.all { it in byId }) { "Every cohort Work must be present in the catalog" }
            cohort.map { heldOut ->
                val training = cohort.filter { it != heldOut }
                val signals = training.map { id ->
                    val work = byId.getValue(id)
                    RecommendationEngine.Signal(id, work.title, work.author, work.genre, work.series, weight = .9)
                }
                val exclusions = training.toSet()
                fun top(vectors: Map<String, FloatArray>) = RecommendationEngine.recommendWithVectors(
                    catalog, signals, vectors, exclusions, k
                ).map { it.candidate.id }
                val semanticTop = top(semanticVectors)
                val baselineTop = top(baselineVectors)
                fun rank(top: List<String>): Int? = top.indexOf(heldOut).takeIf { it >= 0 }?.plus(1)
                Fold(cohortIndex, heldOut, catalog.size - exclusions.size,
                    rank(semanticTop), rank(baselineTop), semanticTop, baselineTop)
            }
        }
        fun recall(rank: (Fold) -> Int?) = folds.count { rank(it) != null }.toDouble() / folds.size
        fun ndcg(rank: (Fold) -> Int?) = folds.sumOf { fold ->
            rank(fold)?.let { ln(2.0) / ln(it + 1.0) } ?: 0.0
        } / folds.size
        return LeaveOneOutReport(Report(recall { it.semanticRank }, ndcg { it.semanticRank },
            recall { it.baselineRank }, ndcg { it.baselineRank }), folds)
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
        // #487 — a true recall: hits over the whole relevant set, averaged.
        val relevantPerFold = (completionSet.size - 1).coerceAtLeast(1)
        return Report(
            semanticRecallAtK = semanticHits.toDouble() / (folds * relevantPerFold),
            semanticNdcgAtK = if (folds > 0) semanticDcg / (folds * idealDcg) else 0.0,
            baselineRecallAtK = baselineHits.toDouble() / (folds * relevantPerFold),
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
