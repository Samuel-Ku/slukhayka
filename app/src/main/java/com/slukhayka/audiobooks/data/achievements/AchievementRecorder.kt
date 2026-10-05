package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** App-owned FIFO. Immutable intervals update the one Listening State total, surviving player release. */
class AchievementRecorder(scope: CoroutineScope, private val store: AchievementStore,
    private val recordListeningTime: suspend (Long) -> Unit, private val onFailure: (Throwable) -> Unit = {}) {
    private sealed interface Write {
        data class Duration(val millis: Long) : Write
        data class Fact(val fact: AchievementFact) : Write
    }
    private val writes = Channel<Write>(Channel.UNLIMITED)
    init {
        scope.launch {
            for (write in writes) {
                while (isActive) {
                    try {
                        when (write) {
                            is Write.Duration -> recordListeningTime(write.millis)
                            is Write.Fact -> store.recordFact(write.fact)
                        }
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // Both production writes are atomic Room transactions: failure rolls back.
                        onFailure(failure)
                        delay(1000L)
                    }
                }
            }
        }
    }
    fun recordDuration(millis: Long) { if (millis > 0L) check(writes.trySend(Write.Duration(millis)).isSuccess) }
    fun captureFact(fact: AchievementFact) { check(writes.trySend(Write.Fact(fact)).isSuccess) }
    /** Acceptance is already successful; queue it without waiting for a retrying database. */
    suspend fun recordFact(fact: AchievementFact) { captureFact(fact) }
}
