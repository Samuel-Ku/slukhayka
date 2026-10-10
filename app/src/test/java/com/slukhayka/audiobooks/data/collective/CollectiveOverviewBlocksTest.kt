package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollectiveOverviewBlocksTest {
    private fun block(sourceId: String, kind: CollectiveBlockKind) = CollectiveFeedBlock(
        blockKey = collectiveBlockKey(sourceId, kind), sourceId = sourceId, kind = kind,
        name = "Блок", provenanceUrl = "https://sluhay.com.ua/detail",
        cards = listOf(CollectiveBlockCard(sourceId, "https://sluhay.com.ua/same-work", "Місто", "Автор")),
        fetchedAt = 1L, staleAfter = 2L, version = 1L,
        lastAttempt = CollectiveAttempt(1L, CollectiveAttemptStatus.SUCCESS)
    )

    @Test
    fun `overview preserves all stored stale blocks and cross-block Work repeats`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        val arrivals = block("sluhayua", CollectiveBlockKind.NEW_ARRIVALS)
        val recs = block("sluhayua", CollectiveBlockKind.RECOMMENDATIONS)
        val collection = block("sluhayua", CollectiveBlockKind.COLLECTIONS)
        local.activate(arrivals)
        local.activate(recs)
        local.activate(collection)
        val shown = CollectiveOverviewBlocks(local).read(listOf("sluhayua"))
        assertEquals(listOf(arrivals, recs, collection), shown)
        assertEquals("same Work may repeat in meaningful blocks", listOf("Місто", "Місто", "Місто"),
            shown.flatMap { it.cards }.map { it.title })
    }

    @Test
    fun `one failed persisted read preserves other stored blocks in source order`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        val collection = block("soundbooks", CollectiveBlockKind.COLLECTIONS)
        val arrivals = block("sluhayua", CollectiveBlockKind.NEW_ARRIVALS)
        val recs = block("sluhayua", CollectiveBlockKind.RECOMMENDATIONS)
        local.activate(collection)
        local.activate(arrivals)
        local.activate(recs)
        val unreliable = object : CollectiveFeedBlockStore by local {
            override suspend fun active(blockKey: String): CollectiveFeedBlock? {
                if (blockKey == "soundbooks|RECOMMENDATIONS") throw java.io.IOException("bad snapshot")
                return local.active(blockKey)
            }
        }
        assertEquals(listOf(collection, arrivals, recs),
            CollectiveOverviewBlocks(unreliable).read(listOf("soundbooks", "sluhayua")))
    }

    @Test
    fun `cancelled persisted read propagates and stops later block keys and sources`() {
        for (cancelKey in listOf("sluhayua|NEW_ARRIVALS", "sluhayua|RECOMMENDATIONS")) {
            val requested = mutableListOf<String>()
            val cancellation = kotlinx.coroutines.CancellationException("listener left")
            val local = object : CollectiveFeedBlockStore by InMemoryCollectiveFeedBlockStore() {
                override suspend fun active(blockKey: String): CollectiveFeedBlock? {
                    requested += blockKey
                    if (blockKey == cancelKey) throw cancellation
                    return null
                }
            }
            val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { CollectiveOverviewBlocks(local).read(listOf("sluhayua", "soundbooks")) }
            }
            assertSame(cancellation, thrown)
            val expectedReads = if (cancelKey == "sluhayua|NEW_ARRIVALS") {
                listOf("sluhayua|NEW_ARRIVALS")
            } else {
                listOf("sluhayua|NEW_ARRIVALS", "sluhayua|RECOMMENDATIONS")
            }
            assertEquals("cancellation prevents every later persisted read", expectedReads, requested)
        }
    }
}
