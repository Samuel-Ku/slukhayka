package com.slukhayka.audiobooks.data.recommend

/** One coherent published context, derived vectors and attempted signal inputs. */
data class EmbeddingPassSnapshot(
    val context: EmbeddingContext? = null,
    val vectors: Map<String, FloatArray> = emptyMap(),
    val attemptedSignalTexts: Map<String, Set<String>> = emptyMap(),
    val ready: Boolean = false
) {
    init {
        require(context != null || vectors.isEmpty()) { "Published vectors need an identified backend" }
        require(context == null || vectors.values.all(context::accepts)) { "Published vectors differ from their backend context" }
    }

    fun needsRefresh(signals: List<RecommendationEngine.Signal>): Boolean =
        signals.any { it.text !in attemptedSignalTexts[it.id].orEmpty() }
}
