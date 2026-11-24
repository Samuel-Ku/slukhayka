package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementEngineTest {

    /**
     * #1574 — the engine no longer watches the progress tower; it pulls the
     * snapshot explicitly. This fake makes the pull observable so a test can
     * tell "refresh happened" from "a DAO emission happened".
     */
    private class FakeSnapshot(initial: AchievementProgress = AchievementProgress()) : AchievementSnapshot {
        override val snapshot = MutableStateFlow(initial)
        var next: AchievementProgress = initial
        var refreshes: Int = 0
            private set

        override suspend fun refresh(): AchievementProgress {
            refreshes++
            snapshot.value = next
            return next
        }
    }

    @Test fun `a transient local persistence failure does not disable subsequent earning`() = runTest {
        val backing = MemoryAchievementStore()
        var fail = true
        val unavailableOnce = object : AchievementStore by backing {
            override suspend fun awardSilently(
                definitions: List<AchievementDefinition>,
                earnedAt: Long,
                seenAt: Long
            ) {
                if (fail) { fail = false; throw java.io.IOException("temporary database failure") }
                backing.awardSilently(definitions, earnedAt, seenAt)
            }
        }
        var failures = 0
        val source = FakeSnapshot(AchievementProgress(explicitBooks = 1))
        val engine = AchievementEngine(source, unavailableOnce, { failures++ }) { 500L }

        assertEquals(emptyList<EarnedAchievement>(), engine.refresh())
        assertEquals(1, failures)
        // The next explicit pull retries; the first SUCCESSFUL one is the
        // silent backfill (ADR-0063 §7).
        engine.refresh()
        assertEquals(listOf(EarnedAchievement("first_book", 500L, 500L)), engine.earned.first())
    }

    @Test fun `the startup backfill awards but never announces the historical queue`() = runTest {
        val store = MemoryAchievementStore()
        val source = FakeSnapshot(AchievementProgress(explicitBooks = 1))
        val engine = AchievementEngine(source, store) { 700L }
        assertFalse("no backfill happened before the first refresh", engine.backfilled)

        engine.refresh()

        assertTrue("the first refresh completes the backfill", engine.backfilled)
        assertEquals(listOf(EarnedAchievement("first_book", 700L, 700L)), engine.earned.first())
        assertNull(engine.takeNotice())
    }

    @Test fun `a live pull after the backfill still gets its quiet notice`() = runTest {
        val store = MemoryAchievementStore()
        val source = FakeSnapshot(AchievementProgress())
        val engine = AchievementEngine(source, store) { 800L }
        engine.refresh()

        source.next = AchievementProgress(playbackStarts = 1)
        val awarded = engine.refresh()

        assertEquals(listOf(EarnedAchievement("first_playback", 800L)), awarded)
        assertEquals(EarnedAchievement("first_playback", 800L, 800L), engine.takeNotice())
    }

    @Test fun `a legacy unseen award is silenced before any notice can be claimed`() = runTest {
        val store = MemoryAchievementStore()
        val legacy = AchievementCatalog.definitions.first()
        // Written by an older build: earned, never shown.
        store.award(listOf(legacy), earnedAt = 10L)
        val source = FakeSnapshot(AchievementProgress())
        val engine = AchievementEngine(source, store) { 900L }
        assertFalse(engine.backfilled)

        engine.refresh()

        assertTrue(engine.backfilled)
        assertNull("the upgrade queue is history, not news", engine.takeNotice())
        assertEquals(900L, store.earned().first { it.id == legacy.id }.seenAt)
    }

    @Test fun `concurrent evaluators report only the one committed winner`() = runTest {
        val store = MemoryAchievementStore()
        val source = FakeSnapshot(AchievementProgress(explicitBooks = 1))
        val results = (1..20).map { async { AchievementEngine(source, store) { 600L }.evaluate() } }.awaitAll().flatten()
        assertEquals(listOf(EarnedAchievement("first_book", 600L)), results)
    }

    /**
     * #1574 — the boundary the ticket exists for: a progress change that was
     * never pulled earns nothing and moves no UI state. Only an explicit
     * refresh (a real transition) evaluates, and it evaluates ONCE.
     */
    @Test fun `a progress change nobody pulls earns nothing and moves no snapshot`() = runTest {
        val store = MemoryAchievementStore()
        val source = FakeSnapshot(AchievementProgress())
        val engine = AchievementEngine(source, store) { 1230L }
        engine.refresh()
        assertEquals(emptyList<EarnedAchievement>(), engine.earned.first())
        assertEquals(AchievementProgress(), engine.snapshot.value)

        // A DAO write landed, but nothing has pulled it.
        source.next = AchievementProgress(playbackStarts = 1)
        assertEquals(emptyList<EarnedAchievement>(), engine.earned.first())
        assertEquals("the board/title must not move on a write alone", AchievementProgress(), engine.snapshot.value)
        assertEquals(1, source.refreshes)

        // The next real transition pulls exactly once.
        assertEquals(listOf(EarnedAchievement("first_playback", 1230L)), engine.refresh())
        assertEquals(AchievementProgress(playbackStarts = 1), engine.snapshot.value)
        assertEquals(2, source.refreshes)
        assertEquals(listOf(EarnedAchievement("first_playback", 1230L)), engine.earned.first())
    }

    @Test fun `a real award survives a new engine and is announced once`() = runTest {
        val store = MemoryAchievementStore()
        val source = FakeSnapshot(AchievementProgress(explicitBooks = 1))
        val first = AchievementEngine(source, store) { 100L }
        assertEquals(listOf(EarnedAchievement("first_book", 100L)), first.evaluate())
        val restarted = AchievementEngine(source, store) { 200L }
        assertEquals(emptyList<EarnedAchievement>(), restarted.evaluate())
        assertEquals(EarnedAchievement("first_book", 100L, 200L), restarted.takeNotice())
        assertNull(first.takeNotice())
        assertNull(AchievementEngine(source, store) { 300L }.takeNotice())
        assertEquals(listOf(EarnedAchievement("first_book", 100L, 200L)), store.earned())
    }

    /**
     * #1574 — the engine's `snapshot` IS the source's owned StateFlow, so a
     * collector sees exactly what refresh published and nothing in between.
     */
    @Test fun `the engine exposes the owned snapshot, not a live tower`() = runTest {
        val source = FakeSnapshot(AchievementProgress())
        val engine = AchievementEngine(source, MemoryAchievementStore()) { 0L }

        source.next = AchievementProgress(explicitBooks = 3)
        assertEquals(AchievementProgress(), engine.snapshot.value)

        engine.refresh()
        assertEquals(AchievementProgress(explicitBooks = 3), engine.snapshot.value)
    }
}

