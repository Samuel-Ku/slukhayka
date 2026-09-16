package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
