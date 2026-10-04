package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            // #700 — a first step earns its own award. For `completedBooks = 1`
            // the catalogue ALSO opens the first rung of the book ladder
            // (`books_1`), and that is intended, not duplication: the spec asks
            // for both «перша завершена книга» (story 8) and a ladder that
            // starts at 1 (story 18). So this asserts the first-step award is
            // PRESENT rather than that it is the only one.
            val earned = AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }
            assertTrue("$id мусить бути серед виданих: $earned", id in earned)
            assertTrue(
                "повторна видача не має нічого додавати",
                AchievementEvaluator.evaluate(snapshot, earned.toSet()).map { it.id }.isEmpty()
            )
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

    /**
     * #700 (T2) — the BOOK path. Every level unlocks at EXACTLY its threshold
     * and not one book earlier, which is the whole point of a level ladder: an
     * off-by-one here would hand a listener "10 books" at nine.
     *
     * Filled with real numbers rather than a count of loops, so a threshold
     * typed wrong in the catalogue fails here instead of shipping.
     */
    @Test fun `every book level unlocks at exactly its threshold`() {
        val expected = listOf(1L to "books_1", 5L to "books_5", 10L to "books_10", 25L to "books_25",
            50L to "books_50", 100L to "books_100", 250L to "books_250", 500L to "books_500")

        for ((threshold, id) in expected) {
            val justBefore = AchievementEvaluator.evaluate(
                AchievementProgress(completedBooks = threshold - 1), emptySet()
            ).map { it.id }
            assertFalse(
                "$id не має відкриватись на ${threshold - 1} книгах",
                id in justBefore
            )
            val at = AchievementEvaluator.evaluate(
                AchievementProgress(completedBooks = threshold), emptySet()
            ).map { it.id }
            assertTrue("$id мусить відкритись на $threshold книгах", id in at)
        }
    }

    /** A repeated evaluation never re-awards what is already earned. */
    @Test fun `an already earned book level is not awarded twice`() {
        val snapshot = AchievementProgress(completedBooks = 10)
        val once = AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }
        val twice = AchievementEvaluator.evaluate(snapshot, once.toSet()).map { it.id }

        assertTrue("books_10 мусить бути в першій видачі", "books_10" in once)
        assertTrue("повторна видача не має нічого додавати", twice.isEmpty())
    }
}
