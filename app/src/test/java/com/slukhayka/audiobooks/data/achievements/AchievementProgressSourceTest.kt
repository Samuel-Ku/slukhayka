package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // #1174 (друга смуга): the «завершив після покинутого» fact is one of
        // the facts now, so the full fixture carries its field too. Every fact
        // of the enum floors its own field, and a fixture that forgets one reads
        // as "the fold invented a number" — which is exactly what CI caught
        // here when the fact was added without this line. #701's two mechanism
        // facts join the same rule.
        val full = AchievementProgress(
            5, 4, 3, 2, 7, 8, 6, 9, 3_600_000,
            booksFinishedAfterAbandon = 1,
            crossResolves = 1,
            sourceRecoveries = 1
        )
        val source = LocalAchievementProgressSource(MutableStateFlow(full), MutableStateFlow(AchievementFact.entries.toSet()))
        assertEquals(full, source.observe().first())
        assertEquals(full, source.observe().first())
    }

    /**
     * #701 (US44, US45) — the two mechanism awards are FACTS, so the fold is
     * what carries them: nothing in Room proves that a cross-resolve or a
     * source recovery ever happened, and a snapshot without the fact must leave
     * both shut.
     */
    @Test fun `the mechanism facts alone open the resolver and the recoverer`() = runTest {
        val facts = MutableStateFlow(emptySet<AchievementFact>())
        val source = LocalAchievementProgressSource(MutableStateFlow(AchievementProgress()), facts)

        assertTrue(
            "без жодного факту механізмів нагород бути не має",
            earned(source).none { it in setOf("resolver", "recoverer") }
        )
        facts.value = setOf(AchievementFact.CROSS_RESOLVED)
        assertTrue("сам факт крос-резолву відкриває «Резолвера»", "resolver" in earned(source))
        assertFalse("і не відкриває «Відновлювача»", "recoverer" in earned(source))

        facts.value = setOf(AchievementFact.SOURCE_RECOVERED)
        assertTrue("сам факт відновлення відкриває «Відновлювача»", "recoverer" in earned(source))
        assertFalse("і не відкриває «Резолвера»", "resolver" in earned(source))
    }

    /**
     * #1174 (друга смуга, US22) — «Друге дихання»'s second path is a FACT, so
     * the fold is what carries it into the snapshot. Here nothing else happened:
     * one finished book, no second completion, no relisten — the award must stay
     * shut without the fact and open on the fact alone.
     */
    @Test fun `the finished-after-abandon fact alone opens the second wind`() = runTest {
        val aggregates = MutableStateFlow(AchievementProgress(completedBooks = 1))
        val withoutFact = LocalAchievementProgressSource(aggregates, MutableStateFlow(emptySet()))
        val withFact = LocalAchievementProgressSource(
            aggregates,
            MutableStateFlow(setOf(AchievementFact.FINISHED_AFTER_ABANDON))
        )

        assertFalse(
            "завершення книги без позначки «покинуто» другого шляху не дає",
            "second_wind" in earned(withoutFact)
        )
        assertEquals(1L, withFact.observe().first().booksFinishedAfterAbandon)
        assertTrue("сам факт відкриває «Друге дихання»", "second_wind" in earned(withFact))
    }

    private suspend fun earned(source: AchievementProgressSource): List<String> =
        AchievementEvaluator.evaluate(source.observe().first(), emptySet()).map { it.id }
}
