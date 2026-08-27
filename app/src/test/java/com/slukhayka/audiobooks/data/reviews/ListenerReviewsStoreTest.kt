package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec-40 #277 — the [ListenerReviewsStore] seam over an in-memory document
 * transport (prior art: the SearchCache fixture tests). The POLICY under
 * test lives in the seam's default methods: put/get round-trip, newest-first
 * ordering independent of transport order, corrupt documents contributing
 * nothing, batch gaps contributing nothing, and the honest booleans on
 * put/delete — including degrade-never when the transport fails.
 */
class ListenerReviewsStoreTest {

    /** The minimal in-memory transport the tests drive. */
    private class FakeDocuments(
        var failReads: Boolean = false,
        var failWrites: Boolean = false,
        var cancelWrites: Boolean = false,
        var scrambleOrder: Boolean = true,
        val remoteWriteResult: CompletableDeferred<ReviewRemoteResult> =
            CompletableDeferred(ReviewRemoteResult.PUBLISHED)
    ) {
        val documents = mutableMapOf<String, Map<String, Any>>()

        fun seed(vararg reviews: ListenerReview) {
            reviews.forEach { documents[ListenerReviewCodec.documentId(it.workId, it.uid)] = ListenerReviewCodec.toMap(it) }
        }
    }

    private class FakeStore(private val fake: FakeDocuments) : ListenerReviewsStore {
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> {
            if (fake.failReads) throw IllegalStateException("transport down")
            return fake.documents.values
                .filter { it["workId"] == workId }
                .let { if (fake.scrambleOrder) it.reversed() else it }
        }

        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> {
            if (fake.failReads) throw IllegalStateException("transport down")
            return fake.documents.values
                .filter { it["workId"] in workIds.toSet() }
        }

        override suspend fun enqueueDocument(
            documentId: String,
            document: Map<String, Any>
        ): ReviewWriteReceipt {
            if (fake.cancelWrites) throw CancellationException("write cancelled")
            if (fake.failWrites) throw IllegalStateException("transport down")
            fake.documents[documentId] = document
            return ReviewWriteReceipt.Queued { fake.remoteWriteResult.await() }
        }

        override suspend fun removeDocument(documentId: String): Boolean {
            if (fake.cancelWrites) throw CancellationException("delete cancelled")
            if (fake.failWrites) throw IllegalStateException("transport down")
            return fake.documents.remove(documentId) != null
        }
    }

    private fun review(
        uid: String,
        createdAt: Long,
        rating: Int = 5,
        workId: String = "work-1",
        body: String? = "Гарна книга"
    ) = ListenerReview(
        workId = workId,
        uid = uid,
        authorName = "Читач_$uid",
        rating = rating,
        body = body,
        editionTag = null,
        createdAt = createdAt
    )

    @Test
    fun `a put round-trips through getReviews`() = runBlocking {
        val store = FakeStore(FakeDocuments())
        val r = review(uid = "u1", createdAt = 100L)

        assertTrue(store.putReview(r))
        assertEquals(listOf(r), store.getReviews("work-1"))
    }

    @Test
    fun `enqueue reports local queue before the remote verdict`() = runBlocking {
        val remote = CompletableDeferred<ReviewRemoteResult>()
        val store = FakeStore(FakeDocuments(remoteWriteResult = remote))

        val receipt = store.enqueueReview(review(uid = "u1", createdAt = 100L))

        assertTrue(receipt is ReviewWriteReceipt.Queued)
        val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
            (receipt as ReviewWriteReceipt.Queued).awaitRemote()
        }
        yield()
        assertFalse(acknowledgement.isCompleted)

