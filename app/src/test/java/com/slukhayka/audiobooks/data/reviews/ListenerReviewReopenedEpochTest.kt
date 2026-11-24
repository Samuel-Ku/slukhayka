package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** One private public seam tracer; a held old read must not enter a reopened Work. */
class ListenerReviewReopenedEpochTest {
    private class HeldStore : ListenerReviewsStore {
        val reply = CompletableDeferred<ReviewQuerySnapshot>()
        override suspend fun queryWorkSnapshot(workId: String): ReviewQuerySnapshot {
            assertEquals("A", workId)
            return reply.await()
        }
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> =
            error("Use the public metadata result")
        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> =
            error("No batch reads in this tracer")
        override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt =
            error("No writes in this tracer")
        override suspend fun removeDocument(documentId: String): Boolean =
            error("No deletes in this tracer")
    }

    @Test
    fun `an old read cannot populate the same Work reopened in a newer epoch`() = runBlocking {
        val store = HeldStore()
        val lifecycle = ListenerReviewLifecycle(store)
        lifecycle.open("A", "owner")
        val reading = launch(start = CoroutineStart.UNDISPATCHED) { lifecycle.refresh("A") }
        lifecycle.open("B", "owner")
        lifecycle.open("A", "owner")
        val cleanReopened = ListenerReviewState(workId = "A", uid = "owner")
        assertEquals(cleanReopened, lifecycle.state.value)

        val oldConfirmed = ListenerReview("A", "reader", "Reader", 2, createdAt = 10L)
        val oldPending = ListenerReview("A", "owner", "Owner", 5, createdAt = 20L)
        store.reply.complete(
            ReviewQuerySnapshot(
                listOf(
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(oldConfirmed), hasPendingWrites = false),
                    ReviewDocumentSnapshot(ListenerReviewCodec.toMap(oldPending), hasPendingWrites = true),
                ),
                ReviewReadProvenance.CACHE,
            )
        )
        reading.join()
        assertEquals("old epoch read must leave reopened Work clean", cleanReopened, lifecycle.state.value)
    }
}
