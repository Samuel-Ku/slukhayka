package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #483 — the model-mode projection: installed → full, downloading → in
 * progress, failed/absent → simplified (always visible, never silent).
 */
class RecommendationModelPolicyTest {

    @Test
    fun `installed is the full mode`() {
        val mode = RecommendationModelPolicy.mode(EmbeddingModelState.Installed)
        assertEquals(RecommendationModelMode.FULL, mode)
        assertFalse(RecommendationModelPolicy.isSimplifiedVisible(mode))
    }

    @Test
    fun `downloading and absent and failed all surface a simplified state`() {
        val downloading = RecommendationModelPolicy.mode(EmbeddingModelState.Downloading(0.4f))
        val absent = RecommendationModelPolicy.mode(EmbeddingModelState.NotInstalled)
        val failed = RecommendationModelPolicy.mode(EmbeddingModelState.Failed("boom"))

        assertEquals(RecommendationModelMode.DOWNLOADING, downloading)
        assertEquals(RecommendationModelMode.SIMPLIFIED, absent)
        assertEquals(RecommendationModelMode.FAILED, failed)
        assertTrue(RecommendationModelPolicy.isSimplifiedVisible(downloading))
        assertTrue(RecommendationModelPolicy.isSimplifiedVisible(absent))
        assertTrue(RecommendationModelPolicy.isSimplifiedVisible(failed))
    }
}
