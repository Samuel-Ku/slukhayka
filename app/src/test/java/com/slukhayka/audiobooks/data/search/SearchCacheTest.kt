package com.slukhayka.audiobooks.data.search

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-33 T1 (#229) — the [SearchCache] seam over a fake in-memory document
 * store (prior art: the universe store fixture tests): get/put round-trip,
 * query-key normalization, the ~24-hour freshness window and the
 * no-negative-cache rule. The seam never throws — a failing or corrupt
 * store is a miss, exactly what degrade-never promises.
 */
class SearchCacheTest {

    private val card = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        mergeKey = "кобзар|тарас шевченко",
        coverImageUrl = "https://4read.org/covers/kobzar.jpg",
        durationSeconds = 7_200,
        sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/kobzar"))
    )

    private class FakeStore(
        var nowMillis: Long = 1_000_000L,
        var failReads: Boolean = false
    ) : SearchCache {
        val documents = mutableMapOf<String, Map<String, Any>>()

        override suspend fun readDocument(queryKey: String): Map<String, Any>? {
            if (failReads) throw IllegalStateException("store is down")
            return documents[queryKey]
        }

        override suspend fun writeDocument(queryKey: String, document: Map<String, Any>) {
            documents[queryKey] = document
        }

        override fun nowMillis(): Long = nowMillis
    }

    @Test
    fun `a miss on an empty store is null`() = runBlocking {
        val store = FakeStore()

        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `a put round-trips through the seam`() = runBlocking {
        val store = FakeStore()

        store.putResults("Шевченко", listOf(card))
        store.nowMillis += 60_000

        assertEquals(listOf(card), store.getResults("Шевченко"))
    }

    @Test
    fun `the query key is normalized - trim, case-fold and space collapse`() = runBlocking {
        val store = FakeStore()

        store.putResults("  Шевченко  ", listOf(card))
        store.putResults("Гаррі   Поттер", listOf(card))

        assertEquals(listOf(card), store.getResults("шевченко"))
        assertEquals(listOf(card), store.getResults("гаррі поттер"))
        assertEquals(listOf(card), store.getResults("   ГАРРІ ПОТТЕР   "))
    }

    @Test
    fun `a blank query is a miss and a put no-op`() = runBlocking {
        val store = FakeStore()

        assertNull(store.getResults("   "))
        store.putResults("  ", listOf(card))
        assertTrue(store.documents.isEmpty())
    }

    @Test
    fun `a fresh entry is a hit and a day-old entry is a miss`() = runBlocking {
        val store = FakeStore()

        store.putResults("Шевченко", listOf(card))
        store.nowMillis += SearchFreshness.FRESHNESS_MILLIS - 3_600_000
        assertEquals(listOf(card), store.getResults("Шевченко"))

        store.nowMillis += 3_700_000
        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `an entry exactly at the freshness bound is a miss`() = runBlocking {
        val store = FakeStore()

        store.putResults("Шевченко", listOf(card))
        store.nowMillis += SearchFreshness.FRESHNESS_MILLIS

        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `an entry stamped in the future is a miss, never a forever-fresh pin`() = runBlocking {
        // A device whose clock runs ahead would write a fetchedAt far in the
        // future; without the guard the entry would stay "fresh" for years,
        // pinning a stale result for every listener.
        val store = FakeStore()
        store.documents["шевченко"] = SearchResultCodec.toMap(
            fetchedAt = store.nowMillis + 365L * 24 * 60 * 60 * 1000,
            results = listOf(card)
        )

        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `an empty result list is not written - negatives are never cached`() = runBlocking {
        val store = FakeStore()

        store.putResults("Шевченко", emptyList())

        assertTrue(store.documents.isEmpty())
        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `a corrupt document is a miss, never a crash`() = runBlocking {
        val store = FakeStore()
        store.documents["шевченко"] = mapOf("fetchedAt" to "вчора")

        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `a failing store read is a miss, never a crash`() = runBlocking {
        val store = FakeStore(failReads = true)

        assertNull(store.getResults("Шевченко"))
    }

    @Test
    fun `a stale entry is a miss and re-put refreshes it`() = runBlocking {
        val store = FakeStore()

        store.putResults("Шевченко", listOf(card))
        store.nowMillis += SearchFreshness.FRESHNESS_MILLIS + 60_000
        assertNull(store.getResults("Шевченко"))

        store.putResults("Шевченко", listOf(card))
        assertEquals(listOf(card), store.getResults("Шевченко"))
    }
}
