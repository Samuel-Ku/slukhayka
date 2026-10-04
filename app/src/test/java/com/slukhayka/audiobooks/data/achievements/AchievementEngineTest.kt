package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementEngineTest {
    @Test fun `a transient local persistence failure does not disable subsequent earning`() = runTest {
        val backing = MemoryAchievementStore()
        var fail = true
        val unavailableOnce = object : AchievementStore by backing {
            override suspend fun award(definitions: List<AchievementDefinition>, earnedAt: Long): List<EarnedAchievement> {
                if (fail) { fail = false; throw java.io.IOException("temporary database failure") }
                return backing.award(definitions, earnedAt)
            }
        }
        val source = AchievementProgressSource { flowOf(AchievementProgress(explicitBooks = 1)) }
        val engine = AchievementEngine(source, unavailableOnce) { 500L }
        engine.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(EarnedAchievement("first_book", 500L)), engine.earned.first())
    }

    @Test fun `concurrent evaluators report only the one committed winner`() = runTest {
        val store = MemoryAchievementStore()
        val source = AchievementProgressSource { flowOf(AchievementProgress(explicitBooks = 1)) }
        val results = (1..20).map { async { AchievementEngine(source, store) { 600L }.evaluate() } }.awaitAll().flatten()
        assertEquals(listOf(EarnedAchievement("first_book", 600L)), results)
    }

    @Test fun `production watcher earns after new actual facts without a UI polling call`() = runTest {
        val store = MemoryAchievementStore()
        val source = LocalAchievementProgressSource(flowOf(AchievementProgress()), store.observeFacts())
        val engine = AchievementEngine(source, store) { 123L }
        engine.start(backgroundScope)
        runCurrent()
        assertEquals(emptyList<EarnedAchievement>(), engine.earned.first())
        store.recordFact(AchievementFact.PLAYBACK_STARTED)
        runCurrent()
        assertEquals(listOf(EarnedAchievement("first_playback", 123L)), engine.earned.first())
        store.recordFact(AchievementFact.PLAYBACK_STARTED)
        runCurrent()
        assertEquals(listOf(EarnedAchievement("first_playback", 123L)), engine.earned.first())
    }

    @Test fun `a real award survives a new engine and is announced once`() = runTest {
        val store = MemoryAchievementStore()
        val source = AchievementProgressSource { flowOf(AchievementProgress(explicitBooks = 1)) }
        val first = AchievementEngine(source, store) { 100L }
        assertEquals(listOf(EarnedAchievement("first_book", 100L)), first.evaluate())
        val restarted = AchievementEngine(source, store) { 200L }
        assertEquals(emptyList<EarnedAchievement>(), restarted.evaluate())
        assertEquals(EarnedAchievement("first_book", 100L, 200L), restarted.takeNotice())
        assertNull(first.takeNotice())
        assertNull(AchievementEngine(source, store) { 300L }.takeNotice())
        assertEquals(listOf(EarnedAchievement("first_book", 100L, 200L)), store.earned())
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
    override suspend fun claimNotice(knownIds: Set<String>, seenAt: Long): EarnedAchievement? = mutex.withLock {
        val row = awards.value.firstOrNull { it.seenAt == null && it.id in knownIds } ?: return@withLock null
        val claimed = row.copy(seenAt = seenAt)
        awards.value = awards.value.map { if (it.id == row.id) claimed else it }
        claimed
    }
}
