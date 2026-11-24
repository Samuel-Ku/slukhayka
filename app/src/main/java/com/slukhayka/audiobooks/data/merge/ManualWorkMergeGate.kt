package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal sealed interface IdleBookAdmission<out T> {
    data class Admitted<T>(val value: T) : IdleBookAdmission<T>
    data object ActiveParticipants : IdleBookAdmission<Nothing>
}

internal sealed interface ManualWorkMergeResource {
    val id: String
    data class Work(override val id: String) : ManualWorkMergeResource
    data class Book(override val id: String) : ManualWorkMergeResource
}

/** One lock/activity registry per live Room instance, shared by every gate wrapper. */
class ManualWorkMergeGate(private val database: AudiobookDatabase) {
    private class State {
        val locks = mutableMapOf<ManualWorkMergeResource, Mutex>()
        val active = mutableMapOf<ManualWorkMergeResource, Int>()
    }

    private val state = synchronized(registries) {
        registries.getOrPut(database) { State() }
    }

    internal fun requireDatabase(expected: AudiobookDatabase) {
        require(database === expected) { "Commands and gate must use the same Room instance" }
    }

    internal fun requireDao(expected: AudiobookDao) {
        require(database.audiobookDao() === expected) { "Merger and gate must use the same Room instance" }
    }

    /** Writers and activity admission acquire the same sorted participant locks as commands. */
    suspend fun <T> withBookWrite(bookIds: Collection<String>, block: suspend () -> T): T =
        locked(bookIds.map { ManualWorkMergeResource.Book(it) }, block)

    internal suspend fun <T> withIdleBooks(
        bookIds: Collection<String>, block: suspend () -> T,
    ): IdleBookAdmission<T> {
        return idle(bookIds.map { ManualWorkMergeResource.Book(it) }, block)
    }

    internal suspend fun <T> withIdentityWrite(
        workIds: Collection<String>, bookIds: Collection<String>, block: suspend () -> T,
    ): T = locked(resources(workIds, bookIds), block)

    internal suspend fun <T> withIdleIdentities(
        workIds: Collection<String>, bookIds: Collection<String>, block: suspend () -> T,
    ): IdleBookAdmission<T> = idle(resources(workIds, bookIds), block)

    private suspend fun <T> idle(
        resources: Collection<ManualWorkMergeResource>, block: suspend () -> T,
    ): IdleBookAdmission<T> {
        val ids = ordered(resources)
        return locked(ids) {
            if (synchronized(state) { ids.any { (state.active[it] ?: 0) > 0 } }) {
                IdleBookAdmission.ActiveParticipants
            } else IdleBookAdmission.Admitted(block())
        }
    }

    suspend fun registerActivity(bookIds: Collection<String>): AutoCloseable =
        register(resources(emptyList(), bookIds))

    internal suspend fun registerIdentityActivity(
        workIds: Collection<String>, bookIds: Collection<String>,
    ): AutoCloseable = register(resources(workIds, bookIds))

    private suspend fun register(resources: Collection<ManualWorkMergeResource>): AutoCloseable {
        val ids = ordered(resources)
        return locked(ids) {
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
        database.workIdentityDao().bookAutoMergeExcluded(bookId) ||
            database.workIdentityDao().autoMergeExcluded(bookId, editionId)

    private suspend fun <T> locked(
        resources: Collection<ManualWorkMergeResource>, block: suspend () -> T,
    ): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val ids = ordered(resources)
        val locks = synchronized(state) { ids.map { state.locks.getOrPut(it) { Mutex() } } }
        val acquired = mutableListOf<Mutex>()
        try {
            for (lock in locks) {
                context.ensureActive()
                lock.lock()
                acquired += lock
                context.ensureActive()
            }
            context.ensureActive()
            return block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    private fun resources(workIds: Collection<String>, bookIds: Collection<String>): List<ManualWorkMergeResource> =
        workIds.map { ManualWorkMergeResource.Work(it) } + bookIds.map { ManualWorkMergeResource.Book(it) }

    private fun ordered(ids: Collection<ManualWorkMergeResource>): List<ManualWorkMergeResource> {
        require(ids.none { it.id.isBlank() }) { "Blank participant identity" }
        return ids.distinct().sortedWith { first, second ->
            val firstKind = if (first is ManualWorkMergeResource.Work) 0 else 1
            val secondKind = if (second is ManualWorkMergeResource.Work) 0 else 1
            firstKind.compareTo(secondKind).takeIf { it != 0 } ?: compareMergeUtf8(first.id, second.id)
        }
    }

    private companion object {
        val registries = WeakHashMap<AudiobookDatabase, State>()
    }
}
