package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecommendationEvalVectorCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `resume preserves a verified vector computed before backend interruption`() {
        val dir = temporary.newFolder()
        val books = listOf("a", "b").map { RecommendationEngine.Candidate(it, it) }
        val interrupted = object : TextEmbedder {
            override fun embed(text: String): FloatArray {
                if (text == "b") error("Backend interruption")
                return floatArrayOf(1f, 0f)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            RecommendationEvalVectorCache.loadOrCompute(dir, "frozen-model", books, 2, interrupted)
        }
        val resumed = RecommendationEvalVectorCache.loadOrCompute(dir, "frozen-model", books, 2,
            object : TextEmbedder { override fun embed(text: String) = floatArrayOf(0f, 1f) })
        assertArrayEquals(floatArrayOf(1f, 0f), resumed.getValue("a"), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), resumed.getValue("b"), 0f)
    }
    @Test
    fun `a corrupted completed journal cannot silently recompute plausible vectors`() {
        val dir = temporary.newFolder()
        val books = listOf(RecommendationEngine.Candidate("a", "Alpha"))
        val valid = object : TextEmbedder { override fun embed(text: String) = floatArrayOf(1f, 0f) }
        RecommendationEvalVectorCache.loadOrCompute(dir, "model", books, 2, valid)
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            RecommendationEvalVectorCache.loadOrCompute(dir, "model", books, 2,
                object : TextEmbedder { override fun embed(text: String): FloatArray = error("Corruption must fail before inference") })
        }
    }
    @Test
    fun `an unfinished tail can be discarded without embedding verified Works again`() {
        val dir = temporary.newFolder()
        val books = listOf(RecommendationEngine.Candidate("a", "Alpha"))
        RecommendationEvalVectorCache.loadOrCompute(dir, "model", books, 2,
            object : TextEmbedder { override fun embed(text: String) = floatArrayOf(1f, 0f) })
        val file = dir.listFiles()!!.single()
        val completeLength = file.length()
        file.appendBytes(byteArrayOf(0))
        val vectors = RecommendationEvalVectorCache.loadOrCompute(dir, "model", books, 2,
            object : TextEmbedder { override fun embed(text: String): FloatArray = error("Verified vector must be reused") })
        assertArrayEquals(floatArrayOf(1f, 0f), vectors.getValue("a"), 0f)
        assertEquals(completeLength, file.length())
    }
    @Test
    fun `changed source text or model identity must invalidate cached vectors`() {
        val dir = temporary.newFolder()
        val original = listOf(RecommendationEngine.Candidate("a", "Alpha"))
        val changed = listOf(RecommendationEngine.Candidate("a", "Beta"))
        val first = object : TextEmbedder { override fun embed(text: String) = floatArrayOf(1f, 0f) }
        val second = object : TextEmbedder { override fun embed(text: String) = floatArrayOf(0f, 1f) }
        RecommendationEvalVectorCache.loadOrCompute(dir, "model-1", original, 2, first)
        val newText = RecommendationEvalVectorCache.loadOrCompute(dir, "model-1", changed, 2, second)
        assertArrayEquals(floatArrayOf(0f, 1f), newText.getValue("a"), 0f)
        val newModel = RecommendationEvalVectorCache.loadOrCompute(dir, "model-2", changed, 2, first)
        assertArrayEquals(floatArrayOf(1f, 0f), newModel.getValue("a"), 0f)
    }

}
