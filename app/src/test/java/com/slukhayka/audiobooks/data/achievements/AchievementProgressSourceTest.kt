package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementProgressSourceTest {
    @Test fun `declared registry and partial series membership never invent listening or completion`() = runTest {
        val partial = AchievementProgress(
            registeredSourceIds = setOf("soundbooks", "lihtar"),
            knownSeriesMemberships = setOf(AchievementSeriesMembership("cycle", "work-a", 1))
        )
        val source = LocalAchievementProgressSource(MutableStateFlow(partial), MutableStateFlow(emptySet()))
        assertEquals(partial, source.observe().first())
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(source.observe().first(), emptySet()).map { it.id })
    }

    @Test fun `actual persisted event facts fill only the missing progress`() = runTest {
        val aggregates = MutableStateFlow(AchievementProgress(explicitBooks = 2, completedBooks = 4, verifiedListeningMillis = 12_345))
        val facts = MutableStateFlow(setOf(AchievementFact.PLAYBACK_STARTED, AchievementFact.REVIEW_ACCEPTED, AchievementFact.OFFLINE_PLAYBACK_STARTED))
        val source = LocalAchievementProgressSource(aggregates, facts)
        assertEquals(AchievementProgress(explicitBooks = 2, playbackStarts = 1, acceptedReviews = 1, offlinePlaybackStarts = 1, completedBooks = 4, verifiedListeningMillis = 12_345), source.observe().first())
        facts.value = facts.value + AchievementFact.SEARCH_IMPORTED + AchievementFact.BOOK_COMPLETED
        assertEquals(1L, source.observe().first().searchImports)
        assertEquals(4L, source.observe().first().completedBooks)
    }

    @Test fun `empty evidence stays empty without reconstructing past actions`() = runTest {
        val source = LocalAchievementProgressSource(MutableStateFlow(AchievementProgress()), MutableStateFlow(emptySet()))
        assertEquals(AchievementProgress(), source.observe().first())
    }

    @Test fun `full factual snapshot stays intact across a repeated observation`() = runTest {
        val full = AchievementProgress(5, 4, 3, 2, 7, 8, 6, 9, 3_600_000)
        val source = LocalAchievementProgressSource(MutableStateFlow(full), MutableStateFlow(AchievementFact.entries.toSet()))
        assertEquals(full, source.observe().first())
        assertEquals(full, source.observe().first())
    }
}
