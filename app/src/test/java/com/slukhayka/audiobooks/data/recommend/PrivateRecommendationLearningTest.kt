package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateRecommendationLearningTest {
    @Test
    fun `consent needs three meaningful interactions and a recommendation playback start`() {
        assertFalse(RecommendationConsentPolicy.isEligible(RecommendationConsentPolicy.State(3, 0), 0))
        assertTrue(RecommendationConsentPolicy.isEligible(RecommendationConsentPolicy.State(3, 1), 0))
    }

    @Test
    fun `not now cools down for ninety days and repeats only once`() {
        val day = 86_400_000L
        val declined = RecommendationConsentPolicy.State(3, 1, promptCount = 1, lastDeclinedAt = 10 * day)
        assertFalse(RecommendationConsentPolicy.isEligible(declined, 99 * day))
        assertTrue(RecommendationConsentPolicy.isEligible(declined, 100 * day))
        assertFalse(RecommendationConsentPolicy.isEligible(declined.copy(promptCount = 2), 200 * day))
    }

    @Test
    fun `weekly pseudonym changes by week and is deterministic within a week`() {
        val secret = "not-a-production-secret".toByteArray()
        val first = PrivateRecommendationLearning.serverWeeklyPseudonym(secret, "uid", "2026-W34")
        assertEquals(first, PrivateRecommendationLearning.serverWeeklyPseudonym(secret, "uid", "2026-W34"))
        assertTrue(first != PrivateRecommendationLearning.serverWeeklyPseudonym(secret, "uid", "2026-W35"))
    }

    @Test
    fun `production epoch remains dormant and always requests raw deletion`() {
        val payloads = (1..10).map {
            PrivateRecommendationLearning.WeeklyGradient(1, "e5-int8-v1", List(5) { .1 }, 1, "2026-W34", "weekly-$it")
        }
        val result = PrivateRecommendationLearning.closeWeeklyEpoch(
            payloads,
            RecommendationPersonalization.ScoreWeights(),
            activeOptIns = 100,
            privacyReviewApproved = true,
            admission = PrivateRecommendationLearning.Admission(true, true, true)
        )
        assertNull(result.weights)
        assertEquals(10, result.acceptedPayloads)
        assertTrue(result.discardRawPayloads)
    }

    @Test
    fun `epoch counts distinct weekly ids from its own week only`() {
        val duplicate = PrivateRecommendationLearning.WeeklyGradient(
            1, "model", List(5) { .1 }, 1, "2026-W34", "same"
        )
        val result = PrivateRecommendationLearning.closeWeeklyEpoch(
            payloads = listOf(duplicate, duplicate, duplicate.copy(isoWeek = "2026-W35")),
            current = RecommendationPersonalization.ScoreWeights(),
            activeOptIns = 100,
            privacyReviewApproved = true,
            targetIsoWeek = "2026-W34",
            admission = PrivateRecommendationLearning.Admission(true, true, true)
        )
        assertEquals(1, result.acceptedPayloads)
    }

    @Test
    fun `client gradient is clipped to unit L2 norm`() {
        val clipped = PrivateRecommendationLearning.clipGradient(listOf(2.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(1.0, kotlin.math.sqrt(clipped.sumOf { it * it }), 1e-9)
    }

    @Test
    fun `evaluation update stays a five weight simplex inside bounds`() {
        val updated = PrivateRecommendationLearning.boundedUpdate(
            RecommendationPersonalization.ScoreWeights(),
            listOf(100.0, -100.0, .5, -.5, 0.0)
        )
        // #486: the learned simplex is the PERSONAL block; the popularity
        // component stays fixed and outside the channel.
        val values = listOf(updated.semantic, updated.author, updated.genre, updated.series, updated.freshness)
        assertEquals(1.0 - updated.popularity, values.sum(), 1e-9)
        assertTrue(values.all { it in .02..0.80 })
        val before = listOf(.55, .15, .10, .05, .05)
        assertTrue(values.zip(before).all { (after, old) -> kotlin.math.abs(after - old) <= .0200001 })
        assertEquals(.10, updated.popularity, 1e-9)
    }
}
