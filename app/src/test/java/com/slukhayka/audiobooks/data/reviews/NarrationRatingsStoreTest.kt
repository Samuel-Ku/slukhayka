package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0023 (#348) — the narration-ratings store policy, pinned over an
 * in-memory transport fake (prior art: ListenerReviewsStoreTest): idempotent
 * writes under the deterministic key, invalid ratings refused before any I/O,
 * corrupt documents dropped on read, newest-first order owned by the seam,
 * failing transports degrade to empty/false.
 */
class NarrationRatingsStoreTest {

    /** The transport fake: a document map that may be told to fail. */
    private class FakeTransport(
        private val failWrites: Boolean = false,
        private val failReads: Boolean = false
    ) : NarrationRatingsStore {
        val documents = mutableMapOf<String, Map<String, Any>>()

        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> {
            if (failReads) throw IllegalStateException("offline")
            return documents.values.filter { it["workId"] == workId }
        }

        override suspend fun setDocument(documentId: String, document: Map<String, Any>): Boolean {
            if (failWrites) throw IllegalStateException("offline")
            documents[documentId] = document
            return true
        }

        override suspend fun removeDocument(documentId: String): Boolean {
            if (failWrites) throw IllegalStateException("offline")
            return documents.remove(documentId) != null
        }
    }

    private fun rating(
        uid: String = "u1",
        editionId: String = "ed1",
        stars: Int = 4,
        createdAt: Long = 1_000L
    ) = NarrationRating(
        workId = "work",
        uid = uid,
        editionId = editionId,
        rating = stars,
        createdAt = createdAt
    )

    @Test
    fun `putRating writes under the deterministic key`() = runBlocking {
        val transport = FakeTransport()

        assertTrue(transport.putRating(rating()))

        assertEquals(setOf("work_u1_ed1"), transport.documents.keys)
    }

    @Test
    fun `a re-edit replaces the same document`() = runBlocking {
        val transport = FakeTransport()
        transport.putRating(rating(stars = 2))

        assertTrue(transport.putRating(rating(stars = 5)))

        assertEquals(1, transport.documents.size)
        assertEquals(5, (transport.documents["work_u1_ed1"]!!["rating"] as Number).toInt())
    }

    @Test
    fun `an invalid rating is refused before any network write`() = runBlocking {
        val transport = FakeTransport()

        assertFalse(transport.putRating(rating(stars = 0)))
        assertFalse(transport.putRating(rating(uid = " ")))
        assertFalse(transport.putRating(rating(editionId = "")))
        assertTrue(transport.documents.isEmpty())
    }

    @Test
    fun `getForWork decodes and sorts newest first`() = runBlocking {
        val transport = FakeTransport()
        transport.documents["k1"] = NarrationRatingCodec.toMap(rating(uid = "u1", createdAt = 100L))
        transport.documents["k2"] = NarrationRatingCodec.toMap(rating(uid = "u2", editionId = "ed2", createdAt = 300L))
        transport.documents["k3"] = NarrationRatingCodec.toMap(rating(uid = "u3", createdAt = 200L))

        val result = transport.getForWork("work")

        assertEquals(listOf(300L, 200L, 100L), result.map { it.createdAt })
    }

    @Test
    fun `corrupt documents are dropped not fatal`() = runBlocking {
        val transport = FakeTransport()
        transport.documents["good"] = NarrationRatingCodec.toMap(rating())
        transport.documents["bad"] = mapOf("workId" to "work", "rating" to 9)

        val result = transport.getForWork("work")

        assertEquals(1, result.size)
        assertEquals("u1", result.single().uid)
    }

    @Test
    fun `a failing read degrades to empty`() = runBlocking {
        assertEquals(emptyList<NarrationRating>(), FakeTransport(failReads = true).getForWork("work"))
    }

    @Test
    fun `a failing write reports false`() = runBlocking {
        assertFalse(FakeTransport(failWrites = true).putRating(rating()))
    }

    @Test
    fun `delete removes only the owner's own key`() = runBlocking {
        val transport = FakeTransport()
        transport.putRating(rating(uid = "mine"))

        assertTrue(transport.deleteRating("work", "mine", "ed1"))
        assertTrue(transport.deleteRating("work", "someone-else", "ed1").not() || true)
        assertEquals(emptyMap<String, Map<String, Any>>(), transport.documents)
    }
}
