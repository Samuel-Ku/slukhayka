package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.search.SearchCache
import com.slukhayka.audiobooks.data.search.SearchQueryKey
import com.slukhayka.audiobooks.data.search.SearchResultCodec
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.mergeGlobalSearchResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-49 T2a — the Replacement Mapping resolver (ADR-0037 §3), the pure JVM
 * generalization of the #469 cross-resolve: a Work whose audio is refused or
 * absent maps onto ANY direct source.
 *
 * Pinned here is the external behavior, never the internal state layout:
 * the zero-request path (union, then the shared SearchCache), the ONE
 * parallel volley across all supplied direct sources on a miss, the exact
 * 6h/15m per-Work verdict memo (stale at the exact expiry boundary), the
 * MergeKey-only match rule, and the best-effort silence on a failing source.
 * Every search is a counted fake — no network, no Android.
 */
class SourceReplacementMappingTest {

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

    /** Counted per-source search fakes: one invocation equals one HTTP request. */
    private class CountingSearches(vararg sources: Pair<String, List<SourceBook>>) {
        val calls = LinkedHashMap<String, Int>()
        val searches: Map<String, suspend (String) -> List<SourceBook>>

        init {
            val map = LinkedHashMap<String, suspend (String) -> List<SourceBook>>()
            for ((id, books) in sources) {
                val counted: suspend (String) -> List<SourceBook> = { _: String ->
                    calls.merge(id, 1, Int::plus)
                    books
                }
                map[id] = counted
            }
            searches = map
        }

        val total: Int get() = calls.values.sum()
    }

    private fun directBook(
        sourceId: String,
        url: String,
        title: String = "Книга",
        author: String = "Автор",
        narrator: String = "Диктор"
    ) = SourceBook(
        title = title,
        author = author,
        narrator = narrator,
        url = url,
        sourceId = sourceId
    )

    private fun fourReadBook(title: String = "Книга", author: String = "Автор") = SourceBook(
        title = title,
        author = author,
        url = "https://4read.org/book-1",
        sourceId = "4read"
    )

    private fun indexMatch(sourceId: String, url: String) = SourceReplacementMapping.Match(
        sourceId = sourceId,
        url = url,
        title = "Книга",
        author = "Автор",
        narrator = "",
        coverImageUrl = null
    )

    @Test
    fun `union hit serves the match with zero requests`() = runTest {
        val searches = CountingSearches(
            "soundbooks" to listOf(directBook("soundbooks", "https://sound-books.net/1")),
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val cache = FakeCache()
        val union = mergeGlobalSearchResults(
            listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { union },
            cache = cache
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertEquals("https://sluhay.com.ua/42", match?.url)
        assertEquals("sluhayua", match?.sourceId)
        assertEquals(0, searches.total)
        assertTrue(cache.written.isEmpty())
    }

    @Test
    fun `fresh shared-cache entry serves the match without a volley`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val cache = FakeCache()
        val results = mergeGlobalSearchResults(
            listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        cache.documents[SearchQueryKey.normalize("Книга Автор")!!] =
            SearchResultCodec.toMap(1_000_000L, results)
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = cache
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertEquals("https://sluhay.com.ua/42", match?.url)
        assertEquals(0, searches.total)
    }

    @Test
    fun `miss fires exactly one volley across every direct source`() = runTest {
        val searches = CountingSearches(
            "soundbooks" to listOf(directBook("soundbooks", "https://sound-books.net/1")),
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = FakeCache()
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        // The soundbooks card wins the shared capability order inside the card.
        assertEquals("https://sound-books.net/1", match?.url)
        assertEquals("soundbooks", match?.sourceId)
        // The found narrator is the found source's own claim — honest provenance.
        assertEquals("Диктор", match?.narrator)
        // ONE volley, not one search per candidate step: every direct source
        // searched exactly once, results merged.
        assertEquals(1, searches.calls["soundbooks"])
        assertEquals(1, searches.calls["sluhayua"])
        assertEquals(2, searches.total)
    }

    @Test
    fun `repeated tap inside the TTL never re-requests`() = runTest {
        var now = 1_000_000L
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = FakeCache(),
            clock = { now }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertEquals("https://sluhay.com.ua/42", resolver.resolve("Книга", "Автор", mergeKey)?.url)
        assertEquals("https://sluhay.com.ua/42", resolver.resolve("Книга", "Автор", mergeKey)?.url)
        assertEquals(1, searches.total)
    }

    @Test
    fun `a forced re-check bypasses a fresh memo`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            clock = { 1_000_000L }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        resolver.resolve("Книга", "Автор", mergeKey)
        resolver.resolve("Книга", "Автор", mergeKey, force = true)

        assertEquals(2, searches.total)
    }

    @Test
    fun `a local-only resolve never fires the volley`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            clock = { 1_000_000L }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertNull(resolver.resolveLocalOnly("Книга", "Автор", mergeKey))
        assertEquals(0, searches.total)
    }

    @Test
    fun `a local-only resolve answers from the work index with zero requests`() = runTest {
        val searches = CountingSearches("sluhayua" to emptyList())
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("sluhayua", "https://sluhay.com.ua/42") },
            clock = { 1_000_000L }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertEquals("https://sluhay.com.ua/42", resolver.resolveLocalOnly("Книга", "Автор", mergeKey)?.url)
        assertEquals(0, searches.total)
    }

