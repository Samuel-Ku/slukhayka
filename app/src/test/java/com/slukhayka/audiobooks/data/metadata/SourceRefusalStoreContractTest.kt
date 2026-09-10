package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-49 T5 — the shared refusal aggregate contract, against the Fake:
 * one anonymous counter per sourceId, one device counted once, honest
 * degradation when the shared base is down. Prior art: the shared
 * store-contract tests (facets, tombstones, submissions).
 */
class SourceRefusalStoreContractTest {

    @Test
    fun `first vote counts once`() = runBlocking {
        val store = FakeSharedBookMetaStore()

        assertTrue(store.publishRefusalVote("4read", "uid-1"))

        assertEquals(1L, store.getRefusalCount("4read"))
    }

    @Test
    fun `one device counts once - a repeat vote is a no-op`() = runBlocking {
        val store = FakeSharedBookMetaStore()

        assertTrue(store.publishRefusalVote("4read", "uid-1"))
        assertTrue("already counted, still a success", store.publishRefusalVote("4read", "uid-1"))

        assertEquals(1L, store.getRefusalCount("4read"))
    }

    @Test
    fun `a second device increments the same source counter`() = runBlocking {
        val store = FakeSharedBookMetaStore()

        store.publishRefusalVote("4read", "uid-1")
        store.publishRefusalVote("4read", "uid-2")

        assertEquals(2L, store.getRefusalCount("4read"))
    }

    @Test
    fun `blank identities never publish`() = runBlocking {
        val store = FakeSharedBookMetaStore()

        assertFalse(store.publishRefusalVote("", "uid-1"))
        assertFalse(store.publishRefusalVote("4read", ""))
        assertFalse(store.publishRefusalVote("  ", "  "))

        assertEquals(0L, store.getRefusalCount("4read"))
        assertEquals(0L, store.getRefusalCount(""))
    }

    @Test
    fun `unknown sources read zero and stay out of the batch`() = runBlocking {
        val store = FakeSharedBookMetaStore()
        store.publishRefusalVote("4read", "uid-1")

        assertEquals(0L, store.getRefusalCount("sluhayua"))
        assertEquals(mapOf("4read" to 1L), store.getRefusalCounts(listOf("4read", "sluhayua")))
        assertEquals(emptyMap<String, Long>(), store.getRefusalCounts(emptyList()))
    }

    @Test
    fun `a down shared base degrades honestly - no throw, no badge`() = runBlocking {
        val store = FakeSharedBookMetaStore(refusalsDown = true)

        assertFalse(store.publishRefusalVote("4read", "uid-1"))
        assertEquals(0L, store.getRefusalCount("4read"))
        assertEquals(emptyMap<String, Long>(), store.getRefusalCounts(listOf("4read")))
    }
}
