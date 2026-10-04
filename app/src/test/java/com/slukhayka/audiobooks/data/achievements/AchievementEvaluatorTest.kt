package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementEvaluatorTest {
    @Test fun `the first explicit book earns one award only once`() {
        val snapshot = AchievementProgress(explicitBooks = 1)
        assertEquals(listOf("first_book"), AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id })
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(snapshot, setOf("first_book")).map { it.id })
    }
    @Test fun `each real first step earns only its own award`() {
        val cases = listOf(
            AchievementProgress(playbackStarts = 1) to "first_playback",
            AchievementProgress(acceptedReviews = 1) to "first_review",
            AchievementProgress(notInterestedChoices = 1) to "first_not_interested",
            AchievementProgress(searchImports = 1) to "first_search_import",
            AchievementProgress(offlinePlaybackStarts = 1) to "first_offline_playback",
            AchievementProgress(downloadedBooks = 1) to "first_download",
            AchievementProgress(completedBooks = 1) to "first_completion"
        )
        for ((snapshot, id) in cases) {
            assertEquals(id, listOf(id), AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id })
            assertEquals(id, emptyList<String>(), AchievementEvaluator.evaluate(snapshot, setOf(id)).map { it.id })
        }
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id })
    }

    @Test fun `verified hour levels unlock at exactly the five specified thresholds`() {
        val cases = listOf(
            3_600_000L to listOf("hours_1"),
            36_000_000L to listOf("hours_1", "hours_10"),
            360_000_000L to listOf("hours_1", "hours_10", "hours_100"),
            3_600_000_000L to listOf("hours_1", "hours_10", "hours_100", "hours_1000"),
            18_000_000_000L to listOf("hours_1", "hours_10", "hours_100", "hours_1000", "hours_5000")
        )
        for ((millis, expected) in cases) {
            val at = AchievementProgress(verifiedListeningMillis = millis)
            assertEquals(expected, AchievementEvaluator.evaluate(at, emptySet()).map { it.id })
            assertEquals(expected.dropLast(1), AchievementEvaluator.evaluate(at.copy(verifiedListeningMillis = millis - 1), emptySet()).map { it.id })
            assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(at, expected.toSet()).map { it.id })
        }
    }

    @Test fun `unknown or negative evidence does not invent any award`() {
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id })
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(explicitBooks = -1, verifiedListeningMillis = -1), emptySet()).map { it.id })
    }

    @Test fun `hidden definitions are only returned after factual earning`() {
        val hidden = listOf(AchievementDefinition("secret", "hidden", 1, AchievementMetric.COMPLETED_BOOKS, 1, hidden = true))
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(), emptySet(), hidden).map { it.id })
        assertEquals(listOf("secret"), AchievementEvaluator.evaluate(AchievementProgress(completedBooks = 1), emptySet(), hidden).map { it.id })
    }
}
