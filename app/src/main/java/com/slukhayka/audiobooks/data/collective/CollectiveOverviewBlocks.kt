package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Reads persisted overview blocks; source refresh belongs to its explicit owner. */
class CollectiveOverviewBlocks(
    private val store: CollectiveFeedBlockStore
) {
    /** Observes local commits in source/kind order, retaining each key's last good payload. */
    fun observe(sourceIds: List<String> = collectiveBlockSources().map { it.id }): Flow<List<CollectiveFeedBlock>> =
        flow {
            val keys = sourceIds.distinct().flatMap { sourceId ->
                CollectiveBlockKind.entries.map { kind -> collectiveBlockKey(sourceId, kind) }
            }
            val lastGood = mutableMapOf<String, CollectiveFeedBlock>()
            store.observeChanges(keys).collect {
                for (key in keys) {
                    currentCoroutineContext().ensureActive()
                    val block = try {
                        store.active(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (block != null && block.blockKey == key &&
                        collectiveBlockKey(block.sourceId, block.kind) == key && block.cards.isNotEmpty()
                    ) {
                        lastGood[key] = block
                    }
                }
                currentCoroutineContext().ensureActive()
                emit(keys.mapNotNull { lastGood[it] })
            }
        }.distinctUntilChanged()

    suspend fun read(sourceIds: List<String> = collectiveBlockSources().map { it.id }): List<CollectiveFeedBlock> =
        sourceIds.flatMap { sourceId ->
            CollectiveBlockKind.entries.mapNotNull { kind ->
                val key = collectiveBlockKey(sourceId, kind)
                try {
                    store.active(key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
}
