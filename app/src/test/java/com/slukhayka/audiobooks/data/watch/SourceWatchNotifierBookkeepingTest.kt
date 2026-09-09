package com.slukhayka.audiobooks.data.watch

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADR-0037 §6 (spec-49 T4) — the watch's bookkeeping seams without Android:
 * the verdict feed (the T2b tap seam) evaluates zero-request scans exactly
 * like the union scan, an appearance persists its seen-state so the same
 * source never notifies twice (including across store re-creations), and an
 * unpersisted result can repeat. The notification plumbing itself is the
 * one seam left to a device check.
 */
class SourceWatchNotifierBookkeepingTest {

    private fun card(mergeKey: String, vararg sources: GlobalSearchSource) =
        GlobalSearchResult(
            title = "Книга",
            author = "Автор",
            narrator = "",
            mergeKey = mergeKey,
            coverImageUrl = null,
            sources = sources.toList()
        )

    private fun source(sourceId: String, url: String) =
        GlobalSearchSource(sourceId = sourceId, sourceName = sourceId, url = url)

    private val key = "книга|автор"

    @Test
    fun `verdict feed is an appearance with the verdict persisted`() = runBlocking {
        val store = InMemoryWatchStore()
        store.watch(key, "work-1")
        val state = SourceWatchPolicy.SeenState(
            watched = store.watched.value,
            seen = mapOf(key to store.seenFor(key))
        )

        val appearance = SourceWatchPolicy.evaluate(
            state,
            emptyList(),
            mappingVerdicts = listOf(SourceWatchPolicy.MappingVerdict(key, "sluhayua"))
        )!!

        assertEquals(setOf("sluhayua"), appearance.appearedSourceIds)
        assertEquals(1, appearance.verdicts.size)

        // What notifyMappingVerdict persists after the notification goes out:
        store.markSeen(key, appearance.appearedByMergeKey.getValue(key))
        assertEquals(setOf("sluhayua"), store.seenFor(key))
    }

    @Test
    fun `persisted verdict does not repeat - unpersisted can`() = runBlocking {
        val store = InMemoryWatchStore()
        store.watch(key, "work-1")
        val verdicts = listOf(SourceWatchPolicy.MappingVerdict(key, "sluhayua"))

        fun state() = SourceWatchPolicy.SeenState(
            watched = store.watched.value,
            seen = mapOf(key to store.seenFor(key))
        )

        val first = SourceWatchPolicy.evaluate(state(), emptyList(), mappingVerdicts = verdicts)!!
        store.markSeen(key, first.appearedByMergeKey.getValue(key))

        // Re-delivered memoized verdict inside its TTL — no second appearance.
        assertNull(SourceWatchPolicy.evaluate(state(), emptyList(), mappingVerdicts = verdicts))

        // A result that was never persisted (notification dropped) repeats.
        val dropped = SourceWatchPolicy.evaluate(
            SourceWatchPolicy.SeenState(watched = mapOf(key to "work-1"), seen = emptyMap()),
            emptyList(),
            mappingVerdicts = verdicts
        )
        assertNotNull(dropped)
    }

    @Test
    fun `union scan and verdict feed agree on the seen-state`() = runBlocking {
        val store = InMemoryWatchStore()
        store.watch(key, "work-1")

        // Scan 1: the union carries the direct member — an appearance.
        val scanOne = SourceWatchPolicy.evaluate(
            seenState(store),
            listOf(card(key, source("soundbooks", "https://sound-books.net/a")))
        )!!
        store.markSeen(key, scanOne.appearedByMergeKey.getValue(key))

        // Scan 2: the same source in the union and re-delivered as the
        // mapping verdict — silence on both channels (exactly once).
        val scanTwo = SourceWatchPolicy.evaluate(
            seenState(store),
            listOf(card(key, source("soundbooks", "https://sound-books.net/a"))),
            mappingVerdicts = listOf(SourceWatchPolicy.MappingVerdict(key, "soundbooks"))
        )
        assertNull(scanTwo)
    }

    private fun seenState(store: InMemoryWatchStore): SourceWatchPolicy.SeenState =
        SourceWatchPolicy.SeenState(
            watched = store.watched.value,
            seen = store.watched.value.keys.associateWith(store::seenFor)
        )
}

/**
 * The [SourceWatchStore] read/write seams the bookkeeping uses, in memory —
 * the store's persistence itself is covered by [SourceWatchStoreTest].
 */
private class InMemoryWatchStore {
    val watched = MutableStateFlow<Map<String, String>>(emptyMap())
    private val seen = mutableMapOf<String, MutableSet<String>>()

    fun watch(mergeKey: String, workId: String) {
        watched.value = watched.value + (mergeKey to workId)
    }

    fun seenFor(mergeKey: String): Set<String> = seen[mergeKey].orEmpty().toSet()

    fun markSeen(mergeKey: String, sourceIds: Set<String>) {
        seen.getOrPut(mergeKey) { mutableSetOf() } += sourceIds
    }
}
