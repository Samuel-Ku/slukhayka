package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.identity.FakeListenerIdentity
import com.slukhayka.audiobooks.data.identity.LocalOnlyIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * #581 W0.3 — the JVM test of [WorkRelationshipsSync] (prior art:
 * [ProgressSyncControllerTest]): the honest moments push through the seam,
 * every gate writes nothing. No Android, no Firebase, no Room.
 */
class WorkRelationshipsSyncTest {

    private class FakeTransport : WorkRelationshipsStore {
        val documents = mutableMapOf<String, Map<String, Any>>()
        var serverStamp: Long? = 1234L

        override suspend fun fetchDocument(documentId: String): Map<String, Any>? = documents[documentId]

        override suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean {
            // Emulate the server: Firestore merges the serverTimestamp into
            // the stored document; the client never wrote a clock itself.
            documents[documentId] = fields + (WorkRelationshipsCodec.FIELD_UPDATED_AT to (serverStamp ?: 0L))
            return true
        }

        override suspend fun readServerUpdatedAtMs(documentId: String): Long? = serverStamp
    }

    private fun identityFor(uid: String): FakeListenerIdentity =
        // Deterministic uid-driven gates (the web tests use the same shape).
        FakeListenerIdentity(Random(42), com.slukhayka.audiobooks.data.identity.ListenerProfile(uid, "Нік"))

    @Test
    fun `pushEntry writes an entry row under the bound identity`() = runBlocking {
        val transport = FakeTransport()
        val sync = WorkRelationshipsSync(identityFor("listener-1"), transport)

        sync.pushEntry("k|a", "Книга", "Автор")

        val row = transport.pull("listener-1", "k|a")
        assertNotNull(row)
        assertEquals(WorkRelationshipsCodec.STATE_ENTRY, row!!.state)
        assertEquals("Книга", row.title)
    }

    @Test
    fun `pushTombstone writes a tombstone with mergeKey provenance`() = runBlocking {
        val transport = FakeTransport()
        val sync = WorkRelationshipsSync(identityFor("listener-1"), transport)

        sync.pushTombstone("k|a")

        val row = transport.pull("listener-1", "k|a")
        assertEquals(WorkRelationshipsCodec.STATE_TOMBSTONE, row!!.state)
        assertEquals("k|a", row.title)
    }

    @Test
    fun `a local-identity profile writes nothing`() = runBlocking {
        val transport = FakeTransport()
        val sync = WorkRelationshipsSync(identityFor(LocalOnlyIdentity.LOCAL_UID_PREFIX + "abc"), transport)

        sync.pushEntry("k|a", "Книга", "Автор")

        assertTrue(transport.documents.isEmpty())
    }

    @Test
    fun `a switched-off toggle writes nothing and a null store is a no-op`() = runBlocking {
        val transport = FakeTransport()
        var enabled = false
        val sync = WorkRelationshipsSync(identityFor("listener-1"), transport) { enabled }
        sync.pushEntry("k|a", "Книга", "Автор")
        assertTrue(transport.documents.isEmpty())

        enabled = true
        val syncNoStore = WorkRelationshipsSync(identityFor("listener-1"), null) { true }
        syncNoStore.pushEntry("k|a", "Книга", "Автор") // must not throw
        assertTrue(transport.documents.isEmpty())
    }

    @Test
    fun `a blank mergeKey has no cloud anchor and writes nothing`() = runBlocking {
        val transport = FakeTransport()
        val sync = WorkRelationshipsSync(identityFor("listener-1"), transport)

        sync.pushEntry("", "Книга", "Автор")

        assertTrue(transport.documents.isEmpty())
    }
}
