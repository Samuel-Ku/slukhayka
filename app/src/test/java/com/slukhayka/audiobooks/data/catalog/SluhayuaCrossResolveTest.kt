package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.search.SearchCache
import com.slukhayka.audiobooks.data.search.SearchQueryKey
import com.slukhayka.audiobooks.data.search.SearchResultCodec
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.mergeGlobalSearchResults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec #462 ID7 (#469) — the tap-time cross-resolve of a 4read-only card:
 * MergeKey matching, the one-request-per-tap discipline, the availability-TTL
 * verdict memo and the shared [SearchCache] channel (with the sluhayua-only
 * source guard — a cached 4read card never becomes a «direct» match).
 */
class SluhayuaCrossResolveTest {

    /** In-memory shared store — the same fixture style as the spec-33 tests. */
    private class FakeCache : SearchCache {
        val documents = HashMap<String, Map<String, Any>>()
        val written = mutableListOf<String>()

        override suspend fun readDocument(queryKey: String): Map<String, Any>? = documents[queryKey]

        override suspend fun writeDocument(queryKey: String, document: Map<String, Any>) {
            written += queryKey
            documents[queryKey] = document
        }

        override fun nowMillis(): Long = 1_000_000L
    }

    private fun sluhayuaBook(title: String = "Книга", author: String = "Автор") = SourceBook(
        title = title,
        author = author,
        narrator = "Диктор",
        url = "https://sluhay.com.ua/42:knyzhka",
        sourceId = "sluhayua"
    )

    private fun fourReadBook(title: String = "Книга", author: String = "Автор") = SourceBook(
        title = title,
        author = author,
        url = "https://4read.org/book-1",
        sourceId = "4read"
    )

    @Test
    fun `sluhayua card matching the Work MergeKey is served with exactly one search`() = runTest {
        var searches = 0
        val cache = FakeCache()
        val resolver = SluhayuaCrossResolve(
            search = { _ ->
                searches += 1
                listOf(sluhayuaBook())
            },
            cache = cache
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        val match = resolver.resolve("Книга", "Автор", mergeKey)

        assertEquals("https://sluhay.com.ua/42:knyzhka", match?.url)
        assertEquals("Книга", match?.title)
        assertEquals(1, searches)
        // The merged cards rode back into the shared base best-effort.
        assertTrue(cache.written.contains(SearchQueryKey.normalize("Книга Автор")!!))
    }

    @Test
    fun `repeated tap inside the TTL never re-requests`() = runTest {
        var searches = 0
        val resolver = SluhayuaCrossResolve(
            search = { _ ->
                searches += 1
                listOf(sluhayuaBook())
            },
            cache = FakeCache()
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertEquals("https://sluhay.com.ua/42:knyzhka", resolver.resolve("Книга", "Автор", mergeKey)?.url)
        assertEquals("https://sluhay.com.ua/42:knyzhka", resolver.resolve("Книга", "Автор", mergeKey)?.url)
        assertEquals(1, searches)
    }

    @Test
    fun `no match is a memoized negative verdict - still one search on the next tap`() = runTest {
        var searches = 0
        val resolver = SluhayuaCrossResolve(
            search = { _ ->
                searches += 1
                // The same Work, but nothing matches the requested title+author.
                listOf(sluhayuaBook(title = "Інша книга", author = "Інший автор"))
            },
            cache = FakeCache()
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
        assertEquals(1, searches)
    }

    @Test
    fun `fresh shared entry serves the tap without touching sluhayua`() = runTest {
        var searches = 0
        val cache = FakeCache()
        val results = mergeGlobalSearchResults(listOf(sluhayuaBook()))
        cache.documents[SearchQueryKey.normalize("Книга Автор")!!] = SearchResultCodec.toMap(1_000_000L, results)
        val resolver = SluhayuaCrossResolve(
            search = { _ ->
                searches += 1
                emptyList()
            },
            cache = cache
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertEquals("https://sluhay.com.ua/42:knyzhka", match?.url)
        assertEquals(0, searches)
    }

    @Test
    fun `cached 4read-only entry never becomes a direct match - the live search decides`() = runTest {
        var searches = 0
        val cache = FakeCache()
        val cachedResults = mergeGlobalSearchResults(listOf(fourReadBook()))
        cache.documents[SearchQueryKey.normalize("Книга Автор")!!] = SearchResultCodec.toMap(1_000_000L, cachedResults)
        val resolver = SluhayuaCrossResolve(
            search = { _ ->
                searches += 1
                listOf(sluhayuaBook())
            },
            cache = cache
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        // The 4read card matches the MergeKey but is a BROWSER source — the
        // cross-resolve exists to avoid exactly that door.
        assertEquals("https://sluhay.com.ua/42:knyzhka", match?.url)
        assertEquals(1, searches)
    }

    @Test
    fun `blank MergeKey is never a search question`() = runTest {
        var searches = 0
        val cache = FakeCache()
        val resolver = SluhayuaCrossResolve(
            search = { _ ->
                searches += 1
                listOf(sluhayuaBook())
            },
            cache = cache
        )

        assertNull(resolver.resolve("Книга", "Автор", ""))
        assertEquals(0, searches)
        assertTrue(cache.written.isEmpty())
    }
}
