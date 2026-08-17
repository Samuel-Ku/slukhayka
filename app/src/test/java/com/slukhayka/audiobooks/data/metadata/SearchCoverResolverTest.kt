package com.slukhayka.audiobooks.data.metadata

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
 * Spec-30 T3 (#218) — the search-card cover resolver over the seam: the
 * client-first precedence (locally known cover → shared cache, fill-the-gap
 * only → the source's own claim), ONE batched shared read for the visible
 * cards (never a request per Work), mirroring of shared hits into the local
 * database through the existing cover write path
 * ([AudiobookDao.updateCoverImageUrl]), and degrade-never on a missing or
 * throwing store. Prior art: [SearchDurationResolverTest].
 */
class SearchCoverResolverTest {

    private class RecordingStore(
        var hits: Map<String, String> = emptyMap(),
        var throwOnBatch: Boolean = false
    ) : SharedBookMetaStore {
        val batchCalls = mutableListOf<List<String>>()
        val singleCalls = mutableListOf<String>()
        val puts = mutableListOf<Triple<String, String, CoverProvenance>>()

        override suspend fun getCover(mergeKey: String): String? {
            singleCalls += mergeKey
            return hits[mergeKey]
        }

        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> {
            batchCalls += mergeKeys
            if (throwOnBatch) throw IllegalStateException("shared base down")
            return hits.filterKeys { it in mergeKeys }
        }

        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) {
            puts += Triple(mergeKey, coverUrl, provenance)
        }

        // Duration/profile methods — not exercised by the cover resolver.
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

    private fun book(
        id: String,
        coverUrl: String? = null,
        mergeKey: String = ""
    ): AudiobookEntity = AudiobookEntity(
        id = id,
        title = "Книга",
        author = "Автор",
        narrator = "Читець",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://4read.org/$id.html",
        coverImageUrl = coverUrl,
        totalDurationSeconds = 0L
    ).also { it.mergeKey = mergeKey }

    private fun card(
        title: String = "Книга",
        author: String = "Автор",
        mergeKey: String = "книга|автор",
        coverUrl: String? = null,
        url: String = "https://4read.org/kniga.html"
    ) = GlobalSearchResult(
        title = title,
        author = author,
        narrator = "",
        mergeKey = mergeKey,
        coverImageUrl = coverUrl,
        sources = listOf(GlobalSearchSource("4read", "4read", url))
    )

    private fun store(hits: Map<String, String> = emptyMap(), throwOnBatch: Boolean = false) =
        RecordingStore(hits = hits, throwOnBatch = throwOnBatch)

    // ---------------------------------------------------------------------
    // Locally known wins — the shared cache never overrides the local row
    // ---------------------------------------------------------------------

    @Test
    fun `a locally known cover wins over the card's own claim and the shared store is not consulted`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(book("b1", coverUrl = "https://canonical.example/cover.jpg", mergeKey = "книга|автор"))
        )
        val store = store(hits = mapOf("книга|автор" to "https://shared.example/other.jpg"))
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card(coverUrl = "https://source.example/claim.jpg")))

        // The card shows the LOCAL (canonical) URL, not the source claim and
        // not the shared one.
        assertEquals("https://canonical.example/cover.jpg", resolved[0].coverImageUrl)
        // The shared tier was never touched — no batch, no singles.
        assertTrue(store.batchCalls.isEmpty())
        assertTrue(store.singleCalls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // The source's own claim is the last resort
    // ---------------------------------------------------------------------

    @Test
    fun `a card with no local row keeps its own cover on a shared miss`() = runBlocking {
        val dao = FakeAudiobookDao()
        val store = store()
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card(coverUrl = "https://source.example/claim.jpg")))

        assertEquals("https://source.example/claim.jpg", resolved[0].coverImageUrl)
    }

    @Test
    fun `a card with a local row cover is never replaced on a shared miss`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(book("b1", coverUrl = "https://canonical.example/cover.jpg", mergeKey = "книга|автор"))
        )
        val store = store()
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card(coverUrl = "https://source.example/claim.jpg")))

        assertEquals("https://canonical.example/cover.jpg", resolved[0].coverImageUrl)
    }

    // ---------------------------------------------------------------------
    // Shared fills the gap only
    // ---------------------------------------------------------------------

    @Test
    fun `a shared hit fills a card with no local row`() = runBlocking {
        val dao = FakeAudiobookDao() // empty library — the book was never imported
        val store = store(hits = mapOf("книга|автор" to "https://shared.example/cover.jpg"))
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card()))

        assertEquals("https://shared.example/cover.jpg", resolved[0].coverImageUrl)
        assertEquals(listOf(listOf("книга|автор")), store.batchCalls)
        assertTrue(store.singleCalls.isEmpty())
    }

    @Test
    fun `a shared miss leaves the card with no cover`() = runBlocking {
        val dao = FakeAudiobookDao()
        val store = store()
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card()))

        assertNull(resolved[0].coverImageUrl)
    }

    @Test
    fun `a card with a blank merge key never consults the shared tier`() = runBlocking {
        val dao = FakeAudiobookDao()
        val store = store(hits = mapOf("" to "https://shared.example/cover.jpg"))
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card(mergeKey = "")))

        // No Work identity — the shared base has nothing to offer; the card
        // keeps its own claim (or null) and no read is ever made.
        assertNull(resolved[0].coverImageUrl)
        assertTrue(store.batchCalls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Batching — one shared read for the visible cards, never per Work
    // ---------------------------------------------------------------------

    @Test
    fun `gap cards are resolved in ONE batched read, never one request per card`() = runBlocking {
        val dao = FakeAudiobookDao()
        val store = store()
        val resolver = SearchCoverResolver(dao, store)

        resolver.resolve((1..25).map { i -> card(title = "Книга $i", mergeKey = "книга $i|автор") })

        // Exactly one batch call covering all gap keys (the visible page), no
        // single-id calls at all.
        assertEquals(1, store.batchCalls.size)
        assertEquals(25, store.batchCalls[0].size)
        assertTrue(store.singleCalls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Mirroring — a shared hit lands in the local row via the write path
    // ---------------------------------------------------------------------

    @Test
    fun `a shared hit mirrors into a local row with a blank cover`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(book("b1", coverUrl = null, mergeKey = "книга|автор"))
        )
        val store = store(hits = mapOf("книга|автор" to "https://shared.example/cover.jpg"))
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card()))

        assertEquals("https://shared.example/cover.jpg", resolved[0].coverImageUrl)
        // The local row now carries the shared value (the existing write path).
        assertEquals("https://shared.example/cover.jpg", dao.getAudiobookById("b1")!!.coverImageUrl)
    }

    // ---------------------------------------------------------------------
    // Degrade-never
    // ---------------------------------------------------------------------

    @Test
    fun `a throwing shared store degrades silently - cards stay unchanged`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(book("b1", coverUrl = null, mergeKey = "книга|автор"))
        )
        val store = store(throwOnBatch = true)
        val resolver = SearchCoverResolver(dao, store)

        val resolved = resolver.resolve(listOf(card(coverUrl = "https://source.example/claim.jpg")))

        // No crash, no fabricated URL, local row untouched.
        assertEquals("https://source.example/claim.jpg", resolved[0].coverImageUrl)
        assertNull(dao.getAudiobookById("b1")!!.coverImageUrl)
    }

    @Test
    fun `no shared store means the resolver is a no-op`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(book("b1", coverUrl = null, mergeKey = "книга|автор"))
        )
        val resolver = SearchCoverResolver(dao, null)

        val resolved = resolver.resolve(listOf(card(coverUrl = "https://source.example/claim.jpg")))

        assertEquals("https://source.example/claim.jpg", resolved[0].coverImageUrl)
        assertNull(dao.getAudiobookById("b1")!!.coverImageUrl)
    }
}