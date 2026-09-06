package com.slukhayka.audiobooks.data.listening

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #581 W0.3 — the seam test of [WorkRelationshipsStore] over an in-memory
 * transport (prior art: [ListenerProgressSyncStoreTest]) AND the
 * cross-platform lockstep vectors for the web's `workRelationships.ts`
 * tests: the codec, the document id shape and the LWW rule are pinned
 * IDENTICALLY on both platforms, so a web row and a Kotlin row are the same
 * document.
 */
class WorkRelationshipsStoreTest {

    private class FakeTransport : WorkRelationshipsStore {
        val documents = mutableMapOf<String, Map<String, Any>>()
        var failWrites = false
        var serverStamp: Long? = 1234L

        override suspend fun fetchDocument(documentId: String): Map<String, Any>? {
            return documents[documentId]
        }

        override suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean {
            if (failWrites) return false
            // Emulate the server: Firestore merges the serverTimestamp into
            // the stored document; the client never wrote a clock itself.
            val stamp = serverStamp
            documents[documentId] =
                if (stamp != null) fields + (WorkRelationshipsCodec.FIELD_UPDATED_AT to stamp) else fields
            return true
        }

        override suspend fun readServerUpdatedAtMs(documentId: String): Long? = serverStamp
    }

    private fun row(
        mergeKey: String = "титул|автор",
        state: String = WorkRelationshipsCodec.STATE_ENTRY,
        serverMs: Long = 100L,
        title: String = "Книга",
        author: String = "Автор",
    ) = WorkRelationshipRow(
        mergeKey = mergeKey,
        state = state,
        title = title,
        author = author,
        uid = "listener",
        updatedAtServerMs = serverMs,
    )

    // -------------------------------------------------------------------
    // Codec — the cross-platform contract vectors
    // -------------------------------------------------------------------

    @Test
    fun `document id mirrors the listening_state key shape`() {
        assertEquals("listener_титул|автор", WorkRelationshipsCodec.documentId("listener", "титул|автор"))
    }

    @Test
    fun `toDocument never carries a client-forged updatedAt`() {
        val doc = WorkRelationshipsCodec.toDocument("listener", row())
        assertFalse(doc.containsKey(WorkRelationshipsCodec.FIELD_UPDATED_AT))
        assertEquals("listener", doc[WorkRelationshipsCodec.FIELD_UID])
        assertEquals("entry", doc[WorkRelationshipsCodec.FIELD_STATE])
    }

    @Test
    fun `codec round-trips a row`() {
        val doc = WorkRelationshipsCodec.toDocument("listener", row(serverMs = 777L))
        val decoded = WorkRelationshipsCodec.fromDocument(
            doc + mapOf(WorkRelationshipsCodec.FIELD_UPDATED_AT to 777L)
        )
        assertEquals(row(serverMs = 777L), decoded)
    }

    @Test
    fun `codec rejects malformed documents as an honest miss`() {
        val good = WorkRelationshipsCodec.toDocument("listener", row())
            .plus(WorkRelationshipsCodec.FIELD_UPDATED_AT to 100L)
        assertNull(WorkRelationshipsCodec.fromDocument(null))
        assertNull(WorkRelationshipsCodec.fromDocument(good.plus("rogue" to 1))) // closed shape
        assertNull(WorkRelationshipsCodec.fromDocument(good.minus(WorkRelationshipsCodec.FIELD_UID))) // missing field
        assertNull(
            WorkRelationshipsCodec.fromDocument(
                good.plus(WorkRelationshipsCodec.FIELD_STATE to "erased")
            )
        )
        assertNull(
            WorkRelationshipsCodec.fromDocument(
                good.plus(WorkRelationshipsCodec.FIELD_MERGE_KEY to "")
            )
        )
        assertNull(
            WorkRelationshipsCodec.fromDocument(
                good.plus(WorkRelationshipsCodec.FIELD_UPDATED_AT to 0L)
            )
        )
    }

    // -------------------------------------------------------------------
    // Policy — the shared LWW rule (identical vectors to the web tests)
    // -------------------------------------------------------------------

    @Test
    fun `a strictly newer row wins regardless of state`() {
        val local = row(state = WorkRelationshipsCodec.STATE_ENTRY, serverMs = 100L)
        val incoming = row(state = WorkRelationshipsCodec.STATE_TOMBSTONE, serverMs = 200L)
        assertTrue(WorkRelationshipsPolicy.merge(local, incoming) === incoming)
        assertTrue(WorkRelationshipsPolicy.merge(incoming, local) === incoming)
    }

    @Test
    fun `an older incoming row never resurrects a newer tombstone`() {
        val local = row(state = WorkRelationshipsCodec.STATE_TOMBSTONE, serverMs = 300L)
        val incoming = row(state = WorkRelationshipsCodec.STATE_ENTRY, serverMs = 200L)
        assertTrue(WorkRelationshipsPolicy.merge(local, incoming) === local)
    }

    @Test
    fun `a server-time tie goes to the tombstone from either side`() {
        val local = row(state = WorkRelationshipsCodec.STATE_ENTRY, serverMs = 100L)
        val incoming = row(state = WorkRelationshipsCodec.STATE_TOMBSTONE, serverMs = 100L)
        assertTrue(WorkRelationshipsPolicy.merge(local, incoming) === incoming)
        assertTrue(WorkRelationshipsPolicy.merge(incoming, local) === incoming)
    }

    @Test
    fun `a tie between equal states keeps the incoming display data`() {
        val local = row(title = "Старе", serverMs = 100L)
        val incoming = row(title = "Нове", serverMs = 100L)
        assertEquals("Нове", WorkRelationshipsPolicy.merge(local, incoming).title)
    }
    // -------------------------------------------------------------------
    // Seam over the in-memory transport
    // -------------------------------------------------------------------

    @Test
    fun `push writes the codec document and returns the server stamp`() = runBlocking {
        val transport = FakeTransport()
        val stamp = transport.push("listener", row())

        assertEquals(1234L, stamp)
        val stored = transport.documents[WorkRelationshipsCodec.documentId("listener", "титул|автор")]
        assertNotNull(stored)
        // The write request never carried a client-forged clock — the stamp
        // below is the transport's serverTimestamp emulation, not the payload.
        assertFalse(
            WorkRelationshipsCodec.toDocument("listener", row())
                .containsKey(WorkRelationshipsCodec.FIELD_UPDATED_AT)
        )
        assertEquals(1234L, stored!![WorkRelationshipsCodec.FIELD_UPDATED_AT])
    }

    @Test
    fun `pull decodes the stored row and misses honestly`() = runBlocking {
        val transport = FakeTransport()
        assertNull(transport.pull("listener", "титул|автор"))
        transport.push("listener", row(state = WorkRelationshipsCodec.STATE_TOMBSTONE))
        assertEquals(
            WorkRelationshipsCodec.STATE_TOMBSTONE,
            transport.pull("listener", "титул|автор")?.state,
        )
    }

    @Test
    fun `a failing transport degrades to null, never an exception`() = runBlocking {
        val transport = FakeTransport().apply { failWrites = true }
        assertNull(transport.push("listener", row()))
        assertNull(transport.pull("listener", "титул|автор"))
    }
}
