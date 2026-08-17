package com.slukhayka.audiobooks.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-30 T3 (#218) — the curated-covers seed over the seam: pours the
 * curated entries into the shared base idempotently (one document per Work
 * mergeKey, replaced never duplicated), keyed exactly as the read path reads
 * them (so the app's own getCover reads the curated value — unlike the
 * universe seed, whose seed documents the app never reads), with curated
 * provenance. Degrade-never: no store, a blank/invalid entry or a throwing
 * store call contributes nothing and never aborts the rest. Prior art:
 * the universe [com.slukhayka.audiobooks.data.universe.CuratedSeed] tests.
 */
class CuratedCoverSeedTest {

    private class RecordingStore(var throwOnPut: Boolean = false) : SharedBookMetaStore {
        val puts = mutableListOf<Triple<String, String, CoverProvenance>>()

        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) {
            if (throwOnPut) throw IllegalStateException("shared base down")
            puts += Triple(mergeKey, coverUrl, provenance)
        }

        // Duration/profile methods — not exercised by the seed.
        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(
            editionId: String, durationSeconds: Long, provenance: DurationProvenance
        ) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? = null
        override suspend fun putProfile(
            sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance
        ) = Unit
    }

    @Test
    fun `every curated cover is poured with curated provenance and the same stamp`() = runBlocking {
        val store = RecordingStore()
        val covers = listOf(
            CuratedCover("кобзар|шевченко", "https://4read.org/img/kobzar.jpg"),
            CuratedCover("лісова пісня|українка", "https://4read.org/img/lisova.jpg")
        )

        CuratedCoverSeed.seed(store, covers, now = { 123L })

        // Idempotent by key: one document per Work mergeKey, replaced never
        // duplicated — the same shape the read path keys by.
        assertEquals(2, store.puts.size)
        for ((mergeKey, url, provenance) in store.puts) {
            assertEquals(CoverProvenance.SOURCE_CURATED, provenance.source)
            assertEquals(123L, provenance.resolvedAt)
            assertTrue(covers.any { it.mergeKey == mergeKey && it.coverUrl == url })
        }
    }

    @Test
    fun `blank or implausible entries are skipped`() = runBlocking {
        val store = RecordingStore()
        val covers = listOf(
            CuratedCover("", "https://4read.org/img/blank-key.jpg"),
            CuratedCover("книга|автор", "file:///sdcard/not-http.jpg"),
            CuratedCover("книга2|автор", "https://4read.org/img/ok.jpg")
        )

        CuratedCoverSeed.seed(store, covers)

        assertEquals(1, store.puts.size)
        assertEquals("книга2|автор", store.puts[0].first)
    }

    @Test
    fun `a throwing store call does not abort the rest of the seed`() = runBlocking {
        val store = RecordingStore(throwOnPut = true)
        val covers = listOf(
            CuratedCover("a|б", "https://4read.org/img/a.jpg"),
            CuratedCover("b|в", "https://4read.org/img/b.jpg")
        )

        CuratedCoverSeed.seed(store, covers)

        // No crash — the seed is best-effort by contract.
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `no shared store means the seed is a no-op`() = runBlocking {
        CuratedCoverSeed.seed(null, listOf(CuratedCover("a|б", "https://4read.org/img/a.jpg")))
    }
}