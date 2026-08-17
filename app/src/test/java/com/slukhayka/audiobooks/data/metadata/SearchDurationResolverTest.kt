package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-30 T2 (#217) — the search-card duration resolver over the seam: the
 * client-first precedence (locally known → shared cache, fill-the-gap only),
 * ONE batched shared read for the visible cards (never a request per book),
 * mirroring of shared hits into the local database through the existing
 * duration write path, and degrade-never on a missing/throwing store.
 * Prior art: the universe store fixture tests.
 */
class SearchDurationResolverTest {

    private class RecordingStore(
        var hits: Map<String, Long> = emptyMap(),
        var throwOnBatch: Boolean = false
    ) : SharedBookMetaStore {
        val batchCalls = mutableListOf<List<String>>()
        val singleCalls = mutableListOf<String>()
        val puts = mutableListOf<Triple<String, Long, DurationProvenance>>()

        override suspend fun getDuration(editionId: String): Long? {
            singleCalls += editionId
            return hits[editionId]
        }

        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> {
            batchCalls += editionIds
            if (throwOnBatch) throw IllegalStateException("shared base down")
            return hits.filterKeys { it in editionIds }
        }

        override suspend fun putDuration(
            editionId: String,
            durationSeconds: Long,
            provenance: DurationProvenance
        ) {
            puts += Triple(editionId, durationSeconds, provenance)
        }

        // Spec-32 profile methods — not exercised by the duration resolver.
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? = null

        override suspend fun putProfile(
            sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance
        ) = Unit

        // Spec-30 T3 cover methods — not exercised here.
        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) = Unit
    }

    private fun book(
        id: String,
        title: String,
        author: String = "Автор",
        narrator: String = "Читець",
        sourceUrl: String = "https://4read.org/$id.html",
        totalDurationSeconds: Long = 0L,
        mergeKey: String = ""
    ): AudiobookEntity = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = sourceUrl,
        totalDurationSeconds = totalDurationSeconds
    ).also { it.mergeKey = mergeKey }

    private fun card(
        title: String,
        author: String = "Автор",
        narrator: String = "",
        // The normalized merge key (lowercased) — the same shape the local
        // `works` rows carry, so [AudiobookDao.findByMergeKey] matches.
        mergeKey: String = "${title.lowercase()}|${author.lowercase()}",
        url: String = "https://4read.org/${title.lowercase()}.html"
    ) = GlobalSearchResult(
        title = title,
        author = author,
        narrator = narrator,
        mergeKey = mergeKey,
        sources = listOf(GlobalSearchSource("4read", "4read", url))
    )

    // ---------------------------------------------------------------------
    // Locally known wins — the shared cache never overrides the listener
    // ---------------------------------------------------------------------

    @Test
    fun `a locally known duration wins and the shared store is not consulted`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("b1", "Книга", totalDurationSeconds = 7_200L, mergeKey = "книга|автор")
            )
        )
        val store = RecordingStore(hits = mapOf("edition-of-book" to 99L))
        val resolver = SearchDurationResolver(dao, store)

        val resolved = resolver.resolve(listOf(card("Книга")))

        // The card carries the LOCAL value, not the shared one.
        assertEquals(7_200L, resolved[0].durationSeconds)
        // The shared tier was never touched — no batch, no singles.
        assertTrue(store.batchCalls.isEmpty())
        assertTrue(store.singleCalls.isEmpty())
        // And the local row was not overwritten.
        assertEquals(7_200L, dao.getAudiobookById("b1")!!.totalDurationSeconds)
    }

    // ---------------------------------------------------------------------
    // Shared fills the gap only
    // ---------------------------------------------------------------------

    @Test
    fun `a shared hit fills a card with no local row`() = runBlocking {
        val dao = FakeAudiobookDao() // empty library — the book was never imported
        val url = "https://4read.org/kniga.html"
        val editionId = EditionId.forBook("книга|автор", url, "")
        val store = RecordingStore(hits = mapOf(editionId to 5_400L))
        val resolver = SearchDurationResolver(dao, store)

        val resolved = resolver.resolve(listOf(card("Книга", url = url)))

        assertEquals(5_400L, resolved[0].durationSeconds)
        assertEquals(listOf(listOf(editionId)), store.batchCalls)
        assertTrue(store.singleCalls.isEmpty())
    }

    @Test
    fun `a shared miss leaves the card without a duration`() = runBlocking {
        val dao = FakeAudiobookDao()
        val store = RecordingStore(hits = emptyMap())
        val resolver = SearchDurationResolver(dao, store)

        val resolved = resolver.resolve(listOf(card("Книга")))

        assertNull(resolved[0].durationSeconds)
    }

    // ---------------------------------------------------------------------
    // Batching — one shared read for the visible cards, never per book
    // ---------------------------------------------------------------------

    @Test
    fun `gap cards are resolved in ONE batched read, never one request per card`() = runBlocking {
        val dao = FakeAudiobookDao()
        val store = RecordingStore()
        val resolver = SearchDurationResolver(dao, store)

        resolver.resolve((1..25).map { i -> card("Книга $i") })

        // Exactly one batch call covering all gap ids (the visible page), no
        // single-id calls at all.
        assertEquals(1, store.batchCalls.size)
        assertEquals(25, store.batchCalls[0].size)
        assertTrue(store.singleCalls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Mirroring — a shared hit lands in the local row via the write path
    // ---------------------------------------------------------------------

    @Test
    fun `a shared hit mirrors into a local row with unknown duration`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("b1", "Книга", totalDurationSeconds = 0L, mergeKey = "книга|автор")
            )
        )
        val url = "https://4read.org/kniga.html"
        val editionId = EditionId.forBook("книга|автор", url, "")
        val store = RecordingStore(hits = mapOf(editionId to 10_800L))
        val resolver = SearchDurationResolver(dao, store)

        val resolved = resolver.resolve(listOf(card("Книга", url = url)))

        assertEquals(10_800L, resolved[0].durationSeconds)
        // The local row now carries the shared value (the existing write path).
        assertEquals(10_800L, dao.getAudiobookById("b1")!!.totalDurationSeconds)
    }

    // ---------------------------------------------------------------------
    // Degrade-never
    // ---------------------------------------------------------------------

    @Test
    fun `a throwing shared store degrades silently - cards stay unchanged`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("b1", "Книга", totalDurationSeconds = 0L, mergeKey = "книга|автор")
            )
        )
        val store = RecordingStore(throwOnBatch = true)
        val resolver = SearchDurationResolver(dao, store)

        val resolved = resolver.resolve(listOf(card("Книга")))

        assertNull(resolved[0].durationSeconds)
        // No crash, no fabricated number, local row untouched.
        assertEquals(0L, dao.getAudiobookById("b1")!!.totalDurationSeconds)
    }

    @Test
    fun `no shared store means the resolver is a no-op`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("b1", "Книга", totalDurationSeconds = 0L, mergeKey = "книга|автор")
            )
        )
        val resolver = SearchDurationResolver(dao, null)

        val resolved = resolver.resolve(listOf(card("Книга")))

        assertNull(resolved[0].durationSeconds)
    }
}