        remote.complete(ReviewRemoteResult.FAILED)
        assertEquals(ReviewRemoteResult.FAILED, acknowledgement.await())
    }

    @Test
    fun `remote acknowledgement failure degrades to a failed verdict`() = runBlocking {
        val receipt = ReviewWriteReceipt.Queued {
            throw IllegalStateException("backend listener failed")
        }

        assertEquals(ReviewRemoteResult.FAILED, receipt.awaitRemote())
    }

    @Test
    fun `remote acknowledgement still propagates caller cancellation`() = runBlocking {
        val receipt = ReviewWriteReceipt.Queued {
            throw CancellationException("caller left")
        }

        assertCancellationPropagates { receipt.awaitRemote() }
    }

    @Test
    fun `a re-edit of the same key replaces - never duplicates`() = runBlocking {
        val store = FakeStore(FakeDocuments())
        val first = review(uid = "u1", createdAt = 100L, rating = 3)
        val edited = first.copy(rating = 5, body = "Переоцінив")

        assertTrue(store.putReview(first))
        assertTrue(store.putReview(edited))

        val reviews = store.getReviews("work-1")
        assertEquals(1, reviews.size)
        assertEquals(edited, reviews.single())
    }

    @Test
    fun `getReviews is newest first regardless of transport order`() = runBlocking {
        val fake = FakeDocuments()
        fake.seed(review("u-old", createdAt = 10L), review("u-new", createdAt = 30L), review("u-mid", createdAt = 20L))
        val store = FakeStore(fake)

        assertEquals(listOf(30L, 20L, 10L), store.getReviews("work-1").map { it.createdAt })
    }

    @Test
    fun `a corrupt document contributes nothing - its neighbours survive`() = runBlocking {
        val fake = FakeDocuments()
        fake.seed(review("u-good", createdAt = 20L))
        fake.documents["corrupt"] = mapOf("workId" to "work-1", "rating" to "чудово", "createdAt" to 99L)
        fake.documents[ListenerReviewCodec.documentId("work-1", "u-bad")] =
            ListenerReviewCodec.toMap(review("u-bad", createdAt = 30L)) + ("rating" to 9)

        val reviews = FakeStore(fake).getReviews("work-1")

        assertEquals(listOf(20L), reviews.map { it.createdAt })
        assertEquals("u-good", reviews.single().uid)
    }

    @Test
    fun `getForWorks batches across works - gaps contribute nothing`() = runBlocking {
        val fake = FakeDocuments()
        fake.seed(
            review("u1", createdAt = 10L, workId = "w-a"),
            review("u2", createdAt = 20L, workId = "w-a"),
            review("u3", createdAt = 30L, workId = "w-b"),
            review("u4", createdAt = 40L, workId = "w-c")
        )
        val store = FakeStore(fake)

        val result = store.getForWorks(listOf("w-a", "w-b", "w-empty"))

        // w-c was not asked for; w-empty has no reviews and is absent.
        assertEquals(setOf("w-a", "w-b"), result.keys)
        assertEquals(listOf(20L, 10L), result["w-a"]?.map { it.createdAt })
        assertEquals(listOf(30L), result["w-b"]?.map { it.createdAt })
    }

    @Test
    fun `an invalid review is refused with false before any IO`() = runBlocking {
        val fake = FakeDocuments()
        val store = FakeStore(fake)

        assertFalse(store.putReview(review("u1", createdAt = 1L).copy(rating = 0)))
        assertFalse(store.putReview(review("u1", createdAt = 1L).copy(rating = 6)))
        assertFalse(store.putReview(review("u1", createdAt = 1L).copy(workId = "  ")))
        assertFalse(store.putReview(review("u1", createdAt = 1L).copy(uid = "")))
        assertFalse(store.putReview(review("u1", createdAt = 1L).copy(authorName = "")))

        assertTrue(fake.documents.isEmpty())
    }

    @Test
    fun `delete reports what happened - true for a hit, false for a miss`() = runBlocking {
        val store = FakeStore(FakeDocuments())

        assertTrue(store.putReview(review("u1", createdAt = 1L)))
        assertTrue(store.deleteReview("work-1", "u1"))
        assertFalse(store.deleteReview("work-1", "u1"))
        assertTrue(store.getReviews("work-1").isEmpty())
    }

    @Test
    fun `a failing read is empty and a failing write is false - never a crash`() = runBlocking {
        val store = FakeStore(FakeDocuments(failReads = true, failWrites = true))

        assertTrue(store.getReviews("work-1").isEmpty())
        assertTrue(store.getForWorks(listOf("work-1")).isEmpty())
        assertFalse(store.putReview(review("u1", createdAt = 1L)))
        assertFalse(store.deleteReview("work-1", "u1"))
    }

    @Test
    fun `write and delete never swallow coroutine cancellation`() = runBlocking {
        val store = FakeStore(FakeDocuments(cancelWrites = true))

        assertCancellationPropagates { store.putReview(review("u1", createdAt = 1L)) }
        assertCancellationPropagates { store.deleteReview("work-1", "u1") }
    }

    private suspend fun assertCancellationPropagates(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Cancellation is control flow: the policy seam must rethrow it.
        }
    }
}
