package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecommendationFullCatalogEvalTest {
    @Test
    fun `held out Work is ranked from remaining signals against the whole catalog`() {
        val books = listOf("a", "b", "c", "d").map { RecommendationEngine.Candidate(it, it) }
        val vectors = mapOf(
            "a" to floatArrayOf(1f, 0f),
            "b" to floatArrayOf(0f, 1f),
            "c" to floatArrayOf(0f, 1f),
            "d" to floatArrayOf(1f, 1f)
        )
        // Fold a: b+c put d ahead of a. Fold b: a+c put d ahead of b.
        // Fold c: a+b put d ahead of c. Training Works are excluded.
        // The former inverted query=held-out method instead finds b/c.
        val result = RecommendationEval.evaluateLeaveOneOut(
            completionCohorts = listOf(listOf("a", "b", "c")),
            candidates = books,
            semanticVectors = vectors,
            baselineVectors = vectors,
            k = 1
        )
        assertEquals(0.0, result.report.semanticRecallAtK, 0.0)
        assertEquals(0.0, result.report.semanticNdcgAtK, 0.0)
        assertFalse(result.report.semanticWins)
        assertEquals(3, result.folds.size)
        assertEquals(listOf(2, 2, 2), result.folds.map { it.candidateCount })
    }
    @Test(expected = IllegalArgumentException::class)
    fun `degenerate semantic backend cannot silently receive a gate decision`() {
        val books = listOf("a", "b").map { RecommendationEngine.Candidate(it, it) }
        RecommendationEval.evaluateLeaveOneOut(
            listOf(listOf("a", "b")), books,
            mapOf("a" to floatArrayOf(0f, 0f), "b" to floatArrayOf(1f, 0f)),
            mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(1f, 0f)), 1
        )
    }

    @Test
    fun `better ordering without more held-out Works in top K cannot pass the scale gate`() {
        val result = RecommendationEval.LeaveOneOutReport(
            RecommendationEval.Report(.5, .8, .5, .6), emptyList()
        )
        assertFalse(result.passesGate)
    }

    @Test
    fun `single relevant held-out rank has independently calculated discounted gain`() {
        val books = listOf("a", "b", "c").map { RecommendationEngine.Candidate(it, it) }
        val vectors = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(.8f, .6f), "c" to floatArrayOf(.8f, .6f))
        val result = RecommendationEval.evaluateLeaveOneOut(listOf(listOf("a", "b")), books, vectors, vectors, 2)
        assertEquals(listOf(2, 1), result.folds.map { it.semanticRank })
        assertEquals(1.0, result.report.semanticRecallAtK, 0.0)
        assertEquals((1.0 / (kotlin.math.ln(3.0) / kotlin.math.ln(2.0)) + 1.0) / 2.0,
            result.report.semanticNdcgAtK, 1e-12)
    }
    @Test
    fun `a competitor beyond forty candidates still changes the exact top K`() {
        val books = (listOf("a", "b") + (0 until 100).map { "c$it" } + "zzz").map { RecommendationEngine.Candidate(it, it) }
        val vectors = books.associate { it.id to when (it.id) {
            "a" -> floatArrayOf(.1f, 1f)
            "b", "zzz" -> floatArrayOf(0f, 1f)
            else -> floatArrayOf(1f, 0f)
        } }
        val result = RecommendationEval.evaluateLeaveOneOut(listOf(listOf("a", "b")), books, vectors, vectors, 1)
        assertEquals(listOf("zzz"), result.folds.first().semanticTopIds)
        assertEquals(102, result.folds.first().candidateCount)
    }

}
