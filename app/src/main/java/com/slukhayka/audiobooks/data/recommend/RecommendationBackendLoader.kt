package com.slukhayka.audiobooks.data.recommend

import kotlinx.coroutines.CancellationException

/** Which existing E5 loading boundary supplied the backend. */
enum class RecommendationBackendSource { INSTALLED, BUNDLED }

/** Bounded load outcomes; exception messages and paths never enter UI state. */
enum class RecommendationBackendFailure { UNAVAILABLE, LOAD_FAILED }

/** Runtime backend status is distinct from model file inventory. */
sealed interface RecommendationBackendStatus {
    data object NotLoaded : RecommendationBackendStatus
    data class E5(val source: RecommendationBackendSource) : RecommendationBackendStatus
    data class Keyword(
        val installedFailure: RecommendationBackendFailure,
        val bundledFailure: RecommendationBackendFailure
    ) : RecommendationBackendStatus
}

/** A selected embedder and its status travel together. */
sealed class LoadedRecommendationBackend {
    abstract val embedder: TextEmbedder
    abstract val status: RecommendationBackendStatus

    class E5 internal constructor(
        override val embedder: TextEmbedder,
        source: RecommendationBackendSource
    ) : LoadedRecommendationBackend() {
        override val status = RecommendationBackendStatus.E5(source)
    }

    class Keyword internal constructor(
        installedFailure: RecommendationBackendFailure,
        bundledFailure: RecommendationBackendFailure
    ) : LoadedRecommendationBackend() {
        override val embedder = KeywordEmbedder()
        override val status = RecommendationBackendStatus.Keyword(installedFailure, bundledFailure)
    }
}

/**
 * Selects the existing installed, bundled, then keyword recovery path.
 * Factories are the external file/native initialization boundary; this
 * module neither opens files nor initializes Android or ONNX Runtime.
 */
class RecommendationBackendLoader(
    private val installedFactory: () -> TextEmbedder?,
    private val bundledFactory: () -> TextEmbedder?
) {
    fun load(): LoadedRecommendationBackend {
        val installed = attempt(installedFactory)
        installed.embedder?.let {
            return LoadedRecommendationBackend.E5(it, RecommendationBackendSource.INSTALLED)
        }
        val bundled = attempt(bundledFactory)
        bundled.embedder?.let {
            return LoadedRecommendationBackend.E5(it, RecommendationBackendSource.BUNDLED)
        }
        return LoadedRecommendationBackend.Keyword(installed.failure, bundled.failure)
    }

    private data class Attempt(
        val embedder: TextEmbedder?,
        val failure: RecommendationBackendFailure
    )

    private fun attempt(factory: () -> TextEmbedder?): Attempt = try {
        Attempt(factory(), RecommendationBackendFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Attempt(null, RecommendationBackendFailure.LOAD_FAILED)
    }
}
