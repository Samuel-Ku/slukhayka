package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-620 (#623) — the Work-scoped Listener Review lifecycle over an
 * in-memory store with controllable acknowledgements. Every scenario goes
 * through the lifecycle interface, never through its internals.
 */
class ListenerReviewLifecycleTest {

    private class FakeStore(var failReads: Boolean = false) : ListenerReviewsStore {
        val documents = linkedMapOf<String, Map<String, Any>>()
        val acknowledgements = ArrayDeque<CompletableDeferred<ReviewRemoteResult>>()

        fun seed(vararg reviews: ListenerReview) {
            reviews.forEach {
                documents[ListenerReviewCodec.documentId(it.workId, it.uid)] = ListenerReviewCodec.toMap(it)
            }
        }

        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> {
            if (failReads) throw IllegalStateException("transport down")
            return documents.values.filter { it["workId"] == workId }
        }

        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> =
            documents.values.filter { it["workId"] in workIds.toSet() }

        override suspend fun enqueueDocument(
            documentId: String,
            document: Map<String, Any>
        ): ReviewWriteReceipt {
            documents[documentId] = document
            val acknowledgement =
                acknowledgements.removeFirstOrNull() ?: CompletableDeferred(ReviewRemoteResult.PUBLISHED)
            return ReviewWriteReceipt.Queued { acknowledgement.await() }
        }

        override suspend fun removeDocument(documentId: String): Boolean =
            documents.remove(documentId) != null

        /** #626 — the separate backend verdict of one delete. */
        val deleteAcknowledgements = ArrayDeque<CompletableDeferred<Boolean>>()
        var failDeletes = false

        override suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt {
            if (failDeletes) return ReviewDeleteReceipt.Rejected
            documents.remove(documentId)
            val acknowledgement = deleteAcknowledgements.removeFirstOrNull()
                ?: CompletableDeferred(true)
            return ReviewDeleteReceipt.Queued { acknowledgement.await() }
        }
    }

    private fun review(uid: String, createdAt: Long, rating: Int = 5, workId: String = "w1") = ListenerReview(
        workId = workId,
        uid = uid,
        authorName = "Читач_$uid",
        rating = rating,
        body = "Гарна книга",
        createdAt = createdAt
    )

    private val documentId = ListenerReviewCodec.documentId("w1", "u1")

    @Test
    fun `a local reject never creates a pending card`() = runTest {
        val lifecycle = ListenerReviewLifecycle(FakeStore(), now = { 100L })
        lifecycle.open("w1")

        val outcome = lifecycle.save("w1", "u1", "Читач", 0, null, null, null)

        assertEquals(ReviewSaveResult.FAILED, outcome)
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
    }

    @Test
    fun `a missing store refuses honestly without a pending card`() = runTest {
        val lifecycle = ListenerReviewLifecycle(null, now = { 100L })
        lifecycle.open("w1")

        assertEquals(ReviewSaveResult.FAILED, lifecycle.save("w1", "u1", "Читач", 5, null, null, null))
        assertTrue(lifecycle.state.value.pending.isEmpty())
    }

    @Test
    fun `a local acceptance creates a pending card before the backend verdict`() = runTest {
        val store = FakeStore()
        val acknowledgement = CompletableDeferred<ReviewRemoteResult>()
        store.acknowledgements.add(acknowledgement)
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")

        val saving = launch { lifecycle.save("w1", "u1", "Читач", 5, "Гарна", null, null) }
        runCurrent()

        assertEquals(setOf(documentId), lifecycle.state.value.pending.keys)
        assertEquals("pending never enters the confirmed snapshot", emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
        assertEquals(listOf(5), lifecycle.state.value.visible.map { it.rating })

        acknowledgement.complete(ReviewRemoteResult.PUBLISHED)
        saving.join()

        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertEquals(listOf(5), lifecycle.state.value.confirmed.map { it.rating })
    }

    @Test
    fun `a failed acknowledgement retracts the pending card and reports failure`() = runTest {
        val store = FakeStore()
        store.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.FAILED))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")

        val outcome = lifecycle.save("w1", "u1", "Читач", 4, null, null, null)

        assertEquals(ReviewSaveResult.FAILED, outcome)
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
    }

    @Test
    fun `a newer edit wins over a late acknowledgement of the older save`() = runTest {
        val store = FakeStore()
        val olderAck = CompletableDeferred<ReviewRemoteResult>()
        val newerAck = CompletableDeferred<ReviewRemoteResult>()
        store.acknowledgements.add(olderAck)
        store.acknowledgements.add(newerAck)
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")

        val older = launch { lifecycle.save("w1", "u1", "Читач", 3, null, null, null) }
        runCurrent()
        val newer = launch { lifecycle.save("w1", "u1", "Читач", 5, null, null, null) }
        runCurrent()

        // The OLD save's acknowledgement arrives while the new edit is pending:
        // it must not touch the state.
        olderAck.complete(ReviewRemoteResult.PUBLISHED)
        older.join()
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
        assertEquals(listOf(5), lifecycle.state.value.pending.values.map { it.rating })

        newerAck.complete(ReviewRemoteResult.PUBLISHED)
        newer.join()
        assertEquals(listOf(5), lifecycle.state.value.confirmed.map { it.rating })
        assertTrue(lifecycle.state.value.pending.isEmpty())
    }

    @Test
    fun `an edit keeps the original createdAt and marks editedAt`() = runTest {
        val store = FakeStore()
        val lifecycle = ListenerReviewLifecycle(store, now = { 500L })
        lifecycle.open("w1")
        val existing = review("u1", createdAt = 100L)

        lifecycle.save("w1", "u1", "Читач", 4, "Оновлено", null, existing)

        val stored = lifecycle.state.value.confirmed.single()
        assertEquals(100L, stored.createdAt)
        assertEquals(500L, stored.editedAt)
    }

    @Test
    fun `a failed read keeps the last confirmed snapshot and flags it`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")

        lifecycle.refresh("w1")
        assertFalse(lifecycle.state.value.readFailed)
        assertEquals(1, lifecycle.state.value.confirmed.size)

        store.failReads = true
        lifecycle.refresh("w1")

        assertTrue(lifecycle.state.value.readFailed)
        assertEquals("a failure is not an empty community", 1, lifecycle.state.value.confirmed.size)
    }

    @Test
    fun `a successful empty clears the confirmed snapshot`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")
        lifecycle.refresh("w1")
        assertEquals(1, lifecycle.state.value.confirmed.size)

        store.documents.clear()
        lifecycle.refresh("w1")

        assertFalse(lifecycle.state.value.readFailed)
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
    }

    @Test
    fun `opening another Work drops the previous Work's state`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L, workId = "w1"), review("u2", createdAt = 20L, workId = "w2"))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })

        lifecycle.open("w1")
        lifecycle.refresh("w1")
        assertEquals(listOf("u1"), lifecycle.state.value.confirmed.map { it.uid })

        lifecycle.open("w2")
        assertTrue("no leakage into the new Work", lifecycle.state.value.confirmed.isEmpty())

        // A straggler read of the OLD Work cannot repopulate the new scope.
        lifecycle.refresh("w1")
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
    }

    @Test
    fun `confirmed and pending cards are distinct in the visible projection`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        store.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.PUBLISHED))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")
        lifecycle.refresh("w1")

        val saving = launch { lifecycle.save("w1", "u1", "Читач", 2, null, null, null) }
        runCurrent()

        // The pending edit shadows the confirmed card instead of duplicating it.
        assertEquals(1, lifecycle.state.value.visible.size)
        assertEquals(listOf(2), lifecycle.state.value.visible.map { it.rating })
        saving.join()
    }

    @Test
    fun `a pending edit keeps the previous confirmed rating until acceptance`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L, rating = 4))
        val acknowledgement = CompletableDeferred<ReviewRemoteResult>()
        store.acknowledgements.add(acknowledgement)
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")
        lifecycle.refresh("w1")

        val saving = launch {
            lifecycle.save("w1", "u1", "Читач", 5, null, null, lifecycle.state.value.confirmed.first())
        }
        runCurrent()

        // The headline (built from confirmed) does not move before acceptance…
        assertEquals(listOf(4), lifecycle.state.value.confirmed.map { it.rating })
        // …while the card itself shows the pending edit.
        assertEquals(listOf(5), lifecycle.state.value.visible.map { it.rating })

        acknowledgement.complete(ReviewRemoteResult.PUBLISHED)
        saving.join()
    }

    @Test
    fun `results are announced through the lifecycle interface`() = runTest {
        val store = FakeStore()
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1")
        val seen = mutableListOf<ReviewSaveResult>()
        val collecting = launch { lifecycle.results.collect { seen += it.result } }
        runCurrent()

        lifecycle.save("w1", "u1", "Читач", 5, null, null, null)
        runCurrent()
        collecting.cancel()

        assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), seen)
    }
    // ------------------------------------------------------------------
    // Spec-620 (#626) — delete ordering, retry and identity epochs
    // ------------------------------------------------------------------

    @Test
    fun `delete accepts locally, then reports the backend verdict`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        val acknowledgement = CompletableDeferred<Boolean>()
        store.deleteAcknowledgements.add(acknowledgement)
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.refresh("w1")
        val seen = mutableListOf<ReviewDeleteResult>()
        val collecting = launch { lifecycle.deleteResults.collect { seen += it.result } }
        runCurrent()

        val deleting = launch { lifecycle.delete("w1", "u1") }
        runCurrent()

        // Local acceptance: gone from every surface BEFORE the network verdict.
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
        assertTrue(lifecycle.state.value.visible.isEmpty())
        assertEquals(setOf(documentId), lifecycle.state.value.deleting)

        acknowledgement.complete(true)
        deleting.join()
        runCurrent()
        collecting.cancel()

        assertEquals(listOf(ReviewDeleteResult.QUEUED, ReviewDeleteResult.DELETED), seen)
        assertTrue(lifecycle.state.value.deleting.isEmpty())
    }

    @Test
    fun `a late save acknowledgement cannot return a deleted review`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        val saveAck = CompletableDeferred<ReviewRemoteResult>()
        store.acknowledgements.add(saveAck)
        val deleteAck = CompletableDeferred<Boolean>()
        store.deleteAcknowledgements.add(deleteAck)
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.refresh("w1")

        val saving = launch { lifecycle.save("w1", "u1", "Читач", 5, null, null, null) }
        runCurrent()
        val deleting = launch { lifecycle.delete("w1", "u1") }
        runCurrent()

        saveAck.complete(ReviewRemoteResult.PUBLISHED)
        saving.join()
        assertTrue("a stale save ack must not resurrect the card", lifecycle.state.value.confirmed.isEmpty())
        assertTrue(lifecycle.state.value.pending.isEmpty())

        deleteAck.complete(true)
        deleting.join()
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
    }

    @Test
    fun `a failed delete restores the confirmed card and stays retryable`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        store.deleteAcknowledgements.add(CompletableDeferred(false))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.refresh("w1")

        assertEquals(ReviewDeleteResult.FAILED, lifecycle.delete("w1", "u1"))
        assertEquals("the review is back after a failed delete", 1, lifecycle.state.value.confirmed.size)
        assertTrue(lifecycle.state.value.hasFailedMutation)

        store.deleteAcknowledgements.add(CompletableDeferred(true))
        assertEquals(documentId, lifecycle.retry("w1"))
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
        assertFalse(lifecycle.state.value.hasFailedMutation)
    }

    @Test
    fun `a save after a delete is allowed and creates no tombstone`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.refresh("w1")
        assertEquals(ReviewDeleteResult.DELETED, lifecycle.delete("w1", "u1"))
        assertTrue(lifecycle.state.value.confirmed.isEmpty())

        assertEquals(ReviewSaveResult.PUBLISHED, lifecycle.save("w1", "u1", "Читач", 4, null, null, null))
        assertEquals(listOf(4), lifecycle.state.value.confirmed.map { it.rating })
        assertFalse(lifecycle.state.value.deleting.contains(documentId))
    }

    @Test
    fun `retry resends the exact failed save payload`() = runTest {
        val store = FakeStore()
        store.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.FAILED))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.save("w1", "u1", "Читач", 3, "перша", null, null)
        assertTrue(lifecycle.state.value.hasFailedMutation)

        store.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.PUBLISHED))
        assertEquals(documentId, lifecycle.retry("w1"))

        assertEquals(3, (store.documents[documentId]?.get("rating") as Number).toInt())
        assertEquals("перша", store.documents[documentId]?.get("body"))
        assertFalse(lifecycle.state.value.hasFailedMutation)
    }

    @Test
    fun `a newer local draft supersedes the failed payload so retry never rewrites it`() = runTest {
        val store = FakeStore()
        store.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.FAILED))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.save("w1", "u1", "Читач", 3, null, null, null)
        assertTrue(lifecycle.state.value.hasFailedMutation)

        val newerAck = CompletableDeferred<ReviewRemoteResult>()
        store.acknowledgements.add(newerAck)
        val saving = launch { lifecycle.save("w1", "u1", "Читач", 5, null, null, null) }
        runCurrent()

        assertFalse("the newer draft superseded the failure", lifecycle.state.value.hasFailedMutation)
        assertNull(lifecycle.retry("w1"))
        assertEquals(listOf(5), lifecycle.state.value.pending.values.map { it.rating })
        newerAck.complete(ReviewRemoteResult.PUBLISHED)
        saving.join()
    }

    @Test
    fun `a different listener uid is a new epoch that drops private overlays`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L, rating = 5))
        val saveAck = CompletableDeferred<ReviewRemoteResult>()
        store.acknowledgements.add(saveAck)
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.refresh("w1")
        val saving = launch { lifecycle.save("w1", "u1", "Читач", 3, null, null, null) }
        runCurrent()
        assertEquals(setOf(documentId), lifecycle.state.value.pending.keys)

        // A different listener: public truth stays, private overlays are gone.
        lifecycle.open("w1", "u2")
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertEquals(1, lifecycle.state.value.confirmed.size)

        // The OLD uid's late acknowledgement cannot touch the new epoch.
        saveAck.complete(ReviewRemoteResult.PUBLISHED)
        saving.join()
        assertEquals(listOf(5), lifecycle.state.value.confirmed.map { it.rating })
    }

    @Test
    fun `a Work switch drops failures and refuses a retry of the old Work`() = runTest {
        val store = FakeStore()
        store.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.FAILED))
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.save("w1", "u1", "Читач", 3, null, null, null)
        assertTrue(lifecycle.state.value.hasFailedMutation)

        lifecycle.open("w2", "u1")
        assertFalse(lifecycle.state.value.hasFailedMutation)
        assertNull(lifecycle.retry("w1"))
    }

    @Test
    fun `cancelling the awaiting coroutine does not revoke a locally accepted save`() = runTest {
        val store = FakeStore()
        store.acknowledgements.add(CompletableDeferred())
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")

        val saving = launch { lifecycle.save("w1", "u1", "Читач", 5, null, null, null) }
        runCurrent()
        assertEquals(setOf(documentId), lifecycle.state.value.pending.keys)

        saving.cancel()
        saving.join()
        assertEquals(
            "cancellation never revokes a write the local queue already accepted",
            setOf(documentId),
            lifecycle.state.value.pending.keys
        )
    }

    @Test
    fun `cancelling the awaiting coroutine does not restore a locally accepted delete`() = runTest {
        val store = FakeStore()
        store.seed(review("u1", createdAt = 10L))
        store.deleteAcknowledgements.add(CompletableDeferred())
        val lifecycle = ListenerReviewLifecycle(store, now = { 100L })
        lifecycle.open("w1", "u1")
        lifecycle.refresh("w1")

        val deleting = launch { lifecycle.delete("w1", "u1") }
        runCurrent()
        assertTrue(lifecycle.state.value.confirmed.isEmpty())

        deleting.cancel()
        deleting.join()
        assertTrue(
            "cancellation never restores a delete the local queue already accepted",
            lifecycle.state.value.confirmed.isEmpty()
        )
    }

    @Test
    fun `a missing store refuses a delete honestly`() = runTest {
        val lifecycle = ListenerReviewLifecycle(null, now = { 100L })
        lifecycle.open("w1", "u1")
        assertEquals(ReviewDeleteResult.FAILED, lifecycle.delete("w1", "u1"))
        assertTrue(lifecycle.state.value.confirmed.isEmpty())
    }
}
