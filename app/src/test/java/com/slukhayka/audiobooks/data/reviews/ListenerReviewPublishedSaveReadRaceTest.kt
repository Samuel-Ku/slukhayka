package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One private public seam tracer for read-before-save but publication-after-save. */
class ListenerReviewPublishedSaveReadRaceTest {
    private class HeldStore(val initial: ReviewQuerySnapshot) : ListenerReviewsStore {
        val oldReply = CompletableDeferred<ReviewQuerySnapshot>()
        var reads = 0
        override suspend fun queryWorkSnapshot(workId: String): ReviewQuerySnapshot {
            assertEquals("work", workId)
            reads += 1
            return if (reads == 1) initial else oldReply.await()
        }
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> =
            error("Use the public metadata result")
        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> =
            error("No batch reads in this tracer")
        override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt {
            assertEquals("work_owner", documentId)
            return ReviewWriteReceipt.Queued { ReviewRemoteResult.PUBLISHED }
        }
        override suspend fun removeDocument(documentId: String): Boolean =
            error("No deletes in this tracer")
    }

    @Test
    fun `a delayed read keeps new peer results without rolling back a published own rating`() = runBlocking {
        val oldOwn = ListenerReview("work", "owner", "Owner", 2, createdAt = 10L)
        val peer = ListenerReview("work", "peer", "Peer", 4, createdAt = 20L)
        val newPeer = ListenerReview("work", "new-peer", "New Peer", 3, createdAt = 25L)
        val newOwn = ListenerReview("work", "owner", "Owner", 5, body = "new", createdAt = 10L, editedAt = 30L)
        val oldSnapshot = ReviewQuerySnapshot(
            listOf(
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(peer), hasPendingWrites = false),
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(oldOwn), hasPendingWrites = false),
            ),
            ReviewReadProvenance.SERVER,
        )
        val store = HeldStore(oldSnapshot)
        val lifecycle = ListenerReviewLifecycle(store, now = { 30L })
        lifecycle.open("work", "owner")
        lifecycle.refresh("work")
        assertEquals(listOf(peer, oldOwn), lifecycle.state.value.confirmed)
        assertEquals(CombinedAverageResult(3.0, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))

        val reading = launch(start = CoroutineStart.UNDISPATCHED) { lifecycle.refresh("work") }
        assertEquals(2, store.reads)
        assertEquals(ReviewSaveResult.PUBLISHED, lifecycle.save("work", "owner", "Owner", 5, "new", null, oldOwn))
        assertEquals(listOf(peer, newOwn), lifecycle.state.value.confirmed)
        assertEquals(CombinedAverageResult(4.5, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))

        val delayedSnapshot = oldSnapshot.copy(
            documents = listOf(ReviewDocumentSnapshot(ListenerReviewCodec.toMap(newPeer), hasPendingWrites = false)) + oldSnapshot.documents
        )
        store.oldReply.complete(delayedSnapshot)
        reading.join()
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertEquals("late read must merge new peer and preserve published own", listOf(newPeer, peer, newOwn), lifecycle.state.value.confirmed)
        assertEquals(CombinedAverageResult(4.0, 3), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))
    }
}
