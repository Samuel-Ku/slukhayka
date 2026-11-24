package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private unrun tracer. A retryable unconfirmed draft is not an SDK error verdict. */
class ListenerReviewRestoredDraftReconciliationTest {
    private class SnapshotStore(var snapshot: ReviewQuerySnapshot) : ListenerReviewsStore {
        override suspend fun queryWorkSnapshot(workId: String): ReviewQuerySnapshot = snapshot
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> =
            snapshot.documents.map { it.data }
        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> =
            snapshot.documents.map { it.data }
        override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt =
            error("This restored-only read tracer must not enqueue writes")
        override suspend fun removeDocument(documentId: String): Boolean =
            error("This restored-only read tracer must not delete documents")
    }

    @Test
    fun `a restored draft superseded by server truth becomes retryable without changing the headline`() = runBlocking {
        val oldOwn = ListenerReview("work", "owner", "Owner", 2, createdAt = 10L)
        val other = ListenerReview("work", "reader", "Reader", 4, createdAt = 20L)
        val draftOwn = ListenerReview("work", "owner", "Owner", 5, body = "edited", createdAt = 10L, editedAt = 30L)
        val serverTruth = ReviewQuerySnapshot(
            listOf(
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(other), hasPendingWrites = false),
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(oldOwn), hasPendingWrites = false),
            ),
            ReviewReadProvenance.SERVER,
        )
        val store = SnapshotStore(serverTruth)
        val lifecycle = ListenerReviewLifecycle(store)
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.results.collect { events += it }
        }
        try {
            lifecycle.open("work", "owner")
            lifecycle.refresh("work")
            assertEquals(listOf(other, oldOwn), lifecycle.state.value.confirmed)
            assertEquals(CombinedAverageResult(3.0, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))

            store.snapshot = ReviewQuerySnapshot(
                listOf(
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(draftOwn), hasPendingWrites = true),
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(other), hasPendingWrites = false),
                ),
                ReviewReadProvenance.CACHE,
            )
            lifecycle.refresh("work")
            assertEquals(listOf(other, oldOwn), lifecycle.state.value.confirmed)
            assertEquals(mapOf("work_owner" to draftOwn), lifecycle.state.value.pending)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())
            assertEquals(listOf(other, draftOwn), lifecycle.state.value.visible)
            assertEquals(CombinedAverageResult(3.0, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))

            store.snapshot = serverTruth
            assertEquals(ReviewReadResult.Data(listOf(other, oldOwn), emptyList(), ReviewReadProvenance.SERVER), store.readReviews("work"))
            lifecycle.refresh("work")
            assertEquals(listOf(other, oldOwn), lifecycle.state.value.confirmed)
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(mapOf("work_owner" to draftOwn), lifecycle.state.value.failedSave)
            assertTrue(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(other, oldOwn), lifecycle.state.value.visible)
            assertEquals(CombinedAverageResult(3.0, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))
            assertFalse(lifecycle.state.value.readFailed)
            assertEquals(ReviewReadProvenance.SERVER, lifecycle.state.value.readProvenance)
            assertEquals(emptyList<ReviewSaveEvent>(), events)
        } finally {
            collecting.cancelAndJoin()
        }
    }
}
