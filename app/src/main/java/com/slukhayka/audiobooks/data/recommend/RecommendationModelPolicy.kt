package com.slukhayka.audiobooks.data.recommend

/** The modes the recommendations UI can be in (spec-19 / #483). */
enum class RecommendationModelMode { FULL, DOWNLOADING, SIMPLIFIED, FAILED }

/**
 * #483 — the pure projection of [EmbeddingModelState] onto the mode the
 * settings/row show. Keeps the "which mode am I in" rule testable and out of
 * the UI; the simplified (keyword) mode is never silent.
 */
object RecommendationModelPolicy {

    fun mode(state: EmbeddingModelState): RecommendationModelMode = when (state) {
        is EmbeddingModelState.Installed -> RecommendationModelMode.FULL
        is EmbeddingModelState.Downloading -> RecommendationModelMode.DOWNLOADING
        is EmbeddingModelState.Failed -> RecommendationModelMode.FAILED
        is EmbeddingModelState.NotInstalled -> RecommendationModelMode.SIMPLIFIED
    }

    /** True whenever the row is NOT running the full model — the state is visible. */
    fun isSimplifiedVisible(mode: RecommendationModelMode): Boolean =
        mode != RecommendationModelMode.FULL
}
