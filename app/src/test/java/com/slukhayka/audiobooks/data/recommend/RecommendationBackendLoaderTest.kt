package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationBackendLoaderTest {
    @Test
    fun `installed files with failed E5 loading show the actual simplified backend`() {
        val loaded = RecommendationBackendLoader(
            installedFactory = { throw IllegalStateException("neutral initialization failure") },
            bundledFactory = { null }
        ).load()

        assertTrue(loaded.embedder is KeywordEmbedder)
        assertEquals("keyword-bow-v1", loaded.embedder.cacheContext?.identity)
        assertEquals(512, loaded.embedder.cacheContext?.dimension)
        assertTrue(loaded.status is RecommendationBackendStatus.Keyword)

        assertEquals(
            RecommendationModelMode.SIMPLIFIED,
            RecommendationModelPolicy.mode(EmbeddingModelState.Installed, loaded.status)
        )
    }
    @Test
    fun `available bundled backend shows full mode without installed files`() {
        // External native factory boundary only; no ONNX or embed invocation.
        val bundled = object : TextEmbedder {
            override val cacheContext = EmbeddingContext("test-bundled-factory-v1", 384)
            override fun embed(text: String): FloatArray =
                error("This external factory fixture must not run inference")
        }
        val loaded = RecommendationBackendLoader(
            installedFactory = { null },
            bundledFactory = { bundled }
        ).load()

        assertSame(bundled, loaded.embedder)
        assertEquals(
            RecommendationBackendStatus.E5(RecommendationBackendSource.BUNDLED),
            loaded.status
        )
        assertEquals("test-bundled-factory-v1", loaded.embedder.cacheContext?.identity)
        assertEquals(384, loaded.embedder.cacheContext?.dimension)

        assertEquals(
            RecommendationModelMode.FULL,
            RecommendationModelPolicy.mode(EmbeddingModelState.NotInstalled, loaded.status)
        )
    }

    @Test
    fun `installed inventory with not loaded backend shows not loaded mode`() {
        assertEquals(
            RecommendationModelMode.NOT_LOADED,
            RecommendationModelPolicy.mode(
                EmbeddingModelState.Installed,
                RecommendationBackendStatus.NotLoaded
            )
        )
    }
}
