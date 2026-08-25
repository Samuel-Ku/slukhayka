package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFacetStoreContractTest {

    @Test
    fun `fake round-trips one assertion idempotently through the public seam`() = runBlocking {
        val store = FakeSharedBookMetaStore()
        val first = work("same", updatedAt = 10L, genres = listOf(FacetGenre("fantasy", "Фентезі")))
        val fresher = first.copy(
            updatedAt = 20L,
            genres = listOf(FacetGenre("fantasy", "Фентезі"), FacetGenre("drama", "Драма"))
        )
        val stale = first.copy(updatedAt = 5L, genres = listOf(FacetGenre("wrong", "Хибний")))

        store.putFacet(first)
        store.putFacet(fresher)
        store.putFacet(stale)

        assertEquals(fresher, store.getFacet(FacetAssertionKey(first.kind, first.workId, first.sourceId)))
        assertEquals(1, store.getFacetPage(after = null, limit = 100).assertions.size)
    }

    @Test
    fun `fake pages are bounded and ordered after timestamp plus document cursor`() = runBlocking {
        val store = FakeSharedBookMetaStore()
        val assertions = listOf(
            work("c", updatedAt = 11L),
            work("a", updatedAt = 10L),
            work("b", updatedAt = 10L),
            work("d", updatedAt = 12L)
        )
        assertions.reversed().forEach { store.putFacet(it) }

        val first = store.getFacetPage(after = null, limit = 2)
        val second = store.getFacetPage(after = first.nextCursor, limit = 2)

        assertEquals(
            assertions.sortedWith(compareBy<FacetAssertion> { it.updatedAt }.thenBy { it.documentId }),
            first.assertions + second.assertions
        )
        assertEquals(2, first.assertions.size)
        assertNull(second.nextCursor)
        assertTrue(store.getFacetPage(after = null, limit = FacetPageLimits.MAX_PAGE_SIZE + 10).assertions.size <= FacetPageLimits.MAX_PAGE_SIZE)
        assertEquals(emptyList<FacetAssertion>(), store.getFacetPage(after = null, limit = 0).assertions)
    }

    @Test
    fun `fake ignores malformed assertion writes like the production boundary`() = runBlocking {
        val store = FakeSharedBookMetaStore()
        val malformed = work("bad", updatedAt = 10L).copy(genres = listOf(FacetGenre(" ", "Фентезі")))

        store.putFacet(malformed)

        assertNull(store.getFacet(FacetAssertionKey(malformed.kind, malformed.workId, malformed.sourceId)))
    }

    private fun work(
        id: String,
        updatedAt: Long,
        genres: List<FacetGenre> = listOf(FacetGenre("genre", "Жанр"))
    ) =
        FacetAssertion.Work(
            workId = "work-$id",
            sourceId = "4read",
            genres = genres,
            observedAt = 1_000L,
            updatedAt = updatedAt
        )
}
