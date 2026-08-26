package com.slukhayka.audiobooks.data.listening

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0023 (spec-43 T6) — the seam test of [ListenerProgressSyncStore] over
 * an in-memory transport (prior art: ListenerReviewsStoreTest): the policy
 * lives in the seam's default methods, the transport only moves documents.
 */
class ListenerProgressSyncStoreTest {

    private class FakeTransport : ListenerProgressSyncStore {
        val documents = mutableMapOf<String, Map<String, Any>>()
        var failWrites = false
        var failFetches = false
        /** The server stamp the transport hands back after an accepted write. */
        var serverStamp: Long? = 1234L

        override suspend fun fetchDocument(documentId: String): Map<String, Any>? {
            if (failFetches) throw IllegalStateException("transport down")
            return documents[documentId]
        }

        override suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean {
            if (failWrites) throw IllegalStateException("transport down")
            documents[documentId] = fields
            return true
        }

        override suspend fun readServerUpdatedAtMs(documentId: String): Long? = serverStamp
    }

    private fun payload(editionId: String = "ed-1", positionSeconds: Long = 30L) =
        RemoteListeningState(
            editionId = editionId,
            chapterIndex = 1,
            positionSeconds = positionSeconds,
            isCompleted = false,
            preferredSpeed = null,
            updatedAtServerMs = 0L // stamped by the server at the transport
        )

    @Test
    fun `push writes the codec document and returns the server stamp`() = runBlocking {
        val transport = FakeTransport()
        val stamp = transport.push("listener", payload())

        assertEquals(1234L, stamp)
        val stored = transport.documents["listener_ed-1"]
        assertNotNull(stored)
        assertEquals("listener", stored!![ProgressSyncCodec.FIELD_UID])
        assertEquals("ed-1", stored[ProgressSyncCodec.FIELD_EDITION_ID])
        assertFalse(stored.containsKey(ProgressSyncCodec.FIELD_UPDATED_AT)) // never client-forged
    }

    @Test
    fun `a failing push is silent and stampless`() = runBlocking {
        val transport = FakeTransport().apply { failWrites = true }
        assertNull(transport.push("listener", payload()))
        assertTrue(transport.documents.isEmpty())
    }

    @Test
    fun `pull decodes a stored document`() = runBlocking {
        val transport = FakeTransport()
        transport.push("listener", payload(positionSeconds = 77L))
        transport.documents["listener_ed-1"] =
            transport.documents["listener_ed-1"]!! + mapOf(ProgressSyncCodec.FIELD_UPDATED_AT to 9999L)

        val remote = transport.pull("listener", "ed-1")

        assertNotNull(remote)
        assertEquals(77L, remote!!.positionSeconds)
        assertEquals(9999L, remote.updatedAtServerMs)
    }

    @Test
    fun `pull misses silently on absence, corruption or failure`() = runBlocking {
        val empty = FakeTransport()
        assertNull(empty.pull("listener", "ed-1"))

        val corrupt = FakeTransport().apply {
            documents["listener_ed-1"] =
                mapOf(ProgressSyncCodec.FIELD_EDITION_ID to "ed-1") // everything else missing
        }
        assertNull(corrupt.pull("listener", "ed-1"))

        val failing = FakeTransport().apply { failFetches = true }
        assertNull(failing.pull("listener", "ed-1"))
    }
}
