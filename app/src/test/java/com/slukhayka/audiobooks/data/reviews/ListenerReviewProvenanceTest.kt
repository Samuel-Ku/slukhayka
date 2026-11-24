package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerReviewProvenanceTest {
    private class SnapshotStore(var snapshot: ReviewQuerySnapshot) : ListenerReviewsStore {
        override suspend fun queryWorkSnapshot(workId: String): ReviewQuerySnapshot = snapshot
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> =
            snapshot.documents.map { it.data }
        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> =
            snapshot.documents.map { it.data }
        override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt =
            error("This read scenario must not enqueue writes")
        override suspend fun removeDocument(documentId: String): Boolean =
            error("This read scenario must not delete documents")
    }

    @Test
    fun `a fresh lifecycle keeps cached SDK pending reviews private and outside the headline`() = runBlocking {
        val pendingOwn = ListenerReview("work", "owner", "Owner", 5, createdAt = 30L)
        val confirmedOther = ListenerReview("work", "reader", "Reader", 2, createdAt = 20L)
        val pendingOther = ListenerReview("work", "other", "Other", 3, createdAt = 10L)
        val store = SnapshotStore(
            ReviewQuerySnapshot(
                listOf(
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(pendingOwn), hasPendingWrites = true),
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(confirmedOther), hasPendingWrites = false),
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(pendingOther), hasPendingWrites = true),
                ),
                ReviewReadProvenance.CACHE,
            )
        )
        val lifecycle = ListenerReviewLifecycle(store)
        lifecycle.open("work", "owner")

        lifecycle.refresh("work")

        assertEquals("SDK pending is not a confirmed vote after recreation", listOf(confirmedOther), lifecycle.state.value.confirmed)
        assertEquals(mapOf("work_owner" to pendingOwn), lifecycle.state.value.pending)
        assertEquals(listOf(pendingOwn, confirmedOther), lifecycle.state.value.visible)
        assertEquals(CombinedAverageResult(2.0, 1), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))
        assertEquals(listOf(confirmedOther), store.getReviews("work"))

        lifecycle.open("work", "reader")
        lifecycle.refresh("work")
        assertTrue("another uid never inherits a local pending card", lifecycle.state.value.pending.isEmpty())
        assertEquals(listOf(confirmedOther), lifecycle.state.value.visible)

        lifecycle.open("work", "owner")
        lifecycle.refresh("work")
        assertEquals(mapOf("work_owner" to pendingOwn), lifecycle.state.value.pending)

        store.snapshot = ReviewQuerySnapshot(
            listOf(
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(pendingOwn), hasPendingWrites = false),
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(confirmedOther), hasPendingWrites = false),
            ),
            ReviewReadProvenance.SERVER,
        )
        lifecycle.refresh("work")
        assertEquals(listOf(pendingOwn, confirmedOther), lifecycle.state.value.confirmed)
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertEquals(CombinedAverageResult(3.5, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))
    }
}
