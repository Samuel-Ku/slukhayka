package com.slukhayka.audiobooks.data.recommend

import java.text.Normalizer
import kotlin.math.abs

/**
 * Transparent local adaptation layered on top of the frozen embedding model.
 * It owns signal weights, positive/negative profiles, score composition and
 * diversity. There is no I/O and no user data leaves the device.
 */
object RecommendationPersonalization {
    private const val DAY_MS = 86_400_000L

    data class WorkBehavior(
        val workId: String,
        val title: String,
        val author: String = "",
        val genre: String = "",
        val series: String = "",
        val isFavorite: Boolean = false,
        val rating: Int? = null,
        val progressFraction: Double = 0.0,
        val progressRecordedAt: Long? = null,
        val completed: Boolean = false,
        val relistened: Boolean = false,
        val reduceSimilar: Boolean = false
    )

    data class ScoreWeights(
        val semantic: Double = .60,
        val author: Double = .15,
        val genre: Double = .10,
        val series: Double = .05,
        val freshness: Double = .10
    )

    /** The same Unicode/punctuation normalization used for Work-side identity keys. */
    fun identityKey(value: String): String = normalize(value)

    fun signalsFor(behaviors: List<WorkBehavior>, nowEpochMs: Long): List<RecommendationEngine.Signal> =
        behaviors.groupBy { it.workId }
            .values
            .mapNotNull { editions ->
                editions.mapNotNull { signalFor(it, nowEpochMs) }
                    .maxByOrNull { abs(it.weight) }
            }

    private fun signalFor(
        behavior: WorkBehavior,
        nowEpochMs: Long
    ): RecommendationEngine.Signal? {
            var weight = when (behavior.rating) {
                5 -> 1.2
                4 -> .8
                2 -> -.8
                1 -> -1.2
                else -> 0.0
            }
            if (behavior.isFavorite) weight += 1.0
            if (behavior.completed) weight += .9
            if (behavior.relistened) weight += 1.3
            if (behavior.reduceSimilar) weight -= 1.0
            val progressWeight = when {
                behavior.progressFraction >= .7 -> .5
                behavior.progressFraction >= .3 -> .25
                else -> 0.0
            }
            weight += progressWeight * progressDecay(
                recordedAt = behavior.progressRecordedAt,
                nowEpochMs = nowEpochMs
            )
            weight = weight.coerceIn(-1.5, 1.5)
            return if (abs(weight) < 1e-9) null else RecommendationEngine.Signal(
                id = behavior.workId,
                title = behavior.title,
                author = behavior.author,
                genre = behavior.genre,
                series = behavior.series,
                weight = weight
            )
    }

