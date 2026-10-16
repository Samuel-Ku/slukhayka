package com.slukhayka.audiobooks.data.recommend

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `cancelled installed backend loading propagates the same cancellation`() {
        val cancellation = CancellationException("neutral installed loading cancellation")
        val loader = RecommendationBackendLoader(
            installedFactory = { throw cancellation },
            bundledFactory = { null }
        )

        val propagated = try {
            loader.load()
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(
            "R1_RUNTIME_CANCELLATION_MUST_PROPAGATE_INSTALLED_FACTORY_EXCEPTION",
            cancellation,
            propagated
        )
    }

    @Test
    fun `installed backend linkage failure selects the actual simplified backend`() {
        val linkage = UnsatisfiedLinkError("neutral native loading failure")
        val loader = RecommendationBackendLoader(
            installedFactory = { throw linkage },
            bundledFactory = { null }
        )
        var escaped: UnsatisfiedLinkError? = null
        val loaded = try {
            loader.load()
        } catch (error: UnsatisfiedLinkError) {
            escaped = error
            null
        }

        assertNull("R1_RUNTIME_NATIVE_LINKAGE_FAILURE_MUST_SELECT_RECOVERY_BACKEND", escaped)
        val backend = requireNotNull(loaded)
        assertTrue(backend.embedder is KeywordEmbedder)
        assertEquals(
            RecommendationBackendStatus.Keyword(
                RecommendationBackendFailure.LOAD_FAILED,
                RecommendationBackendFailure.UNAVAILABLE
            ),
            backend.status
        )
        assertEquals(
            RecommendationModelMode.SIMPLIFIED,
            RecommendationModelPolicy.mode(EmbeddingModelState.Installed, backend.status)
        )
    }
}
