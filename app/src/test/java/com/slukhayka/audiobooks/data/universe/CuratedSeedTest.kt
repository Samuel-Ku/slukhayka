package com.slukhayka.audiobooks.data.universe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-26 T6 (#180) — the curated-asset seed: one document per curated
 * series keyed deterministically (`seed:` + URL key, else normalized
 * title), provenance source=curated with the author-verified flag, and the
 * whole thing idempotent — a re-seed writes the same keys, never duplicates
 * (AC3). No store or a failing store contributes nothing (AC5).
 */
class CuratedSeedTest {

    private val universes = listOf(
        UniverseList(
            id = "first-law",
            name = "Перший закон",
            series = listOf(
                UniverseSeries(
                    title = "Перший закон",
                    urls = listOf("https://4read.org/xfsearch/cikl/pervyj-zakon/")
                ),
                UniverseSeries(
                    title = "Епоха божевілля",
                    urls = listOf("https://4read.org/xfsearch/cikl/epoha-bozhevillja/")
                )
            )
        ),
        UniverseList(
            id = "witcher",
            name = "Відьмак",
            series = listOf(
                UniverseSeries(title = "Відьмак") // no URL → the title key
            )
        )
    )

    private class RecordingStore : SharedUniverseStore {
        val writes = mutableListOf<Triple<String, UniverseResolution, ResolutionProvenance>>()
        val docs = mutableMapOf<String, UniverseResolution>()
        var failWrites = false

        override suspend fun getResolution(workId: String): UniverseResolution? = docs[workId]

        override suspend fun putResolution(
            workId: String,
            resolution: UniverseResolution,
            provenance: ResolutionProvenance
        ) {
            if (failWrites) throw IllegalStateException("offline")
            writes += Triple(workId, resolution, provenance)
            docs[workId] = resolution
        }
    }

    @Test
    fun `seeding writes one document per curated series keyed deterministically`() = runBlocking {
        val store = RecordingStore()

        CuratedSeed.seed(store, universes, now = { 1_000_000L })

        // 3 series → 3 documents; the URL key wins where a URL exists, the
        // normalized title keys the bare series.
        assertEquals(3, store.writes.size)
        assertEquals(
            listOf(
                "seed:https://4read.org/xfsearch/cikl/epoha-bozhevillja",
                "seed:https://4read.org/xfsearch/cikl/pervyj-zakon",
                "seed:відьмак"
            ),
            store.writes.map { it.first }.sorted()
        )
        // Every document carries the curated provenance, stamped.
        store.writes.forEach { (_, _, provenance) ->
            assertEquals(ResolutionProvenance.SOURCE_CURATED, provenance.source)
            assertTrue(provenance.authorVerified)
            assertEquals(1_000_000L, provenance.resolvedAt)
        }
        // Each document holds the full chain and its own position.
        val firstLaw = store.writes.first { it.first.contains("pervyj-zakon") }
        assertEquals("first-law", firstLaw.second.universe.id)
        assertEquals(1, firstLaw.second.position)
        val witcher = store.writes.first { it.first.contains("відьмак") }
        assertEquals("witcher", witcher.second.universe.id)
        assertEquals(1, witcher.second.position)
    }

    @Test
    fun `re-seeding is idempotent - same keys, never duplicates`() = runBlocking {
        // AC3: a second seed writes the same documents — a document key is
        // replaced, never duplicated.
        val store = RecordingStore()

        CuratedSeed.seed(store, universes, now = { 1L })
        val keysAfterFirst = store.docs.keys.sorted()

        CuratedSeed.seed(store, universes, now = { 2L })

        assertEquals(keysAfterFirst, store.docs.keys.sorted())
        assertEquals(3, store.docs.size)
    }

    @Test
    fun `a failing store is silent - the seed never throws`() = runBlocking {
        // AC5: a failing write contributes nothing and never surfaces.
        val store = RecordingStore().apply { failWrites = true }

        CuratedSeed.seed(store, universes) // must not throw
    }

    @Test
    fun `no store - nothing happens`() = runBlocking {
        // No Firebase keys → no store → the seed is a no-op.
        CuratedSeed.seed(null, universes)
    }
}
