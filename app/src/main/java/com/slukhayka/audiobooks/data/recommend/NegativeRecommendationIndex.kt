package com.slukhayka.audiobooks.data.recommend

/** Normalized public metadata shared by positive scoring and complete negative scoring. */
internal data class RecommendationFeatures(val author: String, val series: String, val genres: Set<String>) {
    fun affinity(other: RecommendationFeatures): Double {
        val authorMatch = if (author.isNotEmpty() && author == other.author) 1.0 else 0.0
        val seriesMatch = if (series.isNotEmpty() && series == other.series) 1.0 else 0.0
        val genreMatch = if (genres.isEmpty() || other.genres.isEmpty()) 0.0 else
            genres.count { it in other.genres }.toDouble() / minOf(genres.size, other.genres.size)
        return .25 * authorMatch + .15 * genreMatch + .10 * seriesMatch
    }
}

internal fun sparseRecommendationDot(a: Map<String, Double>, b: Map<String, Double>): Double =
    if (a.size > b.size) sparseRecommendationDot(b, a) else
        a.entries.sumOf { (word, value) -> value * (b[word] ?: 0.0) }

/**
 * All explicit negatives, without a history-length cutoff. Metadata is grouped by
 * matching fields; graph evidence is compiled from sparse edges, not every pair.
 * Text postings visit only possible overlaps. A veto can stop early; a returned
 * candidate always receives the complete maximum aversion. No graph is retained.
 * Owned by the synchronized local ranker; never persisted or uploaded.
 */
internal class NegativeRecommendationIndex(
    signals: List<RecommendationEngine.Signal>,
    signalFeatures: Map<String, RecommendationFeatures>,
    candidateFeatures: Map<String, RecommendationFeatures>,
    graph: PrecomputedRecommendationGraph?,
    vectors: Map<String, Map<String, Double>>
) {
    private data class MetadataKey(val author: String = "", val series: String = "")
    private data class WeightedMetadata(val features: RecommendationFeatures, val weight: Double)
    private class MetadataGroup {
        var strongest: WeightedMetadata? = null
        val genres = mutableMapOf<String, MutableMap<Set<String>, WeightedMetadata>>()
    }
    private data class Score(var value: Double, var complete: Boolean)

    private val weights = signals.associate { it.id to -it.weight }
    private val features = weights.keys.associateWith { signalFeatures.getValue(it) }
    private val metadata = mutableMapOf<MetadataKey, MetadataGroup>()
    private val graphScores = mutableMapOf<String, Double>()
    private val scores = mutableMapOf<String, Score>()
    private val lexicalVectors: Map<String, Map<String, Double>>
    private val allWords: Map<String, List<String>>
    private val uncoveredWords: Map<String, List<String>>

    init {
        for ((id, feature) in features) {
            val weight = weights.getValue(id)
            addMetadata(MetadataKey(), feature.copy(author = "", series = ""), weight)
            if (feature.author.isNotEmpty()) {
                addMetadata(MetadataKey(author = feature.author), feature.copy(series = ""), weight)
            }
            if (feature.series.isNotEmpty()) {
                addMetadata(MetadataKey(series = feature.series), feature.copy(author = ""), weight)
            }
            if (feature.author.isNotEmpty() && feature.series.isNotEmpty()) {
                addMetadata(MetadataKey(feature.author, feature.series), feature, weight)
            }
        }
        graph?.forEachEdge { from, to, similarity ->
            addGraphScore(from, to, similarity, candidateFeatures)
            addGraphScore(to, from, similarity, candidateFeatures)
        }
        val uncoveredCandidates = graph == null || candidateFeatures.keys.any { !graph.containsWork(it) }
        val coveredNegatives = if (graph == null) emptySet() else weights.keys.filterTo(HashSet()) { graph.containsWork(it) }
        lexicalVectors = vectors.filterKeys { it in weights && (uncoveredCandidates || it !in coveredNegatives) }
        allWords = wordIndex(lexicalVectors)
        uncoveredWords = if (coveredNegatives.isEmpty()) allWords else
            wordIndex(lexicalVectors.filterKeys { it !in coveredNegatives })
    }

    fun aversion(
        id: String,
        candidate: RecommendationFeatures,
        vector: Map<String, Double>,
        graphCovered: Boolean,
        positiveAffinity: Double
    ): Double {
        val words = if (graphCovered) uncoveredWords else allWords
        val score = scores.getOrPut(id) {
            Score(maxOf(metadataScore(candidate), graphScores[id] ?: 0.0), words.isEmpty() || vector.isEmpty())
        }
        if (score.complete || score.value > 0.0 && score.value >= positiveAffinity) return score.value
        var complete = true
        for (negativeId in vector.keys.asSequence().flatMap { words[it].orEmpty().asSequence() }.distinct()) {
            val similarity = (.5 * sparseRecommendationDot(vector, lexicalVectors.getValue(negativeId)) +
                candidate.affinity(features.getValue(negativeId))).coerceIn(0.0, 1.0)
            score.value = maxOf(score.value, similarity * weights.getValue(negativeId))
            if (score.value > 0.0 && score.value >= positiveAffinity) {
                complete = false
                break
            }
        }
        // Do not retain posting iterators or their dedup sets per candidate.
        score.complete = complete
        return score.value
    }

    private fun addMetadata(key: MetadataKey, feature: RecommendationFeatures, weight: Double) {
        val group = metadata.getOrPut(key) { MetadataGroup() }
        val entry = WeightedMetadata(feature, weight)
        if (weight > (group.strongest?.weight ?: 0.0)) group.strongest = entry
        for (genre in feature.genres) {
            val posting = group.genres.getOrPut(genre) { mutableMapOf() }
            if (weight > (posting[feature.genres]?.weight ?: 0.0)) posting[feature.genres] = entry
        }
    }

    private fun metadataScore(candidate: RecommendationFeatures): Double {
        val keys = listOf(MetadataKey(), MetadataKey(author = candidate.author),
            MetadataKey(series = candidate.series), MetadataKey(candidate.author, candidate.series)).distinct()
        return keys.maxOf { key ->
            val group = metadata[key] ?: return@maxOf 0.0
            // Without a genre overlap, only the largest weight can win within
            // this author/series group. Visit other entries through genre postings.
            var score = group.strongest?.let { candidate.affinity(it.features) * it.weight } ?: 0.0
            for (entry in candidate.genres.asSequence().flatMap { group.genres[it].orEmpty().values.asSequence() }.distinct()) {
                score = maxOf(score, candidate.affinity(entry.features) * entry.weight)
            }
            score
        }
    }

    private fun addGraphScore(
        negativeId: String,
        candidateId: String,
        similarity: Double,
        candidates: Map<String, RecommendationFeatures>
    ) {
        val weight = weights[negativeId] ?: return
        val candidate = candidates[candidateId] ?: return
        val value = (.5 * similarity + candidate.affinity(features.getValue(negativeId))).coerceIn(0.0, 1.0) * weight
        graphScores[candidateId] = maxOf(graphScores[candidateId] ?: 0.0, value)
    }

    private fun wordIndex(vectors: Map<String, Map<String, Double>>): Map<String, List<String>> {
        val words = mutableMapOf<String, MutableList<String>>()
        for ((id, vector) in vectors) for (word in vector.keys) words.getOrPut(word) { mutableListOf() }.add(id)
        return words
    }
}
