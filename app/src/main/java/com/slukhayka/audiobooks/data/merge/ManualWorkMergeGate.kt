package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** One lock/activity registry per live Room instance, shared by every gate wrapper. */
class ManualWorkMergeGate(private val database: AudiobookDatabase) {
    private class State {
        val locks = mutableMapOf<String, Mutex>()
        val active = mutableMapOf<String, Int>()
    }

    private val state = synchronized(registries) {
        registries.getOrPut(database) { State() }
    }

    internal fun requireDatabase(expected: AudiobookDatabase) {
        require(database === expected) { "Commands and gate must use the same Room instance" }
    }

    /** Writers and activity admission acquire the same sorted participant locks as commands. */
    suspend fun <T> withBookWrite(bookIds: Collection<String>, block: suspend () -> T): T =
        locked(bookIds, requireIdle = false, block)

    internal suspend fun <T> withIdleBooks(bookIds: Collection<String>, block: suspend () -> T): T =
        locked(bookIds, requireIdle = true, block)

    suspend fun registerActivity(bookIds: Collection<String>): AutoCloseable {
        val ids = ordered(bookIds)
        return locked(ids, requireIdle = false) {
            synchronized(state) { ids.forEach { state.active[it] = (state.active[it] ?: 0) + 1 } }
            val closed = AtomicBoolean(false)
            AutoCloseable {
                if (closed.compareAndSet(false, true)) synchronized(state) {
                    ids.forEach { id ->
                        val count = checkNotNull(state.active[id])
                        if (count == 1) state.active.remove(id) else state.active[id] = count - 1
                    }
                }
            }
        }
    }

    /** Scoped activity ownership ensures cancellation cannot strand a registered activity. */
    suspend fun <T> withActivity(bookIds: Collection<String>, block: suspend () -> T): T {
        val lease = registerActivity(bookIds)
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            lease.close()
        }
    }

    suspend fun autoMergeExcluded(bookId: String, editionId: String): Boolean =
        database.workIdentityDao().autoMergeExcluded(bookId, editionId)

    private suspend fun <T> locked(
        bookIds: Collection<String>, requireIdle: Boolean, block: suspend () -> T,
    ): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val ids = ordered(bookIds)
        val locks = synchronized(state) { ids.map { state.locks.getOrPut(it) { Mutex() } } }
        val acquired = mutableListOf<Mutex>()
        try {
            for (lock in locks) {
                context.ensureActive()
                lock.lock()
                acquired += lock
                context.ensureActive()
            }
            if (requireIdle) check(synchronized(state) { ids.none { (state.active[it] ?: 0) > 0 } }) {
                "A participant has active work"
            }
            context.ensureActive()
            return block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    private fun ordered(ids: Collection<String>): List<String> {
        require(ids.none(String::isBlank)) { "Blank participant identity" }
        return ids.distinct().sortedWith(::compareMergeUtf8)
    }

    private companion object {
        val registries = WeakHashMap<AudiobookDatabase, State>()
    }
}