    fun rank(
        candidates: List<RecommendationEngine.Candidate>,
        signals: List<RecommendationEngine.Signal>,
        vectors: Map<String, FloatArray>,
        excludedWorkIds: Set<String> = emptySet(),
        excludedAuthors: Set<String> = emptySet(),
        weights: ScoreWeights = ScoreWeights(),
        nowEpochMs: Long = System.currentTimeMillis(),
        topN: Int = 10,
        explorationCount: Int = 2
    ): List<RecommendationEngine.Recommendation> {
        if (topN <= 0) return emptyList()
        val positives = signals.filter { it.weight > 0.0 && vectors[it.id] != null }
        if (positives.isEmpty()) return emptyList()
        val negatives = signals.filter { it.weight < 0.0 && vectors[it.id] != null }
        val positiveCentroid = centroid(positives, vectors) ?: return emptyList()
        val negativeCentroid = centroid(negatives, vectors)
        val hiddenAuthors = excludedAuthors.map(::normalize).filter { it.isNotEmpty() }.toSet()

        val scored = candidates.asSequence()
            .filter { it.id !in excludedWorkIds }
            .filter { normalize(it.author) !in hiddenAuthors }
            .mapNotNull { candidate ->
                val vector = vectors[candidate.id] ?: return@mapNotNull null
                val positiveSimilarity = RecommendationEngine.cosine(vector, positiveCentroid)
                if (positiveSimilarity <= 0.0) return@mapNotNull null
                val negativeSimilarity = negativeCentroid?.let {
                    RecommendationEngine.cosine(vector, it)
                } ?: 0.0
                val semantic = positiveSimilarity - .70 * negativeSimilarity
                val reason = positives.maxByOrNull {
                    RecommendationEngine.cosine(vector, vectors.getValue(it.id))
                } ?: return@mapNotNull null
                val score = weights.semantic * semantic +
                    weights.author * affinity(candidate.author, positives) { it.author } +
                    weights.genre * affinity(candidate.genre, positives) { it.genre } +
                    weights.series * affinity(candidate.series, positives) { it.series } +
                    weights.freshness * freshness(candidate.publishedAtEpochMs, nowEpochMs)
                RecommendationEngine.Recommendation(
                    candidate = candidate,
                    score = score,
                    reasonTitle = reason.title,
                    semanticScore = semantic
                )
            }
            .sortedWith(compareByDescending<RecommendationEngine.Recommendation> { it.score }
                .thenBy { it.candidate.id })
            .toList()

        val exploreSlots = explorationCount.coerceIn(0, topN)
        val personalSlots = topN - exploreSlots
        val selected = mutableListOf<RecommendationEngine.Recommendation>()
        val authorCounts = mutableMapOf<String, Int>()
        val seriesCounts = mutableMapOf<String, Int>()

        fun addIfDiverse(item: RecommendationEngine.Recommendation, exploration: Boolean): Boolean {
            val author = normalize(item.candidate.author)
            val series = normalize(item.candidate.series)
            if (author.isNotEmpty() && authorCounts.getOrDefault(author, 0) >= 2) return false
            if (series.isNotEmpty() && seriesCounts.getOrDefault(series, 0) >= 1) return false
            selected += item.copy(isExploration = exploration)
            if (author.isNotEmpty()) authorCounts[author] = authorCounts.getOrDefault(author, 0) + 1
            if (series.isNotEmpty()) seriesCounts[series] = seriesCounts.getOrDefault(series, 0) + 1
            return true
        }

        for (item in scored) {
            if (selected.size >= personalSlots) break
            addIfDiverse(item, exploration = false)
        }
        val selectedIds = selected.mapTo(mutableSetOf()) { it.candidate.id }
        val explorationPool = scored.asSequence()
            .filter { it.candidate.id !in selectedIds && it.semanticScore > 0.0 }
            .sortedBy { stableExplorationKey(it.candidate.id) }
        var addedExploration = 0
        for (item in explorationPool) {
            if (addedExploration >= exploreSlots) break
            if (addIfDiverse(item, exploration = true)) addedExploration++
        }
        if (selected.size < topN) {
            val nowSelected = selected.mapTo(mutableSetOf()) { it.candidate.id }
            for (item in scored) {
                if (selected.size >= topN) break
                if (item.candidate.id !in nowSelected && addIfDiverse(item, exploration = false)) {
                    nowSelected += item.candidate.id
                }
            }
        }
        return selected
    }

    private fun progressDecay(recordedAt: Long?, nowEpochMs: Long): Double {
        if (recordedAt == null) return 1.0
        val ageDays = ((nowEpochMs - recordedAt).coerceAtLeast(0L)).toDouble() / DAY_MS
        return when {
            ageDays <= 30.0 -> 1.0
            ageDays >= 180.0 -> 0.0
            else -> (180.0 - ageDays) / 150.0
        }
    }

    private fun centroid(
        signals: List<RecommendationEngine.Signal>,
        vectors: Map<String, FloatArray>
    ): FloatArray? {
        val first = signals.firstOrNull()?.let { vectors[it.id] } ?: return null
        val result = FloatArray(first.size)
        var total = 0.0
        for (signal in signals) {
            val vector = vectors[signal.id] ?: continue
            if (vector.size != result.size) continue
            val weight = abs(signal.weight)
            for (i in result.indices) result[i] += (vector[i] * weight).toFloat()
            total += weight
        }
        if (total == 0.0) return null
        for (i in result.indices) result[i] = (result[i] / total).toFloat()
        return result
    }

    private fun <T> affinity(
        candidateValue: String,
        positives: List<T>,
        value: (T) -> String
    ): Double {
        val normalized = normalize(candidateValue)
        if (normalized.isEmpty()) return 0.0
        return if (positives.any { normalize(value(it)) == normalized }) 1.0 else 0.0
    }

    private fun freshness(publishedAtEpochMs: Long?, nowEpochMs: Long): Double {
        val published = publishedAtEpochMs ?: return 0.0
        val ageDays = ((nowEpochMs - published).coerceAtLeast(0L)).toDouble() / DAY_MS
        return (1.0 - ageDays / 365.0).coerceIn(0.0, 1.0)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun stableExplorationKey(id: String): Int = id.fold(17) { acc, char -> acc * 31 + char.code }
}
