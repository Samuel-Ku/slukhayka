package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.listening.ListeningObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App-owned FIFO. Immutable observations update the one Listening State total,
 * surviving player release.
 *
 * #1173 (T9): the same queue carries the listening observations (which write
 * the day row and the session) and the durable counters, so everything the
 * player observed is persisted in ONE order — a stop cannot overtake the
 * interval that preceded it.
 */
class AchievementRecorder(scope: CoroutineScope, private val store: AchievementStore,
    private val recordListeningTime: suspend (ListeningObservation) -> Unit, private val onFailure: (Throwable) -> Unit = {}) {
    private sealed interface Write {
        data class Observation(val observation: ListeningObservation) : Write
        data class Fact(val fact: AchievementFact) : Write
        data class Counter(val key: String) : Write
    }
    private val writes = Channel<Write>(Channel.UNLIMITED)
    init {
        scope.launch {
            for (write in writes) {
                while (isActive) {
                    try {
                        when (write) {
                            is Write.Observation -> recordListeningTime(write.observation)
                            is Write.Fact -> store.recordFact(write.fact)
                            is Write.Counter -> store.incrementCounter(write.key)
                        }
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // All three production writes are atomic Room transactions: failure rolls back.
                        onFailure(failure)
                        delay(1000L)
                    }
                }
            }
        }
    }
    /** A zero-length interval reports nothing; a stop always travels. */
    fun recordObservation(observation: ListeningObservation) {
        if (observation is ListeningObservation.Played && observation.millis <= 0L) return
        check(writes.trySend(Write.Observation(observation)).isSuccess)
    }

    /**
     * #1173 (T9) — a durable counter step, queued like a fact: the arm already
     * happened, so the write must not wait for a retrying database.
     */
    fun captureCounter(key: String) { check(writes.trySend(Write.Counter(key)).isSuccess) }
    fun captureFact(fact: AchievementFact) { check(writes.trySend(Write.Fact(fact)).isSuccess) }
    /** Acceptance is already successful; queue it without waiting for a retrying database. */
    suspend fun recordFact(fact: AchievementFact) { captureFact(fact) }
}
