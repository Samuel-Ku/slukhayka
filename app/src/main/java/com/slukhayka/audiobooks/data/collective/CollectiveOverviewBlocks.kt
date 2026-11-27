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
            val lastGood = mutableMapOf<String, CollectiveFeedBlock>()
            store.observeSourceChanges(sourceIds.distinct()).collect {
                val keys = persistedKeys(sourceIds)
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
                        parseCollectiveBlockKey(key)?.let { it.sourceId == block.sourceId && it.kind == block.kind } == true && block.cards.isNotEmpty()
                    ) {
                        lastGood[key] = block
                    }
                }
                currentCoroutineContext().ensureActive()
                emit(ordered(sourceIds, lastGood.values.toList()))
            }
        }.distinctUntilChanged()

    suspend fun read(sourceIds: List<String> = collectiveBlockSources().map { it.id }): List<CollectiveFeedBlock> =
        persistedKeys(sourceIds).mapNotNull { key ->
            try {
                store.active(key)?.takeIf { block ->
                    val ref = parseCollectiveBlockKey(key)
                    block.blockKey == key && ref != null && ref.sourceId == block.sourceId && ref.kind == block.kind &&
                        block.cards.isNotEmpty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun persistedKeys(sourceIds: List<String>): List<String> =
        sourceIds.distinct().flatMap { sourceId ->
            CollectiveBlockKind.entries.flatMap { kind ->
                val legacy = collectiveBlockKey(sourceId, kind)
                val discovered = try {
                    store.keys(sourceId, kind)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                (listOf(legacy) + discovered).distinct().sorted()
            }
        }

    private fun ordered(sourceIds: List<String>, blocks: List<CollectiveFeedBlock>): List<CollectiveFeedBlock> =
        blocks.sortedWith(compareBy<CollectiveFeedBlock> { sourceIds.indexOf(it.sourceId) }
            .thenBy { it.kind.ordinal }.thenBy { it.blockKey })
}
