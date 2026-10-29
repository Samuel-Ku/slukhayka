package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // ADR-0063 §7 — the first successful snapshot is the silent backfill.
        assertEquals(listOf(EarnedAchievement("first_book", 500L, 500L)), engine.earned.first())
    }

    @Test fun `the startup backfill awards but never announces the historical queue`() = runTest {
        val store = MemoryAchievementStore()
        val source = AchievementProgressSource { flowOf(AchievementProgress(explicitBooks = 1)) }
        val engine = AchievementEngine(source, store) { 700L }
        // ADR-0063 §7 — the notice gate reads this flag: nothing may be
        // announced before the historical snapshot has been silenced.
        assertFalse("no backfill happened before start()", engine.backfilled)
        engine.start(backgroundScope)
        runCurrent()
        assertTrue("the first snapshot completes the backfill", engine.backfilled)
        assertEquals(listOf(EarnedAchievement("first_book", 700L, 700L)), engine.earned.first())
        assertNull(engine.takeNotice())
    }

    @Test fun `a live action after the backfill still gets its quiet notice`() = runTest {
        val store = MemoryAchievementStore()
        val source = LocalAchievementProgressSource(flowOf(AchievementProgress()), store.observeFacts())
        val engine = AchievementEngine(source, store) { 800L }
        engine.start(backgroundScope)
        runCurrent()
        store.recordFact(AchievementFact.PLAYBACK_STARTED)
        runCurrent()
        assertEquals(
            EarnedAchievement("first_playback", 800L, 800L),
            engine.takeNotice()
        )
    }

    @Test fun `a legacy unseen award is silenced before any notice can be claimed`() = runTest {
        val store = MemoryAchievementStore()
        val legacy = AchievementCatalog.definitions.first()
        // Written by an older build: earned, never shown.
        store.award(listOf(legacy), earnedAt = 10L)
        val source = AchievementProgressSource { flowOf(AchievementProgress()) }
        val engine = AchievementEngine(source, store) { 900L }
        assertFalse(engine.backfilled)

        engine.start(backgroundScope)
        runCurrent()

        assertTrue(engine.backfilled)
        assertNull("the upgrade queue is history, not news", engine.takeNotice())
        assertEquals(900L, store.earned().first { it.id == legacy.id }.seenAt)
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

    /**
     * #1572 — the UI collects [AchievementEngine.snapshot] from the main
     * dispatcher (`MainViewModel.listenerTitle` is a `stateIn(viewModelScope)`),
     * while the progress chain re-matches the whole own library on every Room
     * invalidation. #1570 measured 1.5–1.8 s of main-thread work per emission,
     * so this pin is about WHERE the transform runs, not what it returns: the
     * upstream flow must execute somewhere other than the collecting dispatcher.
     *
     * The collecting dispatcher is a real single thread named after the test, so
     * the assertion fails if the transform leaks onto it (the pre-#1572 code ran
     * the whole chain in the collector's context).
     */
    @Test fun `the progress transform never runs on the collecting main dispatcher`() {
        val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "t1572-test-main")
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            val threads = Collections.synchronizedList(mutableListOf<String>())
            val source = AchievementProgressSource {
                flow {
                    threads += Thread.currentThread().name
                    emit(AchievementProgress(explicitBooks = 1))
                }
            }
            val engine = AchievementEngine(source, MemoryAchievementStore()) { 0L }
            val snapshot = runBlocking {
                withContext(Dispatchers.Main) { engine.snapshot.first() }
            }
            assertEquals(1L, snapshot.explicitBooks)
            assertTrue(
                "the whole progress transform ran on the collecting dispatcher: $threads",
                threads.none { name -> name == "t1572-test-main" }
            )
        } finally {
            Dispatchers.resetMain()
            mainDispatcher.close()
        }
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
    /** #1173 (T9) — durable, monotonic counters; the same contract as Room. */
    val counters = mutableMapOf<String, Long>()
    override suspend fun incrementCounter(key: String) { counters[key] = (counters[key] ?: 0L) + 1L }
    override suspend fun claimNotice(knownIds: Set<String>, seenAt: Long): EarnedAchievement? = mutex.withLock {
        val row = awards.value.firstOrNull { it.seenAt == null && it.id in knownIds } ?: return@withLock null
        val claimed = row.copy(seenAt = seenAt)
        awards.value = awards.value.map { if (it.id == row.id) claimed else it }
        claimed
    }
    override suspend fun silencePending(knownIds: Set<String>, seenAt: Long) {
        mutex.withLock {
            awards.value = awards.value.map {
                if (it.seenAt == null && it.id in knownIds) it.copy(seenAt = seenAt) else it
            }
        }
    }
}