/** In-memory database boundary, shared by reconstructed engines. */
internal class MemoryAchievementStore : AchievementStore {
    private val mutex = Mutex()
    private val awards = MutableStateFlow<List<EarnedAchievement>>(emptyList())
    private val facts = MutableStateFlow<Set<AchievementFact>>(emptySet())
    override fun observeEarned(): Flow<List<EarnedAchievement>> = awards
    override fun observeFacts(): Flow<Set<AchievementFact>> = facts
    override suspend fun earned(): List<EarnedAchievement> = awards.value
    override suspend fun award(definitions: List<AchievementDefinition>, earnedAt: Long): List<EarnedAchievement> = mutex.withLock {
        val fresh = definitions.filter { definition -> awards.value.none { it.id == definition.id } }.map { EarnedAchievement(it.id, earnedAt) }
        awards.value += fresh
        fresh
    }
    override suspend fun recordFact(fact: AchievementFact) { facts.value += fact }
    val counters = mutableMapOf<String, Long>()
    override suspend fun incrementCounter(key: String) { counters[key] = (counters[key] ?: 0L) + 1L }
    override suspend fun silencePending(ids: Set<String>, seenAt: Long) {
        awards.value = awards.value.map { if (it.id in ids) it.copy(seenAt = seenAt) else it }
    }
    override suspend fun awardSilently(definitions: List<AchievementDefinition>, earnedAt: Long, seenAt: Long) {
        mutex.withLock {
            val fresh = definitions.filter { definition -> awards.value.none { it.id == definition.id } }
                .map { EarnedAchievement(it.id, earnedAt, seenAt) }
            awards.value += fresh
        }
    }
    override suspend fun claimNotice(ids: Set<String>, now: Long): EarnedAchievement? {
        val next = awards.value.firstOrNull { it.id in ids && it.seenAt == null } ?: return null
        awards.value = awards.value.map { if (it.id == next.id) it.copy(seenAt = now) else it }
        return next.copy(seenAt = now)
    }
}
