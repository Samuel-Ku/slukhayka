package com.slukhayka.audiobooks.data.watch

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-49 T4 — the pure Source Watch appearance policy (ADR-0037 §6):
 * what counts as an appearance and how many times it fires.
 *
 * A watched Work's union card (or a mapping verdict) that gains a
 * non-refused direct member is an appearance; the appearance fires the
 * local notification exactly once per source — remembered only after the
 * result is persisted ([SourceWatchPolicy.SeenState]).
 *
 * ADR-0037: the refusal is absolute — a refused source is never an
 * appearance, a refused member never satisfies a watch. The policy is pure
 * JVM, no Android, no network: the union scan rides refreshes that already
 * happen, the verdict hook rides the mapping verdicts (zero new requests).
 */
class SourceWatchPolicyTest {

    private fun source(sourceId: String, url: String) =
        GlobalSearchSource(sourceId = sourceId, sourceName = sourceId, url = url)

    private fun card(mergeKey: String, vararg sources: GlobalSearchSource) =
        GlobalSearchResult(
            title = "Книга",
            author = "Автор",
            narrator = "",
            mergeKey = mergeKey,
            coverImageUrl = null,
            sources = sources.toList()
        )

    private val key = "книга|автор"

    @Test
    fun `no watch means no appearance`() {
        val state = SourceWatchPolicy.SeenState(watched = emptyMap(), seen = emptyMap())
        val catalog = listOf(card(key, source("4read", "https://4read.org/a")))

        assertNull(SourceWatchPolicy.evaluate(state, catalog))
    }

    @Test
    fun `first appearance of a non-refused source notifies once`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val catalog = listOf(
            card(key, source("4read", "https://4read.org/a"), source("soundbooks", "https://sound-books.net/a"))
        )

        // The 4read member is refused — it never counts; the direct
        // soundbooks member is the appearance.
        val appearance = SourceWatchPolicy.evaluate(
            state,
            catalog,
            refusedSources = setOf("4read")
        )!!

        assertEquals("work-1", appearance.workId)
        assertEquals(setOf("soundbooks"), appearance.appearedSourceIds)
        assertTrue(appearance.verdicts.isEmpty())
        assertTrue(appearance.next.seen.getValue(key).contains("soundbooks"))
    }

    @Test
    fun `second scan with the same source notifies nothing`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = mapOf(key to setOf("soundbooks"))
        )
        val catalog = listOf(card(key, source("soundbooks", "https://sound-books.net/a")))

        assertNull(SourceWatchPolicy.evaluate(state, catalog))
    }

    @Test
    fun `a new additional source notifies again`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = mapOf(key to setOf("soundbooks"))
        )
        val catalog = listOf(
            card(key, source("soundbooks", "https://sound-books.net/a"), source("sluhayua", "https://sluhay.com.ua/42"))
        )

        val appearance = SourceWatchPolicy.evaluate(state, catalog)!!

        assertEquals(setOf("sluhayua"), appearance.appearedSourceIds)
    }

    @Test
    fun `refused source never appears and never satisfies the watch`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val catalog = listOf(card(key, source("4read", "https://4read.org/a")))
        assertNull(SourceWatchPolicy.evaluate(state, catalog, refusedSources = setOf("4read")))
    }

    @Test
    fun `card carrying refused and allowed sources - only the allowed one appears`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val catalog = listOf(
            card(key, source("4read", "https://4read.org/a"), source("lihtar", "https://lihtar.in.ua/a"))
        )

        val appearance = SourceWatchPolicy.evaluate(
            state,
            catalog,
            refusedSources = setOf("4read")
        )!!

        assertEquals(setOf("lihtar"), appearance.appearedSourceIds)
    }

    @Test
    fun `mapping verdict is an appearance exactly once`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val refusal = emptySet<String>()
        val verdicts = listOf(
            SourceWatchPolicy.MappingVerdict(mergeKey = key, sourceId = "sluhayua")
        )

        val first = SourceWatchPolicy.evaluate(state, emptyList(), mappingVerdicts = verdicts, refusedSources = refusal)!!
        assertEquals(setOf("sluhayua"), first.appearedSourceIds)

        // Re-delivered memoized verdict inside its TTL — no second notification.
        assertNull(
            SourceWatchPolicy.evaluate(
                first.next,
                emptyList(),
                mappingVerdicts = verdicts,
                refusedSources = refusal
            )
        )
    }

    @Test
    fun `verdict for a refused source never satisfies the watch`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val refusal = setOf("soundbooks")
        val verdicts = listOf(
            SourceWatchPolicy.MappingVerdict(mergeKey = key, sourceId = "soundbooks")
        )

        assertNull(
            SourceWatchPolicy.evaluate(state, emptyList(), mappingVerdicts = verdicts, refusedSources = refusal)
        )
    }

    @Test
    fun `verdict for an unwatched work is ignored`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val verdicts = listOf(
            SourceWatchPolicy.MappingVerdict(mergeKey = "інша|книга", sourceId = "sluhayua")
        )

        assertNull(SourceWatchPolicy.evaluate(state, emptyList(), mappingVerdicts = verdicts))
    }

    @Test
    fun `appearance without persistence forgets and can repeat`() {
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1"),
            seen = emptyMap()
        )
        val catalog = listOf(card(key, source("soundbooks", "https://sound-books.net/a")))

        // The result is not persisted: the next scan sees the same fresh state.
        SourceWatchPolicy.evaluate(state, catalog)!!
        val again = SourceWatchPolicy.evaluate(state, catalog)!!

        assertEquals(setOf("soundbooks"), again.appearedSourceIds)
    }

    @Test
    fun `several watched books appear in one scan`() {
        val otherKey = "інша|книга"
        val state = SourceWatchPolicy.SeenState(
            watched = mapOf(key to "work-1", otherKey to "work-2"),
            seen = emptyMap()
        )
        val catalog = listOf(
            card(key, source("soundbooks", "https://sound-books.net/a")),
            card(otherKey, source("sluhayua", "https://sluhay.com.ua/42"))
        )

        val appearance = SourceWatchPolicy.evaluate(state, catalog)!!

        assertEquals(setOf("work-1", "work-2"), appearance.workIds.toSet())
        assertEquals(2, appearance.appearedByMergeKey.size)
        assertTrue(appearance.verdicts.isEmpty())
    }
}
