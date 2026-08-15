package com.example.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [RecommendationEval] (spec-19 US11 / Q6 gate). A
 * semantic embedder that clusters by topic — even when completions express
 * the topic with different words — must beat the literal keyword baseline;
 * an identical embedder ties itself; the whole run is deterministic.
 */
class RecommendationEvalTest {

    /**
     * A fake semantic embedder: it recognises a book's *topic* through a
     * synonym list, so completions like «кобзар том перший» and «шевченко
     * поезії» (no shared literal tokens) still land on the same vector.
     * The keyword baseline cannot see through synonyms — that is the Q6 gap.
     */
    private class TopicEmbedder : TextEmbedder {
        private val familyA = listOf("кобзар", "шевченко", "думи", "народні", "вірші", "збірник")
        private val familyB = listOf("лісова", "пісня", "лукаш", "казка", "драма")

        override fun embed(text: String): FloatArray {
            val lowered = text.lowercase()
            val topic = when {
                familyA.any { lowered.contains(it) } -> "familyA"
                familyB.any { lowered.contains(it) } -> "familyB"
                else -> lowered
            }
            val vector = FloatArray(32)
            for (c in topic) vector[c.code % 32] += 1f
            val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
            if (norm == 0.0) return vector
            return FloatArray(32) { (vector[it] / norm).toFloat() }
        }
    }

    private val keyword = KeywordEmbedder()
    private val topic = TopicEmbedder()

    /**
     * Two topic families. Family A's titles deliberately share no literal
     * tokens with each other (topic expressed via synonyms), so the keyword
     * baseline cannot group the completions; family A also has distractor
     * books that DO share keywords («кобзар збірник інший») to bait the
     * baseline into ranking irrelevant books on top.
     */
    private val catalogue: Map<String, String> = buildMap {
        put("a1", "кобзар том перший")
        put("a2", "шевченко поезії")
        put("a3", "думи народні")
        put("a4", "українська класика")
        put("a5", "вірші збірник")
        put("a6", "кобзар збірник інший")
        put("a7", "шевченко вірші том")
        put("a8", "думи кобзар перший")
        put("a9", "народні поезії збірник")
        put("a10", "українська класика том")
        for (i in 1..10) put("b$i", "лісова пісня частина $i")
    }

    @Test
    fun `topic embedder beats the keyword baseline on completions`() {
        val completions = listOf("a1", "a2", "a3", "a4", "a5")
        val semantic = RecommendationEval.evaluate(
            completions = completions,
            candidates = catalogue,
            semanticEmbedder = topic,
            baselineEmbedder = keyword,
            distractorCount = 20,
            k = 20,
            seed = 1L
        )
        assertTrue(
            "semantic recall (${semantic.semanticRecallAtK}) must beat baseline (${semantic.baselineRecallAtK})",
            semantic.semanticRecallAtK > semantic.baselineRecallAtK
        )
        assertTrue("semanticWins must hold", semantic.semanticWins)
        assertTrue(semantic.semanticNdcgAtK > semantic.baselineNdcgAtK)
    }

    @Test
    fun `an identical embedder ties itself - the gate does not claim a win`() {
        val completions = listOf("a1", "a2", "a3", "a4")
        val report = RecommendationEval.evaluate(
            completions = completions,
            candidates = catalogue,
            semanticEmbedder = keyword,
            baselineEmbedder = keyword,
            distractorCount = 20,
            k = 20,
            seed = 2L
        )
        assertEquals(report.semanticRecallAtK, report.baselineRecallAtK, 1e-9)
        assertEquals(report.semanticNdcgAtK, report.baselineNdcgAtK, 1e-9)
        assertTrue("a tie is not a win", !report.semanticWins)
    }

    @Test
    fun `fewer than two completions cannot be evaluated`() {
        val report = RecommendationEval.evaluate(
            completions = listOf("a1"),
            candidates = catalogue,
            semanticEmbedder = topic,
            baselineEmbedder = keyword
        )
        assertEquals(0.0, report.semanticRecallAtK, 1e-9)
        assertEquals(0.0, report.baselineRecallAtK, 1e-9)
    }

    @Test
    fun `evaluation is deterministic under a fixed seed`() {
        fun run() = RecommendationEval.evaluate(
            completions = listOf("b1", "b2", "b3", "b4", "b5"),
            candidates = catalogue,
            semanticEmbedder = topic,
            baselineEmbedder = keyword,
            distractorCount = 20,
            k = 20,
            seed = 7L
        )
        val first = run()
        val second = run()
        assertEquals(first.semanticRecallAtK, second.semanticRecallAtK, 1e-9)
        assertEquals(first.semanticNdcgAtK, second.semanticNdcgAtK, 1e-9)
    }
}
