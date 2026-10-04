package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AcceptedFeedbackTest {
    @Test fun `actual queued review is accepted before remote acknowledgement, but a rejected draft is not`() = runTest {
        val remote = CompletableDeferred<ReviewRemoteResult>()
        var accepted = 0
        val store = object : ListenerReviewsStore {
            override suspend fun queryWorkDocuments(workId: String) = emptyList<Map<String, Any>>()
            override suspend fun queryWorksDocuments(workIds: List<String>) = emptyList<Map<String, Any>>()
            override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>) = ReviewWriteReceipt.Queued { remote.await() }
            override suspend fun removeDocument(documentId: String) = true
        }
        val lifecycle = ListenerReviewLifecycle(store, onAccepted = { accepted++ })
        lifecycle.open("work", "listener")
        lifecycle.refresh("work")
        assertEquals(0,accepted)
        lifecycle.save("work", "listener", "Читач", 0, null, null, null)
        assertEquals(0,accepted)
        val write = launch { lifecycle.save("work", "listener", "Читач", 5, null, null, null) }
        runCurrent()
        assertEquals(1,accepted)
        write.cancel(); runCurrent()
        assertEquals(1,accepted)
    }
    @Test fun `narration feedback requires a successful actual local acceptance`() = runTest {
        var success = false
        var accepted = 0
        val delegate = object : NarrationRatingsStore {
            override suspend fun queryWorkDocuments(workId: String) = emptyList<Map<String, Any>>()
            override suspend fun setDocument(documentId: String, document: Map<String, Any>) = success
            override suspend fun removeDocument(documentId: String) = true
        }
        val store = AcceptedNarrationRatingsStore(delegate) { accepted++ }
        val rating = NarrationRating("work", "listener", "edition", 5, 100L)
        store.getForWork("work"); store.putRating(rating.copy(rating=0)); store.putRating(rating)
        assertEquals(0, accepted)
        success = true
        store.putRating(rating)
        assertEquals(1, accepted)
    }
}
