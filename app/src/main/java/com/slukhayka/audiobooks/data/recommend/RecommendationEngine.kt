package com.slukhayka.audiobooks.data.recommend

/**
 * On-device book recommendations (spec-19 Track A). Pure JVM, local-only:
 * embeddings are computed on the phone, nothing leaves the device (Q2/Q8).
 *
 * The engine ranks catalogue candidates by semantic similarity to the
 * listener's positive signals (favourite + completed + recently listened,
 * Q3), returns the top-N excluding already-known books, and every pick
 * carries a human reason chip («схоже на Х»). The embedding source is the
 * [TextEmbedder] seam — a keyword baseline today (Q6's "similar by
 * genre+author" baseline), an on-device ONNX model (intfloat/
 * multilingual-e5-small, Q4) behind the same seam later.
 */
object RecommendationEngine {

    /** A catalogue candidate the engine may recommend. */
    data class Candidate(
        val id: String,
        val title: String,
        val author: String = "",
        val genre: String = "",
        val series: String = "",
        val description: String = "",
        val publishedAtEpochMs: Long? = null
    ) {
        /** The text embeddings are computed over (Q3: descriptions/fields). */
        val text: String get() = BookRecommendationText.build(title, author, genre, series, description)
    }

    /** One positive listener signal, weighted (favourite > completed > recent). */
    data class Signal(
        val id: String,
        val title: String,
        val author: String = "",
        val genre: String = "",
        val series: String = "",
        val weight: Double
    ) {
        /** The text the signal's vector is computed over. */
        val text: String get() = BookRecommendationText.build(title, author, genre, series)
    }

    /** One recommendation: a candidate + similarity score + why. */
    data class Recommendation(
        val candidate: Candidate,
        val score: Double,
        /** The strongest signal behind this pick («схоже на <title>»). */
        val reasonTitle: String,
        val semanticScore: Double = score,
        val isExploration: Boolean = false
    )

    /**
     * Ranks [candidates] against [signals] by embedding each text on the
     * fly through [embedder]. Prefer [recommendWithVectors] when the
     * vectors are cached (spec-19 Q7): it skips re-embedding entirely.
     */
    fun recommend(
        candidates: List<Candidate>,
        signals: List<Signal>,
        embedder: TextEmbedder,
        excludeIds: Set<String>,
        topN: Int = 10
    ): List<Recommendation> {
        val vectors = (candidates.map { it.id to it.text } + signals.map { it.id to it.text })
            .toMap()
            .mapValues { (_, text) -> embedder.embed(text) }
        return recommendWithVectors(candidates, signals, vectors, excludeIds, topN)
    }

    /**
     * Ranks [candidates] against [signals] using a cached id → vector map
     * (spec-19 Q7: the catalogue-versioned embedding cache). Candidates and
     * signals missing from [vectors] are skipped — a missing vector never
     * fabricates a score. Pure: no embedder, no I/O.
     */
    fun recommendWithVectors(
        candidates: List<Candidate>,
        signals: List<Signal>,
        vectors: Map<String, FloatArray>,
        excludeIds: Set<String>,
        topN: Int = 10
    ): List<Recommendation> {
        return RecommendationPersonalization.rank(
            candidates = candidates,
            signals = signals,
            vectors = vectors,
            excludedWorkIds = excludeIds,
            topN = topN,
            explorationCount = if (topN >= 10) 2 else 0
        )
    }

    /** Cosine similarity in [0, 1]; zero for empty or degenerate vectors. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

}
