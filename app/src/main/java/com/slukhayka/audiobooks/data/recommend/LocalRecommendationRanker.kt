package com.slukhayka.audiobooks.data.recommend

import kotlin.math.ln
import kotlin.math.sqrt

/** Local, sparse content retrieval. No native model, network, or persistent vector cache.
 * The corpus index survives feedback changes; only changed catalogues rebuild it.
 * Explicit dislikes are vetoes. Each positive interest competes independently so a
 * listener who likes both history and fantasy does not get their averaged midpoint.
 */
class LocalRecommendationRanker {
    private var indexedCandidates = emptyList<RecommendationEngine.Candidate>()
    private var featuredCandidates = emptyList<RecommendationEngine.Candidate>()
    private var features = emptyMap<String, Features>()
    private var documents = emptyMap<String, Map<String, Double>>()
    private var inverseFrequency = emptyMap<String, Double>()

    @Synchronized
    fun rank(
        candidates: List<RecommendationEngine.Candidate>,
        signals: List<RecommendationEngine.Signal>,
        excludedWorkIds: Set<String> = emptySet(),
        excludedAuthors: Set<String> = emptySet(),
        popularityByWorkId: Map<String, Double> = emptyMap(),
        sourceLabelsByWorkId: Map<String, String> = emptyMap(),
        nowEpochMs: Long = System.currentTimeMillis(),
        topN: Int = 10,
        contentGraph: PrecomputedRecommendationGraph? = null
    ): List<RecommendationEngine.Recommendation> {
        if (topN <= 0) return emptyList()
        val catalog = candidates.distinctBy { it.id }
        val graph = contentGraph?.takeIf { it.isFresh(nowEpochMs) }
        val hidden = excludedAuthors.map(::key).toSet()
        val ordered = RecommendationPersonalization.resolveSignals(signals)
            .sortedWith(compareByDescending<RecommendationEngine.Signal> { kotlin.math.abs(it.weight) }
            .thenBy { it.id })
        val positive = ordered.filter { it.weight > 0 }.take(64)
        val negative = ordered.filter { it.weight < 0 }.take(64)
        val active = positive + negative
        val needsText = active.isNotEmpty() && (graph == null || active.any { !graph.containsWork(it.id) } ||
            catalog.any { !graph.containsWork(it.id) })
        if (needsText) index(catalog)
        if (catalog != featuredCandidates) {
            features = catalog.associate { it.id to Features(key(it.author), key(it.series), tokens(it.genre).keys) }
            featuredCandidates = catalog
        }
        val knownSignalIds = signals.mapTo(HashSet()) { it.id }
        val signalVectors = if (needsText) active.associate {
            it.id to (documents[it.id] ?: vector(tokens(it.text)))
        } else emptyMap()
        val signalFeatures = active.associate { it.id to Features(key(it.author), key(it.series), tokens(it.genre).keys) }
        fun similarity(candidate: RecommendationEngine.Candidate, signal: RecommendationEngine.Signal): Double {
            val lexical = if (graph != null && graph.containsWork(candidate.id) && graph.containsWork(signal.id)) {
                graph.similarity(candidate.id, signal.id)
            } else dot(documents[candidate.id].orEmpty(), signalVectors[signal.id].orEmpty())
            val a = features.getValue(candidate.id)
            val b = signalFeatures.getValue(signal.id)
            val author = if (a.author.isNotEmpty() && a.author == b.author) 1.0 else 0.0
            val series = if (a.series.isNotEmpty() && a.series == b.series) 1.0 else 0.0
            val genres = if (a.genres.isEmpty() || b.genres.isEmpty()) 0.0 else
                a.genres.count { it in b.genres }.toDouble() / minOf(a.genres.size, b.genres.size)
            return (.5 * lexical + .25 * author + .15 * genres + .10 * series).coerceIn(0.0, 1.0)
        }
        val day = nowEpochMs / 86_400_000L
        val scored = catalog.asSequence()
            .filter { it.id !in excludedWorkIds && features.getValue(it.id).author !in hidden }
            .filter { it.id !in knownSignalIds }
            .mapNotNull { candidate ->
                var best: RecommendationEngine.Signal? = null
                var affinity = 0.0
                for (signal in positive) {
                    val match = similarity(candidate, signal) * signal.weight.coerceAtMost(1.5)
                    if (best == null || match > affinity) {
                        best = signal
                        affinity = match
                    }
                }
                val aversion = negative.maxOfOrNull { similarity(candidate, it) * -it.weight } ?: 0.0
                // A source's top must not override a listener's explicit dislike.
                if (aversion > 0 && aversion >= affinity) return@mapNotNull null
                val popularity = (popularityByWorkId[candidate.id] ?: 0.0).coerceIn(0.0, 1.0)
                val score = affinity - aversion + .1 * popularity
                RecommendationEngine.Recommendation(candidate, score,
                    reasonTitle = if (affinity > 0) best?.title.orEmpty() else "",
                    semanticScore = affinity - aversion,
                    isExploration = affinity <= 0,
                    sourceLabel = if (affinity <= 0) sourceLabelsByWorkId[candidate.id] else null)
            }.toList()
        val personal = scored.filter { it.semanticScore > 0 }
            .sortedWith(compareByDescending<RecommendationEngine.Recommendation> { it.score }.thenBy { it.candidate.id })
        val selected = mutableListOf<RecommendationEngine.Recommendation>()
        val authors = mutableMapOf<String, Int>()
        val series = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        fun add(item: RecommendationEngine.Recommendation, explore: Boolean): Boolean {
            val author = features.getValue(item.candidate.id).author
            val cycle = features.getValue(item.candidate.id).series
            if (item.candidate.id in ids || author.isNotEmpty() && authors.getOrDefault(author, 0) >= 2 ||
                cycle.isNotEmpty() && cycle in series) return false
            selected += item.copy(isExploration = explore)
            ids += item.candidate.id
            if (author.isNotEmpty()) authors[author] = authors.getOrDefault(author, 0) + 1
            if (cycle.isNotEmpty()) series += cycle
            return true
        }
        val personalSlots = (topN - minOf(2, topN / 3)).coerceAtLeast(0)
        for (item in personal) {
            if (selected.size >= topN) break
            add(item, selected.size >= personalSlots)
        }
        // Daily rotation changes discovery without a timer, random flicker or CPU polling.
        // Personal picks retain relevance order; discovery also works for a new listener.
        val discovery = scored.sortedWith(compareBy<RecommendationEngine.Recommendation> {
            rotationKey(it.candidate.id, day)
        }.thenBy { it.candidate.id })
        for (item in discovery) {
            if (selected.size >= topN) break
            add(item, true)
        }
        return selected
    }

