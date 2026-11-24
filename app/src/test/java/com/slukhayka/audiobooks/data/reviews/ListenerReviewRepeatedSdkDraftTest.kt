package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private second vertical tracer; no live submission or SDK error reason is invented. */
class ListenerReviewRepeatedSdkDraftTest {
    private class SnapshotStore(var snapshot: ReviewQuerySnapshot) : ListenerReviewsStore {
        override suspend fun queryWorkSnapshot(workId: String): ReviewQuerySnapshot = snapshot
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> =
            snapshot.documents.map { it.data }
        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> =
            snapshot.documents.map { it.data }
        override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt =
            error("This restored-only tracer must not enqueue a live write")
        override suspend fun removeDocument(documentId: String): Boolean =
            error("This restored-only tracer must not delete documents")
    }

    @Test
    fun `a repeated SDK pending snapshot retains the exact newest restored draft for retry`() = runBlocking {
        val firstDraft = ListenerReview("work", "owner", "Owner", 3, body = "first", createdAt = 10L, editedAt = 30L)
        val secondDraft = ListenerReview("work", "owner", "Owner", 5, body = "second", createdAt = 10L, editedAt = 40L)
        val oldOwn = ListenerReview("work", "owner", "Owner", 2, createdAt = 10L)
        val other = ListenerReview("work", "reader", "Reader", 4, createdAt = 20L)
        val store = SnapshotStore(
            ReviewQuerySnapshot(
                listOf(
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(firstDraft), hasPendingWrites = true),
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(other), hasPendingWrites = false),
                ),
                ReviewReadProvenance.CACHE,
            )
        )
        val lifecycle = ListenerReviewLifecycle(store)
        lifecycle.open("work", "owner")
        lifecycle.refresh("work")
        assertEquals(mapOf("work_owner" to firstDraft), lifecycle.state.value.pending)
        assertEquals(listOf(other), lifecycle.state.value.confirmed)

        store.snapshot = ReviewQuerySnapshot(
            listOf(
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(secondDraft), hasPendingWrites = true),
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(other), hasPendingWrites = false),
            ),
            ReviewReadProvenance.CACHE,
        )
        lifecycle.refresh("work")

        store.snapshot = ReviewQuerySnapshot(
            listOf(
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(other), hasPendingWrites = false),
                ReviewDocumentSnapshot(ListenerReviewCodec.toMap(oldOwn), hasPendingWrites = false),
            ),
            ReviewReadProvenance.SERVER,
        )
        lifecycle.refresh("work")
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertEquals(listOf(other, oldOwn), lifecycle.state.value.confirmed)
        assertEquals(CombinedAverageResult(3.0, 2), CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating }))
        assertEquals(mapOf("work_owner" to secondDraft), lifecycle.state.value.failedSave)
    }
}