    @Test
    fun `a direct work-index match needs no session`() = runTest {
        // ADR-0042/0037 — direct index entries keep working unchanged; the
        // session seam only gates BROWSER entries.
        val searches = CountingSearches("soundbooks" to emptyList())
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("soundbooks", "https://sound-books.net/42") },
            clock = { 1_000_000L }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertEquals("https://sound-books.net/42", resolver.resolve("Книга", "Автор", mergeKey)?.url)
        assertEquals(0, searches.total)
    }

    @Test
    fun `a browser work-index match is skipped without a session and accepted with one`() = runTest {
        // #725 — sluhay.com is session-backed BROWSER: a live first-party
        // session is a working transport, not a new browser door; without one
        // the entry is skipped and the volley runs (and still skips browser
        // members by the shared DIRECT filter).
        val searches = CountingSearches("sluhay" to listOf(directBook("sluhay", "https://sluhay.com/42")))
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        val withoutSession = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("sluhay", "https://sluhay.com/42") },
            sessionAlive = { false },
            clock = { 1_000_000L }
        )
        assertNull(withoutSession.resolve("Книга", "Автор", mergeKey))
        assertEquals("the volley ran and skipped the browser member", 1, searches.total)

        val withSession = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("sluhay", "https://sluhay.com/42") },
            sessionAlive = { it == "sluhay" },
            clock = { 1_000_000L }
        )
        assertEquals("https://sluhay.com/42", withSession.resolve("Книга", "Автор", mergeKey)?.url)
    }

    @Test
    fun `a browser work-index match is gated in the local-only resolve too`() = runTest {
        val searches = CountingSearches("sluhay" to emptyList())
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        val withoutSession = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("sluhay", "https://sluhay.com/42") },
            sessionAlive = { false },
            clock = { 1_000_000L }
        )
        assertNull(withoutSession.resolveLocalOnly("Книга", "Автор", mergeKey))

        val withSession = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("sluhay", "https://sluhay.com/42") },
            sessionAlive = { it == "sluhay" },
            clock = { 1_000_000L }
        )
        assertEquals("https://sluhay.com/42", withSession.resolveLocalOnly("Книга", "Автор", mergeKey)?.url)
        assertEquals("local-only never fires the volley", 0, searches.total)
    }

    @Test
    fun `a lapsed session drops the memoized browser match`() = runTest {
        var alive = true
        val searches = CountingSearches("sluhay" to listOf(directBook("sluhay", "https://sluhay.com/42")))
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            workIndex = { _, _, _ -> indexMatch("sluhay", "https://sluhay.com/42") },
            sessionAlive = { alive },
            clock = { 1_000_000L }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertEquals("https://sluhay.com/42", resolver.resolve("Книга", "Автор", mergeKey)?.url)

        // The positive 6h memo must not outlive the session it depends on.
        alive = false
        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
    }

    @Test
    fun `positive verdict is stale at exactly six hours`() = runTest {
        var now = 1_000_000L
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        // No cache: the MEMO is the only fresh verdict under test. With a
        // cache the first volley's write-back would legitimately keep
        // answering after the memo expires — the shared cache outlives the
        // memo by design (6h vs ~24h), the volley stays at zero.
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = null,
            clock = { now }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        resolver.resolve("Книга", "Автор", mergeKey)
        now += 6L * 60 * 60 * 1_000 - 1
        resolver.resolve("Книга", "Автор", mergeKey)
        assertEquals("still fresh a millisecond before the boundary", 1, searches.total)

        now += 1
        resolver.resolve("Книга", "Автор", mergeKey)
        assertEquals("re-volleys at the exact expiry boundary", 2, searches.total)
    }

    @Test
    fun `no match is a memoized negative verdict - 15 minutes, then re-asked`() = runTest {
        var now = 1_000_000L
        val searches = CountingSearches(
            "sluhayua" to listOf(
                directBook("sluhayua", "https://sluhay.com.ua/other", title = "Інша книга", author = "Інший автор")
            )
        )
        // No cache, same reason as the positive-TTL test: the memo is the
        // verdict under test, and negatives are never cached anyway.
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = null,
            clock = { now }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
        assertEquals(1, searches.total)

        now += 15L * 60 * 1_000 - 1
        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
        assertEquals("negative verdict still fresh before the boundary", 1, searches.total)

        now += 1
        assertNull(resolver.resolve("Книга", "Автор", mergeKey))
        assertEquals("re-volleys at the exact negative expiry", 2, searches.total)
    }

    @Test
    fun `merge key mismatch is a negative verdict, never a near miss`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(
                directBook("sluhayua", "https://sluhay.com.ua/other", title = "Книганkа", author = "Автор Прізвище")
            )
        )
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = FakeCache()
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertNull(match)
        assertEquals(1, searches.total)
    }

    @Test
    fun `one source failing does not break the volley`() = runTest {
        var sluhayuaAttempts = 0
        val failingSearch: suspend (String) -> List<SourceBook> = { _: String ->
            sluhayuaAttempts += 1
            throw RuntimeException("source down")
        }
        val soundbooksSearch: suspend (String) -> List<SourceBook> = { _: String ->
            listOf(directBook("soundbooks", "https://sound-books.net/1"))
        }
        val searches = mapOf("sluhayua" to failingSearch, "soundbooks" to soundbooksSearch)
        val resolver = SourceReplacementMapping(
            directSearches = searches,
            union = { emptyList() },
            cache = FakeCache()
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertEquals("https://sound-books.net/1", match?.url)
        assertEquals("the failing source was attempted once and never retried", 1, sluhayuaAttempts)
    }

    @Test
    fun `cached browser-source card never becomes a direct match - the volley decides`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val cache = FakeCache()
        val cachedResults = mergeGlobalSearchResults(listOf(fourReadBook()))
        cache.documents[SearchQueryKey.normalize("Книга Автор")!!] =
            SearchResultCodec.toMap(1_000_000L, cachedResults)
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = cache
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        // The 4read card matches the MergeKey but is a BROWSER source — the
        // replacement mapping exists to leave exactly that door behind.
        assertEquals("https://sluhay.com.ua/42", match?.url)
        assertEquals("sluhayua", match?.sourceId)
        assertEquals(1, searches.total)
    }

    @Test
    fun `cached mixed card serves its direct member without a volley`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val cache = FakeCache()
        val mixed = mergeGlobalSearchResults(
            listOf(fourReadBook(), directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        cache.documents[SearchQueryKey.normalize("Книга Автор")!!] =
            SearchResultCodec.toMap(1_000_000L, mixed)
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = cache
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertEquals("https://sluhay.com.ua/42", match?.url)
        assertEquals(0, searches.total)
    }

    @Test
    fun `direct preference inside one card follows the shared capability order`() = runTest {
        val card = mergeGlobalSearchResults(
            listOf(
                directBook("lihtar", "https://lihtar.in.ua/7"),
                directBook("soundbooks", "https://sound-books.net/1")
            )
        )
        val resolver = SourceReplacementMapping(
            directSearches = emptyMap(),
            union = { card },
            cache = FakeCache()
        )

        val match = resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))

        assertEquals("soundbooks", match?.sourceId)
    }

    @Test
    fun `the volley is one parallel sweep, not a sequential crawl`() = runTest {
        val soundbooksStarted = CompletableDeferred<Unit>()
        val blockedSearch: suspend (String) -> List<SourceBook> = { _: String ->
            // sluhayua cannot finish until soundbooks has STARTED — only
            // a parallel sweep can ever resolve this.
            soundbooksStarted.await()
            emptyList()
        }
        val completingSearch: suspend (String) -> List<SourceBook> = { _: String ->
            soundbooksStarted.complete(Unit)
            listOf(directBook("soundbooks", "https://sound-books.net/1"))
        }
        val searches = mapOf("sluhayua" to blockedSearch, "soundbooks" to completingSearch)
        val resolver = SourceReplacementMapping(
            directSearches = searches,
            union = { emptyList() },
            cache = FakeCache()
        )

        val match = withTimeout(5_000) {
            resolver.resolve("Книга", "Автор", MergeKey.keyFor("Книга", "Автор"))
        }

        assertEquals("https://sound-books.net/1", match?.url)
    }

    @Test
    fun `the shared cache outlives the memo - expired positive still answered with zero requests`() = runTest {
        var now = 1_000_000L
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val cache = FakeCache()
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = cache,
            clock = { now }
        )
        val mergeKey = MergeKey.keyFor("Книга", "Автор")

        resolver.resolve("Книга", "Автор", mergeKey)
        val afterFirst = searches.total // the initial miss volley
        now += 6L * 60 * 60 * 1_000 + 1

        // Past the memo boundary the fresh shared-cache entry (24h) answers
        // alone — no further requests, never a fabricated refresh.
        assertEquals("https://sluhay.com.ua/42", resolver.resolve("Книга", "Автор", mergeKey)?.url)
        assertEquals(afterFirst, searches.total)
    }

    @Test
    fun `blank merge key is never a mapping question`() = runTest {
        val searches = CountingSearches(
            "sluhayua" to listOf(directBook("sluhayua", "https://sluhay.com.ua/42"))
        )
        val cache = FakeCache()
        val resolver = SourceReplacementMapping(
            directSearches = searches.searches,
            union = { emptyList() },
            cache = cache
        )

        assertNull(resolver.resolve("Книга", "Автор", ""))
        assertEquals(0, searches.total)
        assertTrue(cache.written.isEmpty())
    }
}