    private fun index(candidates: List<RecommendationEngine.Candidate>) {
        if (candidates == indexedCandidates) return
        val corpus = candidates.associate { it.id to tokens(it.text) }
        val counts = mutableMapOf<String, Int>()
        corpus.values.forEach { words -> words.keys.forEach { counts[it] = counts.getOrDefault(it, 0) + 1 } }
        inverseFrequency = counts.mapValues { (_, count) -> ln((corpus.size + 1.0) / (count + 1.0)) + 1.0 }
        documents = corpus.mapValues { (_, words) -> vector(words) }
        indexedCandidates = candidates.toList()
    }

    private fun vector(words: Map<String, Int>): Map<String, Double> {
        val weighted = words.mapValues { (word, count) -> (1.0 + ln(count.toDouble())) * (inverseFrequency[word] ?: 1.0) }
        val norm = sqrt(weighted.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else weighted.mapValues { it.value / norm }
    }

    private fun tokens(text: String): Map<String, Int> = WORD.findAll(key(text).take(2_000))
        .map { it.value }.filter { it.length > 2 && it !in STOP }.take(200)
        .groupingBy { it }.eachCount()

    private fun dot(a: Map<String, Double>, b: Map<String, Double>): Double =
        if (a.size > b.size) dot(b, a) else a.entries.sumOf { (word, value) -> value * (b[word] ?: 0.0) }

    private data class Features(val author: String, val series: String, val genres: Set<String>)
    private fun key(value: String) = RecommendationPersonalization.identityKey(value)
    private fun rotationKey(id: String, day: Long): Long {
        var hash = id.fold(1125899906842597L xor day) { acc, c -> acc * 31 + c.code }
        hash = (hash xor (hash ushr 33)) * -49064778989728563L
        return hash xor (hash ushr 33)
    }
    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}]+")
        val STOP = setOf("аудіокнига", "слухати", "онлайн", "книга", "книги", "для", "про", "що", "the", "and", "with")
    }
}
