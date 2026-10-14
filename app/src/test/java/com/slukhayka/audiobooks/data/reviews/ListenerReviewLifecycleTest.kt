package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    /** A controllable SDK boundary: durable queue readiness and SERVER read are separate. */
    private class RestoredQueueStore(
        val cached: ReviewReadResult.Snapshot,
        val server: ReviewReadResult,
        val writes: FakeStore = FakeStore()
    ) : ListenerReviewsStore by writes {
        val queue = CompletableDeferred<Boolean>()
        var serverReads = 0
        var lateServer: CompletableDeferred<ReviewReadResult>? = null
        override suspend fun readReviews(workId: String, uid: String): ReviewReadResult = cached
        override suspend fun awaitPendingWrites(): Boolean = withContext(NonCancellable) { queue.await() }
        override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
            serverReads++
            return lateServer?.let { withContext(NonCancellable) { it.await() } } ?: server
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
    fun `an SDK write restored after restart remains pending until server acknowledgement`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        var snapshot = ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true)
        val transport = object : ListenerReviewsStore by FakeStore() {
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult = snapshot
        }
        val lifecycle = ListenerReviewLifecycle(transport)
        lifecycle.open("w1", "u1")
        val results = mutableListOf<ReviewSaveResult>()
        val collecting = launch { lifecycle.results.collect { results += it.result } }
        runCurrent()

        lifecycle.refresh("w1")

        assertEquals(listOf(3), lifecycle.state.value.confirmed.map { it.rating })
        assertEquals(listOf(5), lifecycle.state.value.pending.values.map { it.rating })
        assertEquals(3.0, CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating })!!.value, 0.0)
        assertTrue(results.isEmpty())

        snapshot = ReviewReadResult.Snapshot(listOf(queued, seed), emptyList(), fromCache = false, authoritative = true)
        lifecycle.refresh("w1")
        runCurrent()

        assertEquals(listOf(5, 3), lifecycle.state.value.confirmed.map { it.rating })
        assertTrue(lifecycle.state.value.pending.isEmpty())
        assertEquals(listOf(ReviewSaveResult.PUBLISHED), results)
        collecting.cancel()
    }

    @Test
    fun `restored SDK queue acknowledgement publishes once without a UI refresh`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        val sdkQueue = CompletableDeferred<Boolean>()
        var serverReads = 0
        val transport = object : ListenerReviewsStore by FakeStore() {
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult =
                ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true)
            override suspend fun awaitPendingWrites(): Boolean = sdkQueue.await()
            override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                serverReads++
                return ReviewReadResult.Snapshot(listOf(queued, seed), emptyList(), fromCache = false, authoritative = true)
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, scope = this)
        lifecycle.open("w1", "u1")
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = launch { lifecycle.results.collect { events += it } }
        try {
            runCurrent()
            lifecycle.refresh("w1")
            runCurrent()
            assertEquals(listOf(3), lifecycle.state.value.confirmed.map { it.rating })
            assertEquals(listOf(5), lifecycle.state.value.pending.values.map { it.rating })
            assertEquals(0, serverReads)
            assertTrue(events.isEmpty())

            sdkQueue.complete(true)
            runCurrent()

            assertEquals(listOf(5, 3), lifecycle.state.value.confirmed.map { it.rating })
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertEquals(1, serverReads)
            assertEquals(listOf(ReviewSaveResult.PUBLISHED), events.map { it.result })
            assertEquals(documentId, events.single().documentId)
            runCurrent()
            assertEquals(1, events.size)
        } finally {
            collecting.cancel()
        }
    }

    @Test
    fun `an authoritative missing or changed restored payload fails with the exact retry draft`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        for (changed in listOf<ListenerReview?>(null, queued.copy(rating = 4))) {
            val serverRows = listOfNotNull(changed, seed)
            val transport = RestoredQueueStore(
                ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true),
                ReviewReadResult.Snapshot(serverRows, emptyList(), fromCache = false, authoritative = true)
            )
            val lifecycle = ListenerReviewLifecycle(transport, scope = this)
            lifecycle.open("w1", "u1")
            val results = mutableListOf<ReviewSaveResult>()
            val collecting = launch { lifecycle.results.collect { results += it.result } }
            try {
                runCurrent()
                lifecycle.refresh("w1")
                runCurrent()
                transport.queue.complete(true)
                runCurrent()
                assertEquals(serverRows, lifecycle.state.value.confirmed)
                assertTrue(lifecycle.state.value.pending.isEmpty())
                assertEquals(queued, lifecycle.state.value.failedSave[documentId])
                assertEquals(listOf(ReviewSaveResult.FAILED), results)
                assertEquals(1, transport.serverReads)
                assertFalse(lifecycle.state.value.readFailed)
            } finally { collecting.cancel() }
        }
    }

    @Test
    fun `an unavailable or cached server read keeps restored pending and last confirmed truth`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        for (untrusted in listOf(ReviewReadResult.Failure,
            ReviewReadResult.Snapshot(listOf(queued), emptyList(), fromCache = true))) {
            val transport = RestoredQueueStore(
                ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true), untrusted
            )
            val lifecycle = ListenerReviewLifecycle(transport, scope = this)
            lifecycle.open("w1", "u1")
            val events = mutableListOf<ReviewSaveEvent>()
            val collecting = launch { lifecycle.results.collect { events += it } }
            try {
                runCurrent()
                lifecycle.refresh("w1")
                runCurrent()
                transport.queue.complete(true)
                runCurrent()
                assertEquals(listOf(seed), lifecycle.state.value.confirmed)
                assertEquals(queued, lifecycle.state.value.pending[documentId])
                assertTrue(lifecycle.state.value.readFailed)
                assertFalse(lifecycle.state.value.hasFailedMutation)
                assertTrue(events.isEmpty())
            } finally { collecting.cancel() }
        }
    }

    @Test
    fun `a restored server callback cannot cross a Work or listener epoch`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        for ((nextWork, nextUid) in listOf("w1" to "u2", "w2" to "u1")) {
            val server = ReviewReadResult.Snapshot(listOf(queued, seed), emptyList(), fromCache = false, authoritative = true)
            val transport = RestoredQueueStore(
                ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true), server
            )
            val callback = CompletableDeferred<ReviewReadResult>()
            transport.lateServer = callback
            val lifecycle = ListenerReviewLifecycle(transport, scope = this)
            lifecycle.open("w1", "u1")
            val events = mutableListOf<ReviewSaveEvent>()
            val collecting = launch { lifecycle.results.collect { events += it } }
            try {
                runCurrent()
                lifecycle.refresh("w1")
                runCurrent()
                transport.queue.complete(true)
                runCurrent()
                assertEquals(1, transport.serverReads)
                lifecycle.open(nextWork, nextUid)
                callback.complete(server)
                runCurrent()
                assertEquals(nextWork, lifecycle.state.value.workId)
                assertEquals(nextUid, lifecycle.state.value.uid)
                assertEquals(if (nextWork == "w1") listOf(seed) else emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
                assertTrue(lifecycle.state.value.pending.isEmpty())
                assertFalse(lifecycle.state.value.hasFailedMutation)
                assertTrue(events.isEmpty())
            } finally { collecting.cancel() }
        }
    }

    @Test
    fun `a new edit supersedes the restored SDK callback`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        val transport = RestoredQueueStore(
            ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true),
            ReviewReadResult.Snapshot(listOf(queued, seed), emptyList(), fromCache = false, authoritative = true)
        )
        val newAck = CompletableDeferred<ReviewRemoteResult>()
        transport.writes.acknowledgements.add(newAck)
        val lifecycle = ListenerReviewLifecycle(transport, now = { 300L }, scope = this)
        lifecycle.open("w1", "u1")
        val results = mutableListOf<ReviewSaveResult>()
        val collecting = launch { lifecycle.results.collect { results += it.result } }
        try {
            runCurrent()
            lifecycle.refresh("w1")
            runCurrent()
            assertEquals(ReviewSaveResult.QUEUED, lifecycle.enqueueSave("w1", "u1", "Читач", 4, "Новіший текст", null, queued))
            runCurrent()
            transport.queue.complete(true)
            runCurrent()
            assertEquals(listOf(4), lifecycle.state.value.pending.values.map { it.rating })
            assertEquals(listOf(3), lifecycle.state.value.confirmed.map { it.rating })
            assertEquals(0, transport.serverReads)
            assertEquals(listOf(ReviewSaveResult.QUEUED), results)
            newAck.complete(ReviewRemoteResult.PUBLISHED)
            runCurrent()
            assertEquals(listOf(4, 3), lifecycle.state.value.confirmed.map { it.rating })
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), results)
        } finally { collecting.cancel() }
    }

    @Test
    fun `a new delete supersedes the restored SDK callback without resurrecting the card`() = runTest {
        val seed = review("other", 100L, rating = 3)
        val queued = review("u1", 200L, rating = 5)
        val transport = RestoredQueueStore(
            ReviewReadResult.Snapshot(listOf(seed), listOf(queued), fromCache = true),
            ReviewReadResult.Snapshot(listOf(queued, seed), emptyList(), fromCache = false, authoritative = true)
        )
        val deleteAck = CompletableDeferred<Boolean>()
        transport.writes.deleteAcknowledgements.add(deleteAck)
        val lifecycle = ListenerReviewLifecycle(transport, scope = this)
        lifecycle.open("w1", "u1")
        val saves = mutableListOf<ReviewSaveResult>()
        val deletes = mutableListOf<ReviewDeleteResult>()
        val collecting = launch { lifecycle.results.collect { saves += it.result } }
        val deletingEvents = launch { lifecycle.deleteResults.collect { deletes += it.result } }
        try {
            runCurrent()
            lifecycle.refresh("w1")
            runCurrent()
            val deleting = launch { assertEquals(ReviewDeleteResult.DELETED, lifecycle.delete("w1", "u1")) }
            runCurrent()
            transport.queue.complete(true)
            runCurrent()
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertEquals(setOf(documentId), lifecycle.state.value.deleting)
            assertEquals(listOf(seed), lifecycle.state.value.visible)
            assertEquals(0, transport.serverReads)
            assertTrue(saves.isEmpty())
            deleteAck.complete(true)
            runCurrent()
            assertTrue(deleting.isCompleted)
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(listOf(ReviewDeleteResult.QUEUED, ReviewDeleteResult.DELETED), deletes)
        } finally { collecting.cancel(); deletingEvents.cancel() }
    }

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
    // PRIVATE current-API selected RED proposal. NOT APPLIED / COMPILED / RUN.
    @Test
    fun capturedDefaultReadCannotReplaceNewLocallyQueuedEdit() = runTest {
        val seed = ListenerReview("race-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val oldPending = ListenerReview("race-work", "listener-a", "Читач A", 5,
            "Попередня правка", "Видання E", 100L, 200L)
        val newPending = ListenerReview("race-work", "listener-a", "Читач A", 4,
            "Нова правка", "Видання E", 100L, 300L)
        val oldFrame = ReviewReadResult.Snapshot(listOf(seed), listOf(oldPending), fromCache = true)
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<ReviewReadResult>()
        val remote = CompletableDeferred<ReviewRemoteResult>()
        val readiness = CompletableDeferred<Boolean>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val readinessCalls = java.util.concurrent.atomic.AtomicInteger()
        val serverCalls = java.util.concurrent.atomic.AtomicInteger()
        val writes = FakeStore().also { it.acknowledgements.add(remote) }
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "race-work" && uid == "listener-a")
                return when (reads.incrementAndGet()) {
                    // Public legacy Data establishes the independent full confirmed baseline;
                    // this host test does not model SDK metadata or persistent Firebase storage.
                    1 -> ReviewReadResult.Data(listOf(seed))
                    2 -> {
                        readEntered.complete(Unit)
                        withContext(NonCancellable) { releaseRead.await() }
                    }
                    else -> error("Unexpected additional DEFAULT read")
                }
            }
            override suspend fun awaitPendingWrites(): Boolean {
                readinessCalls.incrementAndGet()
                return readiness.await()
            }
            override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                serverCalls.incrementAndGet()
                return ReviewReadResult.Failure
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, now = { 300L }, scope = backgroundScope)
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch {
            lifecycle.results.collect { events += it }
        }
        var oldRead: kotlinx.coroutines.Job? = null
        suspend fun <T> boundedIo(block: suspend () -> T): T =
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) { block() }
            }
        try {
            lifecycle.open("race-work", "listener-a")
            lifecycle.refresh("race-work")
            runCurrent() // Collector subscription precedes the independently queued mutation.
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(events.isEmpty())

            oldRead = launch(kotlinx.coroutines.Dispatchers.IO) { lifecycle.refresh("race-work") }
            boundedIo { readEntered.await() } // Actual IO request was captured before the new save.
            assertEquals(2, reads.get())
            assertFalse(releaseRead.isCompleted)
            val local = lifecycle.enqueueSave("race-work", "listener-a", "Читач A", 4,
                "Нова правка", "Видання E", seed)
            runCurrent()
            assertEquals(ReviewSaveResult.QUEUED, local)
            assertFalse(remote.isCompleted)
            assertEquals(newPending, ListenerReviewCodec.fromMap(writes.documents.getValue("race-work_listener-a")))
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf("race-work_listener-a" to newPending), lifecycle.state.value.pending)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })

            releaseRead.complete(oldFrame)
            boundedIo { oldRead!!.join() }
            runCurrent()
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            // Meaningful current-code RED: this stale DEFAULT frame currently overwrites exact new A4 with old A5.
            assertEquals(mapOf("race-work_listener-a" to newPending), lifecycle.state.value.pending)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())
            assertTrue(lifecycle.state.value.failedDelete.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertFalse(lifecycle.state.value.readFailed)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })
            assertEquals("race-work", events.single().workId)
            assertEquals("race-work_listener-a", events.single().documentId)
            assertEquals(0, readinessCalls.get())
            assertEquals(0, serverCalls.get())
            assertFalse(remote.isCompleted)
            assertEquals(3.0, CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating })!!.value, 0.0)
        } finally {
            releaseRead.complete(oldFrame)
            oldRead?.let { boundedIo { it.join() } }
            collecting.cancel()
            // Background scope owns the held receipt; test teardown cancels that waiter without fabricating an ACK.
        }
    }

    // PRIVATE one current reviewed-API ACK/late DEFAULT oracle. NOT APPLIED / COMPILED / RUN.
    @Test
    fun publishedEditCannotBeResurrectedByEarlierDefaultRead() = runTest {
        val seed = ListenerReview("ack-race-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val accepted = ListenerReview("ack-race-work", "listener-a", "Читач A", 4,
            "Прийнята правка", "Видання E", 100L, 300L)
        val oldFrame = ReviewReadResult.Snapshot(listOf(seed), listOf(accepted), fromCache = true)
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<ReviewReadResult>()
        val remote = CompletableDeferred<ReviewRemoteResult>()
        val readiness = CompletableDeferred<Boolean>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val serverCalls = java.util.concurrent.atomic.AtomicInteger()
        val writes = FakeStore().also { it.acknowledgements.add(remote) }
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "ack-race-work" && uid == "listener-a")
                return when (reads.incrementAndGet()) {
                    1 -> ReviewReadResult.Data(listOf(seed))
                    2 -> {
                        readEntered.complete(Unit)
                        withContext(NonCancellable) { releaseRead.await() }
                    }
                    else -> error("Unexpected additional DEFAULT read")
                }
            }
            override suspend fun awaitPendingWrites(): Boolean = readiness.await()
            override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "ack-race-work" && uid == "listener-a")
                serverCalls.incrementAndGet()
                return ReviewReadResult.Snapshot(listOf(accepted), emptyList(), fromCache = false, authoritative = true)
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, now = { 300L }, scope = backgroundScope)
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch { lifecycle.results.collect { events += it } }
        var oldRead: kotlinx.coroutines.Job? = null
        suspend fun <T> boundedIo(block: suspend () -> T): T =
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) { block() }
            }
        try {
            lifecycle.open("ack-race-work", "listener-a")
            lifecycle.refresh("ack-race-work")
            runCurrent()
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(events.isEmpty())

            val local = lifecycle.enqueueSave("ack-race-work", "listener-a", "Читач A", 4,
                "Прийнята правка", "Видання E", seed)
            runCurrent()
            assertEquals(ReviewSaveResult.QUEUED, local)
            assertEquals(accepted, ListenerReviewCodec.fromMap(writes.documents.getValue("ack-race-work_listener-a")))
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf("ack-race-work_listener-a" to accepted), lifecycle.state.value.pending)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })
            assertFalse(remote.isCompleted)

            oldRead = launch(kotlinx.coroutines.Dispatchers.IO) { lifecycle.refresh("ack-race-work") }
            boundedIo { readEntered.await() } // DEFAULT generation is captured after queueing, before its ACK.
            assertEquals(2, reads.get())
            assertFalse(releaseRead.isCompleted)
            remote.complete(ReviewRemoteResult.PUBLISHED) // Actual held external seam receipt, not a fabricated lifecycle event.
            runCurrent()
            assertEquals(listOf(accepted), lifecycle.state.value.confirmed)
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), events.map { it.result })
            assertEquals("ack-race-work", events.last().workId)
            assertEquals("ack-race-work_listener-a", events.last().documentId)
            assertEquals(0, serverCalls.get())

            releaseRead.complete(oldFrame)
            boundedIo { oldRead!!.join() }
            runCurrent()
            // Intended measured RED precedes confirmed/event assertions: stale metadata must not resurrect an ACKed payload.
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(accepted), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), events.map { it.result })
            // If any reconciliation was reserved, actual readiness + separate authoritative read must not publish twice.
            readiness.complete(true)
            runCurrent()
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(accepted), lifecycle.state.value.confirmed)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), events.map { it.result })
            assertTrue(serverCalls.get() <= 1)
            assertEquals(2, reads.get()) // No extra UI refresh to repair the state.
        } finally {
            releaseRead.complete(oldFrame)
            oldRead?.let { boundedIo { it.join() } }
            collecting.cancel()
            // runTest cancels only its owned background receipt/reconciliation jobs; no extra backend verdict in cleanup.
        }
    }

    // PRIVATE current public-API terminal DELETE/late DEFAULT oracle. NOT APPLIED / COMPILED / RUN.
    @Test
    fun confirmedDeleteCannotBeResurrectedByEarlierDefaultRead() = runTest {
        val seed = ListenerReview("delete-race-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val id = "delete-race-work_listener-a"
        val oldFrame = ReviewReadResult.Snapshot(listOf(seed), emptyList(), fromCache = true)
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<ReviewReadResult>()
        val localAccepted = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<Boolean>()
        val readiness = CompletableDeferred<Boolean>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val serverCalls = java.util.concurrent.atomic.AtomicInteger()
        val writes = FakeStore().also {
            it.seed(seed)
            it.deleteAcknowledgements.add(remote)
        }
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt {
                check(documentId == id)
                val receipt = writes.enqueueDelete(documentId)
                check(receipt is ReviewDeleteReceipt.Queued && !writes.documents.containsKey(id))
                localAccepted.complete(Unit) // Actual FakeStore acceptance, after its document removal.
                return receipt
            }
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "delete-race-work" && uid == "listener-a")
                return when (reads.incrementAndGet()) {
                    1 -> ReviewReadResult.Data(listOf(seed))
                    2 -> {
                        check(localAccepted.isCompleted && !remote.isCompleted)
                        readEntered.complete(Unit)
                        withContext(NonCancellable) { releaseRead.await() }
                    }
                    else -> error("Unexpected additional DEFAULT read")
                }
            }
            override suspend fun awaitPendingWrites(): Boolean = readiness.await()
            override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "delete-race-work" && uid == "listener-a")
                serverCalls.incrementAndGet()
                return ReviewReadResult.Snapshot(emptyList(), emptyList(), fromCache = false, authoritative = true)
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, now = { 300L }, scope = backgroundScope)
        val events = mutableListOf<ReviewDeleteEvent>()
        val collecting = backgroundScope.launch { lifecycle.deleteResults.collect { events += it } }
        var oldRead: kotlinx.coroutines.Job? = null
        var deleting: kotlinx.coroutines.Job? = null
        suspend fun <T> boundedIo(block: suspend () -> T): T =
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) { block() }
            }
        try {
            lifecycle.open("delete-race-work", "listener-a")
            lifecycle.refresh("delete-race-work")
            runCurrent()
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(listOf(seed), lifecycle.state.value.visible)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertTrue(events.isEmpty())

            deleting = backgroundScope.launch {
                assertEquals(ReviewDeleteResult.DELETED, lifecycle.delete("delete-race-work", "listener-a"))
            }
            runCurrent()
            boundedIo { localAccepted.await() }
            assertFalse(writes.documents.containsKey(id))
            assertTrue(writes.deleteAcknowledgements.isEmpty()) // Held receipt was actually consumed.
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertTrue(lifecycle.state.value.visible.isEmpty())
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertEquals(setOf(id), lifecycle.state.value.deleting)
            assertEquals(listOf(ReviewDeleteResult.QUEUED), events.map { it.result })
            assertFalse(remote.isCompleted)
            assertFalse(deleting!!.isCompleted)

            oldRead = launch(kotlinx.coroutines.Dispatchers.IO) { lifecycle.refresh("delete-race-work") }
            boundedIo { readEntered.await() } // DEFAULT starts after actual local acceptance, before its backend receipt.
            assertEquals(2, reads.get())
            assertFalse(releaseRead.isCompleted)
            remote.complete(true) // Complete the actual held external receipt, never synthesize lifecycle events.
            runCurrent()
            assertTrue(deleting!!.isCompleted)
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertTrue(lifecycle.state.value.visible.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertEquals(listOf(ReviewDeleteResult.QUEUED, ReviewDeleteResult.DELETED), events.map { it.result })
            assertTrue(events.all { it.workId == "delete-race-work" && it.documentId == id })
            assertEquals(0, serverCalls.get())
            assertFalse(readiness.isCompleted)

            releaseRead.complete(oldFrame)
            boundedIo { oldRead!!.join() }
            runCurrent()
            // FIRST intended functional RED: old read cannot repopulate confirmed truth after terminal DELETE.
            // Readiness remains held, so a later SERVER reconciliation cannot hide this resurrection.
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.visible)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(ReviewDeleteResult.QUEUED, ReviewDeleteResult.DELETED), events.map { it.result })
            assertEquals(0, serverCalls.get())
            assertFalse(readiness.isCompleted)
            assertEquals(2, reads.get()) // No extra UI refresh is allowed to repair the state.
        } finally {
            releaseRead.complete(oldFrame)
            oldRead?.let { boundedIo { it.join() } }
            deleting?.cancel()
            deleting?.let { boundedIo { it.join() } }
            collecting.cancel()
            // Only test-owned jobs are cancelled; cleanup does not complete readiness or fabricate a backend ACK.
        }
    }


    /** Immutable returned data with one bounded CPU scheduling pause, not a fake SDK query. */
    private class HeldPendingRow(private val row: ListenerReview) : AbstractList<ListenerReview>() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)
        override val size: Int get() = 1
        override fun get(index: Int): ListenerReview {
            check(index == 0)
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "CPU fixture release timed out" }
            }
            return row
        }
    }

    /** Either public operation completed or is causally serialized behind the actual reader thread. */
    private fun awaitCommitCausalOrder(
        reader: Thread, writer: Thread, completed: java.util.concurrent.CountDownLatch
    ): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        val threads = java.lang.management.ManagementFactory.getThreadMXBean()
        while (System.nanoTime() < deadline) {
            if (completed.await(5, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
            val info = threads.getThreadInfo(writer.id)
            if (info != null && info.threadState == Thread.State.BLOCKED && info.lockOwnerId == reader.id) return false
        }
        error("Neither public completion nor reader-owned serialization was observed")
    }

    private fun joinCommitWorker(worker: Thread?) {
        worker ?: return
        worker.join(10_000)
        check(!worker.isAlive) { "Owned CPU worker failed to terminate" }
    }

    /** Every owned worker and scope gets cleanup even when an earlier join failed. */
    private suspend fun cleanupCommitWorkers(owner: kotlinx.coroutines.Job, vararg workers: Thread?) {
        var firstFailure: Throwable? = null
        for (worker in workers) {
            try { joinCommitWorker(worker) }
            catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
        }
        try {
            owner.cancel()
            withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(10_000) { owner.join() } }
        } catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
        firstFailure?.let { throw it }
    }

    @Test
    fun returnedSnapshotCommitCannotOverwriteNewQueuedEdit() = kotlinx.coroutines.runBlocking {
        val seed = ListenerReview("commit-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val oldPending = ListenerReview("commit-work", "listener-a", "Читач A", 5,
            "Стара правка", "Видання E", 100L, 200L)
        val newer = ListenerReview("commit-work", "listener-a", "Читач A", 4,
            "Нова правка", "Видання E", 100L, 300L)
        val id = "commit-work_listener-a"
        val rows = HeldPendingRow(oldPending)
        val readiness = CompletableDeferred<Boolean>()
        val remote = CompletableDeferred<ReviewRemoteResult>()
        val writes = FakeStore().also { it.seed(seed); it.acknowledgements.add(remote) }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val serverCalls = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "commit-work" && uid == "listener-a")
                return when (reads.incrementAndGet()) {
                    1 -> ReviewReadResult.Data(listOf(seed))
                    2 -> ReviewReadResult.Snapshot(listOf(seed), rows, fromCache = true)
                    else -> error("Unexpected additional DEFAULT read")
                }
            }
            override suspend fun awaitPendingWrites(): Boolean = readiness.await()
            override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                serverCalls.incrementAndGet()
                error("No SERVER repair may conceal this CPU-order verdict")
            }
        }
        val owner = kotlinx.coroutines.SupervisorJob()
        val ownedScope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Default)
        val lifecycle = ListenerReviewLifecycle(transport, now = { 300L }, scope = ownedScope)
        val events = java.util.concurrent.CopyOnWriteArrayList<ReviewSaveEvent>()
        val queuedEvent = java.util.concurrent.CountDownLatch(1)
        val collecting = ownedScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            lifecycle.results.collect { events += it; if (it.result == ReviewSaveResult.QUEUED) queuedEvent.countDown() }
        }
        val workerError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writeStarted = java.util.concurrent.CountDownLatch(1)
        val writeDone = java.util.concurrent.CountDownLatch(1)
        val localResult = java.util.concurrent.atomic.AtomicReference<ReviewSaveResult?>()
        var reader: Thread? = null
        var writer: Thread? = null
        try {
            lifecycle.open("commit-work", "listener-a")
            lifecycle.refresh("commit-work")
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertTrue(events.isEmpty())
            reader = Thread({
                try { kotlinx.coroutines.runBlocking { lifecycle.refresh("commit-work") } }
                catch (failure: Throwable) { workerError.compareAndSet(null, failure) }
            }, "q1-returned-read")
            reader!!.start()
            check(rows.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(2, reads.get()) // Query already returned; actual pending CPU processing is paused.
            writer = Thread({
                writeStarted.countDown()
                try {
                    localResult.set(kotlinx.coroutines.runBlocking {
                        lifecycle.enqueueSave("commit-work", "listener-a", "Читач A", 4,
                            "Нова правка", "Видання E", seed)
                    })
                } catch (failure: Throwable) { workerError.compareAndSet(null, failure) }
                finally { writeDone.countDown() }
            }, "q1-new-edit")
            writer!!.start()
            check(writeStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val completedBeforeRelease = awaitCommitCausalOrder(reader!!, writer!!, writeDone)
            if (completedBeforeRelease) {
                workerError.get()?.let { throw it }
                assertEquals(ReviewSaveResult.QUEUED, localResult.get())
                assertEquals(mapOf(id to newer), lifecycle.state.value.pending)
            }
            rows.release.countDown()
            joinCommitWorker(reader); joinCommitWorker(writer)
            workerError.get()?.let { throw it }
            check(queuedEvent.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(ReviewSaveResult.QUEUED, localResult.get())
            assertEquals(newer, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to newer), lifecycle.state.value.pending)
            assertEquals(listOf(newer), lifecycle.state.value.visible)
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })
            assertTrue(events.all { it.workId == "commit-work" && it.documentId == id })
            assertFalse(remote.isCompleted)
            assertFalse(readiness.isCompleted)
            assertEquals(0, serverCalls.get())
        } finally {
            rows.release.countDown()
            cleanupCommitWorkers(owner, reader, writer)
        }
    }

    @Test
    fun returnedSnapshotCommitCannotLeakAcrossWorkOrListenerSwitch() = kotlinx.coroutines.runBlocking {
        val seed = ListenerReview("scope-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val oldPending = ListenerReview("scope-work", "listener-a", "Читач A", 5,
            "Приватна правка A", "Видання E", 100L, 200L)
        for ((nextWork, nextUid) in listOf("scope-work" to "listener-b", "other-work" to "listener-a")) {
            val rows = HeldPendingRow(oldPending)
            val readiness = CompletableDeferred<Boolean>()
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val serverCalls = java.util.concurrent.atomic.AtomicInteger()
            val transport = object : ListenerReviewsStore by FakeStore() {
                override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
                    check(workId == "scope-work" && uid == "listener-a")
                    return when (reads.incrementAndGet()) {
                        1 -> ReviewReadResult.Data(listOf(seed))
                        2 -> ReviewReadResult.Snapshot(listOf(seed), rows, fromCache = true)
                        else -> error("Unexpected additional DEFAULT read")
                    }
                }
                override suspend fun awaitPendingWrites(): Boolean = readiness.await()
                override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                    serverCalls.incrementAndGet()
                    error("No SERVER repair may conceal a scope leak")
                }
            }
            val owner = kotlinx.coroutines.SupervisorJob()
            val ownedScope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Default)
            val lifecycle = ListenerReviewLifecycle(transport, scope = ownedScope)
            val saves = java.util.concurrent.CopyOnWriteArrayList<ReviewSaveEvent>()
            val deletes = java.util.concurrent.CopyOnWriteArrayList<ReviewDeleteEvent>()
            val saving = ownedScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { lifecycle.results.collect { saves += it } }
            val deleting = ownedScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { lifecycle.deleteResults.collect { deletes += it } }
            val workerError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val switchStarted = java.util.concurrent.CountDownLatch(1)
            val switchDone = java.util.concurrent.CountDownLatch(1)
            var reader: Thread? = null
            var opener: Thread? = null
            try {
                lifecycle.open("scope-work", "listener-a")
                lifecycle.refresh("scope-work")
                assertEquals(listOf(seed), lifecycle.state.value.confirmed)
                reader = Thread({
                    try { kotlinx.coroutines.runBlocking { lifecycle.refresh("scope-work") } }
                    catch (failure: Throwable) { workerError.compareAndSet(null, failure) }
                }, "q1-scope-read")
                reader!!.start()
                check(rows.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(2, reads.get())
                opener = Thread({
                    switchStarted.countDown()
                    try { lifecycle.open(nextWork, nextUid) }
                    catch (failure: Throwable) { workerError.compareAndSet(null, failure) }
                    finally { switchDone.countDown() }
                }, "q1-scope-switch")
                opener!!.start()
                check(switchStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val completedBeforeRelease = awaitCommitCausalOrder(reader!!, opener!!, switchDone)
                if (completedBeforeRelease) {
                    workerError.get()?.let { throw it }
                    assertEquals(nextWork, lifecycle.state.value.workId)
                    assertEquals(nextUid, lifecycle.state.value.uid)
                    assertTrue(lifecycle.state.value.pending.isEmpty())
                }
                rows.release.countDown()
                joinCommitWorker(reader); joinCommitWorker(opener)
                workerError.get()?.let { throw it }
                assertEquals(nextWork, lifecycle.state.value.workId)
                assertEquals(nextUid, lifecycle.state.value.uid)
                assertEquals(if (nextWork == "scope-work") listOf(seed) else emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
                assertEquals(if (nextWork == "scope-work") listOf(seed) else emptyList<ListenerReview>(), lifecycle.state.value.visible)
                assertTrue(lifecycle.state.value.pending.isEmpty())
                assertTrue(lifecycle.state.value.deleting.isEmpty())
                assertFalse(lifecycle.state.value.hasFailedMutation)
                assertTrue(saves.isEmpty())
                assertTrue(deletes.isEmpty())
                assertFalse(readiness.isCompleted)
                assertEquals(0, serverCalls.get())
            } finally {
                rows.release.countDown()
                cleanupCommitWorkers(owner, reader, opener)
            }
        }
    }

    @Test
    fun partialReadWithoutRecoveredSaveReconcilesAutomaticallyFromSeparateServerTruth() = runTest {
        val a = ListenerReview("plain-repair-work", "listener-a", "Читач A", 3,
            "Відомий відгук A", "Видання E", 100L, null)
        val b = ListenerReview("plain-repair-work", "listener-b", "Читач B", 2,
            "Серверний відгук B", "Видання F", 50L, 80L)
        val readiness = CompletableDeferred<Boolean>()
        val defaultReads = java.util.concurrent.atomic.AtomicInteger()
        val readinessCalls = java.util.concurrent.atomic.AtomicInteger()
        val serverCalls = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : ListenerReviewsStore by FakeStore() {
            override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "plain-repair-work" && uid == "listener-a")
                return when (defaultReads.incrementAndGet()) {
                    1 -> ReviewReadResult.Data(listOf(a))
                    2 -> ReviewReadResult.Snapshot(listOf(a), emptyList(), fromCache = false,
                        authoritative = false, readFailed = true)
                    else -> error("No UI refresh may repair this state")
                }
            }
            override suspend fun awaitPendingWrites(): Boolean {
                readinessCalls.incrementAndGet()
                return readiness.await()
            }
            override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
                check(workId == "plain-repair-work" && uid == "listener-a")
                serverCalls.incrementAndGet()
                return ReviewReadResult.Snapshot(listOf(a, b), emptyList(), fromCache = false,
                    authoritative = true, readFailed = false)
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, scope = backgroundScope)
        val saves = mutableListOf<ReviewSaveEvent>()
        val deletes = mutableListOf<ReviewDeleteEvent>()
        val saving = backgroundScope.launch { lifecycle.results.collect { saves += it } }
        val deleting = backgroundScope.launch { lifecycle.deleteResults.collect { deletes += it } }
        try {
            lifecycle.open("plain-repair-work", "listener-a")
            lifecycle.refresh("plain-repair-work")
            runCurrent()
            assertEquals(listOf(a), lifecycle.state.value.confirmed)
            lifecycle.refresh("plain-repair-work")
            runCurrent()
            assertEquals(listOf(a), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.readFailed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertEquals(1, readinessCalls.get())
            assertEquals(0, serverCalls.get())
            assertTrue(saves.isEmpty()); assertTrue(deletes.isEmpty())

            readiness.complete(true)
            runCurrent()
            assertEquals(listOf(a, b), lifecycle.state.value.confirmed)
            assertEquals(listOf(a, b), lifecycle.state.value.visible)
            assertFalse(lifecycle.state.value.readFailed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(1, readinessCalls.get())
            assertEquals(1, serverCalls.get())
            assertEquals(2, defaultReads.get())
            assertTrue(saves.isEmpty()); assertTrue(deletes.isEmpty())
        } finally { saving.cancel(); deleting.cancel() }
    }


    @Test
    fun delayedSaveCannotExposePendingBeforeActualLocalAcceptance() = runTest {
        val seed = ListenerReview("held-save-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val draft = ListenerReview("held-save-work", "listener-a", "Читач A", 5,
            "Правка після прийняття", "Видання E", 100L, 300L)
        val id = "held-save-work_listener-a"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<ReviewRemoteResult>()
        val locallyAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val writes = FakeStore().also { it.seed(seed); it.acknowledgements.add(remote) }
        val hooks = mutableListOf<ListenerReview>()
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
                check(review == draft && ListenerReviewCodec.documentId(review.workId, review.uid) == id)
                entered.complete(Unit)
                release.await() // Nothing has entered the fake durable store yet.
                val receipt = writes.enqueueReview(review)
                check(receipt is ReviewWriteReceipt.Queued)
                locallyAccepted.set(true)
                return receipt
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, now = { 300L }, scope = backgroundScope,
            onAccepted = { check(locallyAccepted.get()); hooks += it })
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch { lifecycle.results.collect { events += it } }
        val localResult = CompletableDeferred<ReviewSaveResult>()
        var caller: kotlinx.coroutines.Job? = null
        try {
            // Establish literal confirmed state before starting any mutation job.
            lifecycle.open("held-save-work", "listener-a")
            lifecycle.refresh("held-save-work")
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            caller = backgroundScope.launch {
                localResult.complete(lifecycle.enqueueSave("held-save-work", "listener-a", "Читач A", 5,
                    "Правка після прийняття", "Видання E", seed))
            }
            runCurrent()
            assertTrue(entered.isCompleted)
            assertFalse(release.isCompleted)
            assertFalse(locallyAccepted.get())
            assertFalse(requireNotNull(caller).isCompleted)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            // FIRST measured RED: awaiting a suspendable enqueue is not local acceptance.
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(listOf(seed), lifecycle.state.value.visible)
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertTrue(events.isEmpty())
            assertTrue(hooks.isEmpty())

            release.complete(Unit)
            runCurrent()
            assertTrue(locallyAccepted.get())
            assertEquals(ReviewSaveResult.QUEUED, localResult.await())
            assertEquals(draft, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to draft), lifecycle.state.value.pending)
            assertEquals(listOf(draft), lifecycle.state.value.visible)
            assertEquals(listOf(draft), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })
            assertTrue(events.all { it.workId == "held-save-work" && it.documentId == id })
            assertFalse(remote.isCompleted)
        } finally {
            caller?.cancel()
            caller?.join()
            collecting.cancel()
            // Cancellation before receipt must not fabricate local acceptance/remote ACK during cleanup.
        }
    }

    @Test
    fun delayedDeleteCannotHideConfirmedCardBeforeActualLocalAcceptance() = runTest {
        val seed = ListenerReview("held-delete-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val id = "held-delete-work_listener-a"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<Boolean>()
        val locallyAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val writes = FakeStore().also { it.seed(seed); it.deleteAcknowledgements.add(remote) }
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt {
                check(documentId == id)
                entered.complete(Unit)
                release.await() // No SDK-like document removal has happened yet.
                val receipt = writes.enqueueDelete(documentId)
                check(receipt is ReviewDeleteReceipt.Queued)
                locallyAccepted.set(true)
                return receipt
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, scope = backgroundScope)
        val events = mutableListOf<ReviewDeleteEvent>()
        val collecting = backgroundScope.launch { lifecycle.deleteResults.collect { events += it } }
        var caller: kotlinx.coroutines.Job? = null
        try {
            lifecycle.open("held-delete-work", "listener-a")
            lifecycle.refresh("held-delete-work")
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            caller = backgroundScope.launch { lifecycle.delete("held-delete-work", "listener-a") }
            runCurrent()
            assertTrue(entered.isCompleted)
            assertFalse(release.isCompleted)
            assertFalse(locallyAccepted.get())
            assertFalse(requireNotNull(caller).isCompleted)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            // FIRST measured RED: an unaccepted delete must retain the complete confirmed card.
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(listOf(seed), lifecycle.state.value.visible)
            assertEquals(emptySet<String>(), lifecycle.state.value.deleting)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertTrue(events.isEmpty())

            release.complete(Unit)
            runCurrent()
            assertTrue(locallyAccepted.get())
            assertFalse(writes.documents.containsKey(id))
            assertTrue(writes.deleteAcknowledgements.isEmpty())
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertTrue(lifecycle.state.value.visible.isEmpty())
            assertEquals(setOf(id), lifecycle.state.value.deleting)
            assertEquals(listOf(ReviewDeleteResult.QUEUED), events.map { it.result })
            assertTrue(events.all { it.workId == "held-delete-work" && it.documentId == id })
            assertFalse(remote.isCompleted)
            assertFalse(requireNotNull(caller).isCompleted)
        } finally {
            caller?.cancel()
            caller?.join()
            collecting.cancel()
            // A held preacceptance gate and held backend receipt are never completed by cleanup.
        }
    }



    @Test
    fun rejectedNewSaveFailureSurvivesOlderAcceptedSaveAcknowledgement() = runTest {
        val workId = "rejected-save-work"
        val id = "rejected-save-work_listener-a"
        val seed = ListenerReview("rejected-save-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val acceptedA = ListenerReview("rejected-save-work", "listener-a", "Читач A", 5,
            "Прийнята правка A", "Видання E", 100L, 200L)
        val rejectedB = ListenerReview("rejected-save-work", "listener-a", "Читач A", 2,
            "Відхилена правка B", "Видання E", 100L, 300L)
        val olderAck = CompletableDeferred<ReviewRemoteResult>()
        val rejectionEntered = CompletableDeferred<Unit>()
        val writes = FakeStore().also { it.seed(seed); it.acknowledgements.add(olderAck) }
        val observedEnqueues = mutableListOf<ListenerReview>()
        var rejectB = true
        var clock = 200L
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
                observedEnqueues += review
                check(ListenerReviewCodec.documentId(review.workId, review.uid) == id)
                return when (review) {
                    acceptedA -> writes.enqueueReview(review)
                    rejectedB -> if (rejectB) {
                        rejectionEntered.complete(Unit)
                        ReviewWriteReceipt.Rejected // No physical write or receipt is made for B.
                    } else writes.enqueueReview(review)
                    else -> error("Unexpected local review payload")
                }
            }
        }
        val hooks = mutableListOf<ListenerReview>()
        val lifecycle = ListenerReviewLifecycle(transport, now = { clock }, scope = backgroundScope,
            onAccepted = { hooks += it })
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch { lifecycle.results.collect { events += it } }
        try {
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            runCurrent() // The public event collector is subscribed before either mutation.

            assertEquals(ReviewSaveResult.QUEUED, lifecycle.enqueueSave(workId, "listener-a", "Читач A", 5,
                "Прийнята правка A", "Видання E", seed))
            runCurrent()
            assertEquals(listOf(acceptedA), observedEnqueues)
            assertEquals(acceptedA, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertTrue(writes.acknowledgements.isEmpty())
            assertFalse(olderAck.isCompleted)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to acceptedA), lifecycle.state.value.pending)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())
            assertEquals(listOf(acceptedA), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })
            val queuedA = events.single()
            assertEquals(workId, queuedA.workId)
            assertEquals(id, queuedA.documentId)

            clock = 300L
            assertEquals(ReviewSaveResult.FAILED, lifecycle.enqueueSave(workId, "listener-a", "Читач A", 2,
                "Відхилена правка B", "Видання E", seed))
            runCurrent()
            assertTrue(rejectionEntered.isCompleted)
            assertEquals(listOf(acceptedA, rejectedB), observedEnqueues)
            assertEquals(acceptedA, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertFalse(olderAck.isCompleted)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to acceptedA), lifecycle.state.value.pending)
            assertEquals(listOf(acceptedA), lifecycle.state.value.visible)
            assertEquals(mapOf(id to rejectedB), lifecycle.state.value.failedSave)
            assertTrue(lifecycle.state.value.failedDelete.isEmpty())
            assertEquals(listOf(acceptedA), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.FAILED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            val failedB = events.last()
            assertTrue(failedB.generation > queuedA.generation)

            // This is the actual held receipt for the older physical local write, not a fabricated UI callback.
            olderAck.complete(ReviewRemoteResult.PUBLISHED)
            runCurrent()
            assertTrue(olderAck.isCompleted)
            assertEquals(acceptedA, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(acceptedA), lifecycle.state.value.confirmed)
            assertEquals(listOf(queuedA, failedB,
                ReviewSaveEvent(workId, id, queuedA.generation, ReviewSaveResult.PUBLISHED)), events)
            // FIRST functional RED: publishing A must not erase the newer rejected B retry payload.
            assertEquals(mapOf(id to rejectedB), lifecycle.state.value.failedSave)
            assertTrue(lifecycle.state.value.hasFailedMutation)
            assertTrue(lifecycle.state.value.failedDelete.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertEquals(listOf(acceptedA), hooks)

            // Executed only if the first oracle passes: retry must resend exact B, including its old clock value.
            rejectB = false
            clock = 999L
            writes.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.PUBLISHED))
            assertEquals(id, lifecycle.retry(workId))
            runCurrent()
            assertEquals(listOf(acceptedA, rejectedB, rejectedB), observedEnqueues)
            assertEquals(rejectedB, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(listOf(rejectedB), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(acceptedA, rejectedB), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.FAILED, ReviewSaveResult.PUBLISHED,
                ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            assertTrue(events[3].generation > failedB.generation)
            assertEquals(events[3].generation, events[4].generation)
        } finally {
            collecting.cancel()
            collecting.join()
            // No additional acceptance, ACK or retry is manufactured during cleanup.
        }
    }


    @Test
    fun sameDocumentSavesEnterLocalEnqueueInInvocationOrderWithoutAwaitingBackend() = runTest {
        val workId = "physical-fifo-work"
        val id = "physical-fifo-work_listener-a"
        val seed = ListenerReview("physical-fifo-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val saveA = ListenerReview("physical-fifo-work", "listener-a", "Читач A", 5,
            "Перша правка A", "Видання E", 100L, 200L)
        val saveB = ListenerReview("physical-fifo-work", "listener-a", "Читач A", 2,
            "Наступна правка B", "Видання E", 100L, 300L)
        val aEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bInvoked = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()
        val aAccepted = CompletableDeferred<Unit>()
        val bAccepted = CompletableDeferred<Unit>()
        val remoteA = CompletableDeferred<ReviewRemoteResult>()
        val remoteB = CompletableDeferred<ReviewRemoteResult>()
        val seamEntries = mutableListOf<ListenerReview>()
        val physicalWrites = mutableListOf<ListenerReview>()
        val writes = FakeStore().also { it.seed(seed) }
        var clock = 200L
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
                check(ListenerReviewCodec.documentId(review.workId, review.uid) == id)
                check(review == saveA || review == saveB)
                seamEntries += review // Actual invoked seam entry, not caller/UI registration.
                if (review == saveA) {
                    aEntered.complete(Unit)
                    releaseA.await() // Only A is held BEFORE physical write and local Queued.
                } else bEntered.complete(Unit) // B has no fixture gate imposing the desired order.
                // ACK identity follows the literal payload, regardless of observed physical order.
                writes.acknowledgements.add(if (review == saveA) remoteA else remoteB)
                val receipt = writes.enqueueReview(review)
                check(receipt is ReviewWriteReceipt.Queued)
                physicalWrites += review
                (if (review == saveA) aAccepted else bAccepted).complete(Unit)
                return receipt
            }
        }
        val hooks = mutableListOf<ListenerReview>()
        val lifecycle = ListenerReviewLifecycle(transport, now = { clock }, scope = backgroundScope,
            onAccepted = { hooks += it })
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch { lifecycle.results.collect { events += it } }
        val resultA = CompletableDeferred<ReviewSaveResult>()
        val resultB = CompletableDeferred<ReviewSaveResult>()
        var callerA: kotlinx.coroutines.Job? = null
        var callerB: kotlinx.coroutines.Job? = null
        try {
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            runCurrent() // Subscribe event collector before either caller.
            callerA = backgroundScope.launch {
                resultA.complete(lifecycle.enqueueSave(workId, "listener-a", "Читач A", 5,
                    "Перша правка A", "Видання E", seed))
            }
            runCurrent()
            assertTrue(aEntered.isCompleted)
            assertFalse(releaseA.isCompleted)
            assertFalse(aAccepted.isCompleted)
            assertFalse(requireNotNull(callerA).isCompleted)
            assertEquals(listOf(saveA), seamEntries)
            assertTrue(physicalWrites.isEmpty())
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(events.isEmpty())
            assertTrue(hooks.isEmpty())
            assertFalse(remoteA.isCompleted)
            assertFalse(remoteB.isCompleted)

            clock = 300L
            callerB = backgroundScope.launch {
                bInvoked.complete(Unit) // Public caller really attempted B while A is inside local enqueue.
                resultB.complete(lifecycle.enqueueSave(workId, "listener-a", "Читач A", 2,
                    "Наступна правка B", "Видання E", seed))
            }
            runCurrent()
            assertTrue(bInvoked.isCompleted)
            assertFalse(releaseA.isCompleted)
            assertFalse(aAccepted.isCompleted)
            assertFalse(requireNotNull(callerA).isCompleted)
            // FIRST functional RED: B must not enter the actual local seam ahead of held A.
            assertEquals(listOf(saveA), seamEntries)
            assertFalse(bEntered.isCompleted)
            assertFalse(bAccepted.isCompleted)
            assertFalse(requireNotNull(callerB).isCompleted)
            assertTrue(physicalWrites.isEmpty())
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(events.isEmpty())
            assertTrue(hooks.isEmpty())

            releaseA.complete(Unit)
            runCurrent()
            assertTrue(aAccepted.isCompleted)
            assertTrue(bEntered.isCompleted)
            assertTrue(bAccepted.isCompleted)
            assertEquals(listOf(saveA, saveB), seamEntries)
            assertEquals(listOf(saveA, saveB), physicalWrites)
            assertEquals(saveB, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertTrue(writes.acknowledgements.isEmpty())
            assertTrue(requireNotNull(callerA).isCompleted)
            assertTrue(requireNotNull(callerB).isCompleted)
            assertEquals(ReviewSaveResult.QUEUED, resultA.await())
            assertEquals(ReviewSaveResult.QUEUED, resultB.await())
            assertFalse(remoteA.isCompleted)
            assertFalse(remoteB.isCompleted) // A backend wait must never hold the local FIFO gate.
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to saveB), lifecycle.state.value.pending)
            assertEquals(listOf(saveB), lifecycle.state.value.visible)
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(saveA, saveB), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.QUEUED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            val queuedA = events[0]
            val queuedB = events[1]
            assertTrue(queuedB.generation > queuedA.generation)

            remoteA.complete(ReviewRemoteResult.PUBLISHED)
            runCurrent()
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to saveB), lifecycle.state.value.pending)
            assertEquals(listOf(queuedA, queuedB), events) // Superseded A cannot emit a terminal verdict.
            assertEquals(saveB, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertFalse(remoteB.isCompleted)

            remoteB.complete(ReviewRemoteResult.PUBLISHED)
            runCurrent()
            assertEquals(listOf(saveB), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(queuedA, queuedB,
                ReviewSaveEvent(workId, id, queuedB.generation, ReviewSaveResult.PUBLISHED)), events)
        } finally {
            val owned = listOfNotNull(callerA, callerB, collecting)
            owned.forEach { it.cancel() }
            var cleanupFailure: Throwable? = null
            for (job in owned) {
                try { kotlinx.coroutines.withTimeout(10_000) { job.join() } }
                catch (failure: Throwable) { if (cleanupFailure == null) cleanupFailure = failure }
            }
            cleanupFailure?.let { throw it }
            // Cleanup never releases local acceptance or completes a backend receipt.
        }
    }

    @Test
    fun rejectedNewSaveFailureSurvivesOlderAcceptedSaveRemoteFailure() = runTest {
        val workId = "remote-failed-save-work"
        val id = "remote-failed-save-work_listener-a"
        val seed = ListenerReview("remote-failed-save-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val acceptedA = ListenerReview("remote-failed-save-work", "listener-a", "Читач A", 5,
            "Прийнята правка A", "Видання E", 100L, 200L)
        val rejectedB = ListenerReview("remote-failed-save-work", "listener-a", "Читач A", 2,
            "Відхилена правка B", "Видання E", 100L, 300L)
        val olderAck = CompletableDeferred<ReviewRemoteResult>()
        val rejectionEntered = CompletableDeferred<Unit>()
        val writes = FakeStore().also { it.seed(seed); it.acknowledgements.add(olderAck) }
        val observedEnqueues = mutableListOf<ListenerReview>()
        var rejectB = true
        var clock = 200L
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
                observedEnqueues += review
                check(ListenerReviewCodec.documentId(review.workId, review.uid) == id)
                return when (review) {
                    acceptedA -> writes.enqueueReview(review)
                    rejectedB -> if (rejectB) {
                        rejectionEntered.complete(Unit)
                        ReviewWriteReceipt.Rejected // No physical write or receipt is made for B.
                    } else writes.enqueueReview(review)
                    else -> error("Unexpected local review payload")
                }
            }
        }
        val hooks = mutableListOf<ListenerReview>()
        val lifecycle = ListenerReviewLifecycle(transport, now = { clock }, scope = backgroundScope,
            onAccepted = { hooks += it })
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch { lifecycle.results.collect { events += it } }
        try {
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            runCurrent() // The public event collector is subscribed before either mutation.

            assertEquals(ReviewSaveResult.QUEUED, lifecycle.enqueueSave(workId, "listener-a", "Читач A", 5,
                "Прийнята правка A", "Видання E", seed))
            runCurrent()
            assertEquals(listOf(acceptedA), observedEnqueues)
            assertEquals(acceptedA, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertTrue(writes.acknowledgements.isEmpty())
            assertFalse(olderAck.isCompleted)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to acceptedA), lifecycle.state.value.pending)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())
            assertEquals(listOf(acceptedA), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED), events.map { it.result })
            val queuedA = events.single()
            assertEquals(workId, queuedA.workId)
            assertEquals(id, queuedA.documentId)

            clock = 300L
            assertEquals(ReviewSaveResult.FAILED, lifecycle.enqueueSave(workId, "listener-a", "Читач A", 2,
                "Відхилена правка B", "Видання E", seed))
            runCurrent()
            assertTrue(rejectionEntered.isCompleted)
            assertEquals(listOf(acceptedA, rejectedB), observedEnqueues)
            assertEquals(acceptedA, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertFalse(olderAck.isCompleted)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(mapOf(id to acceptedA), lifecycle.state.value.pending)
            assertEquals(listOf(acceptedA), lifecycle.state.value.visible)
            assertEquals(mapOf(id to rejectedB), lifecycle.state.value.failedSave)
            assertTrue(lifecycle.state.value.failedDelete.isEmpty())
            assertEquals(listOf(acceptedA), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.FAILED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            val failedB = events.last()
            assertTrue(failedB.generation > queuedA.generation)

            // Fail the actual held receipt consumed by A; the lifecycle decides its terminal event and state.
            olderAck.complete(ReviewRemoteResult.FAILED)
            runCurrent()
            assertTrue(olderAck.isCompleted)
            // This simple fake models accepted local storage, not backend rollback or Firestore persistence.
            assertEquals(acceptedA, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(listOf(seed), lifecycle.state.value.visible)
            assertEquals(listOf(queuedA, failedB,
                ReviewSaveEvent(workId, id, queuedA.generation, ReviewSaveResult.FAILED)), events)
            // FIRST functional RED: remote failure of A must not replace the newer rejected B retry payload.
            assertEquals(mapOf(id to rejectedB), lifecycle.state.value.failedSave)
            assertTrue(lifecycle.state.value.hasFailedMutation)
            assertTrue(lifecycle.state.value.failedDelete.isEmpty())
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertEquals(listOf(acceptedA), hooks)

            // Executed only if the first oracle passes: retry must resend exact B, including its old clock value.
            rejectB = false
            clock = 999L
            writes.acknowledgements.add(CompletableDeferred(ReviewRemoteResult.PUBLISHED))
            assertEquals(id, lifecycle.retry(workId))
            runCurrent()
            assertEquals(listOf(acceptedA, rejectedB, rejectedB), observedEnqueues)
            assertEquals(rejectedB, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(listOf(rejectedB), lifecycle.state.value.confirmed)
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(acceptedA, rejectedB), hooks)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.FAILED, ReviewSaveResult.FAILED,
                ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            assertTrue(events[3].generation > failedB.generation)
            assertEquals(events[3].generation, events[4].generation)
        } finally {
            collecting.cancel()
            collecting.join()
            // No additional acceptance, ACK or retry is manufactured during cleanup.
        }
    }


    @Test
    fun rejectedNewDeleteFailureSurvivesOlderAcceptedDeleteSuccess() = runTest {
        val workId = "delete-success-rejected-work"
        val id = "delete-success-rejected-work_listener-a"
        val seed = ListenerReview("delete-success-rejected-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val olderAck = CompletableDeferred<Boolean>()
        val rejectionEntered = CompletableDeferred<Unit>()
        val writes = FakeStore().also { it.seed(seed); it.deleteAcknowledgements.add(olderAck) }
        val observedEnqueues = mutableListOf<String>()
        val acceptedLocalDeletes = mutableListOf<String>()
        var rejectB = true
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt {
                check(documentId == id)
                observedEnqueues += documentId
                return if (observedEnqueues.size == 2 && rejectB) {
                    rejectionEntered.complete(Unit)
                    ReviewDeleteReceipt.Rejected // B cannot remove a document or consume an ACK.
                } else {
                    val receipt = writes.enqueueDelete(documentId)
                    check(receipt is ReviewDeleteReceipt.Queued)
                    acceptedLocalDeletes += documentId
                    receipt
                }
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, scope = backgroundScope)
        val events = mutableListOf<ReviewDeleteEvent>()
        val collecting = backgroundScope.launch { lifecycle.deleteResults.collect { events += it } }
        val resultA = CompletableDeferred<ReviewDeleteResult>()
        val deletingA = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            resultA.complete(lifecycle.delete(workId, "listener-a"))
        }
        try {
            // Start the actual A caller only after the exact public seed read.
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            deletingA.start()
            runCurrent()

            // Actual A local acceptance removes the fake document before its held backend verdict.
            assertEquals(listOf(id), observedEnqueues)
            assertEquals(listOf(id), acceptedLocalDeletes)
            assertFalse(writes.documents.containsKey(id))
            assertTrue(writes.deleteAcknowledgements.isEmpty())
            assertFalse(olderAck.isCompleted)
            assertFalse(resultA.isCompleted)
            assertTrue(deletingA.isActive)
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertTrue(lifecycle.state.value.visible.isEmpty())
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertEquals(setOf(id), lifecycle.state.value.deleting)
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(ReviewDeleteResult.QUEUED), events.map { it.result })
            val queuedA = events.single()
            assertEquals(workId, queuedA.workId)
            assertEquals(id, queuedA.documentId)

            assertEquals(ReviewDeleteResult.FAILED, lifecycle.delete(workId, "listener-a"))
            runCurrent()
            assertTrue(rejectionEntered.isCompleted)
            assertEquals(listOf(id, id), observedEnqueues)
            assertEquals(listOf(id), acceptedLocalDeletes) // B never reached physical local deletion.
            assertFalse(writes.documents.containsKey(id))
            assertFalse(olderAck.isCompleted)
            assertFalse(resultA.isCompleted)
            assertEquals(setOf(id), lifecycle.state.value.deleting)
            assertEquals(setOf(id), lifecycle.state.value.failedDelete)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertTrue(lifecycle.state.value.visible.isEmpty())
            assertEquals(listOf(ReviewDeleteResult.QUEUED, ReviewDeleteResult.FAILED), events.map { it.result })
            val failedB = events.last()
            assertTrue(failedB.generation > queuedA.generation)
            assertTrue(events.all { it.workId == workId && it.documentId == id })

            // Complete the actual receipt consumed by A; its public terminal must occur before the retry oracle.
            olderAck.complete(true)
            runCurrent()
            assertTrue(olderAck.isCompleted)
            assertTrue(resultA.isCompleted)
            assertEquals(ReviewDeleteResult.DELETED, resultA.await())
            assertTrue(deletingA.isCompleted)
            assertFalse(writes.documents.containsKey(id))
            assertEquals(listOf(id), acceptedLocalDeletes)
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertTrue(lifecycle.state.value.visible.isEmpty())
            assertEquals(listOf(queuedA, failedB,
                ReviewDeleteEvent(workId, id, queuedA.generation, ReviewDeleteResult.DELETED)), events)
            // FIRST functional RED: success of A cannot erase the newer locally rejected B retry intent.
            assertEquals(setOf(id), lifecycle.state.value.failedDelete)
            assertTrue(lifecycle.state.value.hasFailedMutation)
            assertTrue(lifecycle.state.value.failedSave.isEmpty())

            // Executed only after preservation passes: retry performs B's same-document DELETE through the public seam.
            rejectB = false
            writes.deleteAcknowledgements.add(CompletableDeferred(true))
            assertEquals(id, lifecycle.retry(workId))
            runCurrent()
            assertEquals(listOf(id, id, id), observedEnqueues)
            assertEquals(listOf(id, id), acceptedLocalDeletes)
            assertFalse(writes.documents.containsKey(id))
            assertTrue(writes.deleteAcknowledgements.isEmpty())
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertTrue(lifecycle.state.value.deleting.isEmpty())
            assertTrue(lifecycle.state.value.pending.isEmpty())
            assertTrue(lifecycle.state.value.confirmed.isEmpty())
            assertEquals(listOf(ReviewDeleteResult.QUEUED, ReviewDeleteResult.FAILED, ReviewDeleteResult.DELETED,
                ReviewDeleteResult.QUEUED, ReviewDeleteResult.DELETED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            assertTrue(events[3].generation > failedB.generation)
            assertEquals(events[3].generation, events[4].generation)
        } finally {
            deletingA.cancel()
            collecting.cancel()
            deletingA.join()
            collecting.join()
            // Cleanup never accepts another write, completes an ACK, retries or synthesizes events.
        }
    }

    @Test
    fun acceptedSaveSettlesOnceAfterCallerIsCancelledInsideAcceptanceHook() = runTest {
        val workId = "cancelled-accepted-save-work"
        val id = "cancelled-accepted-save-work_listener-a"
        val seed = ListenerReview("cancelled-accepted-save-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val accepted = ListenerReview("cancelled-accepted-save-work", "listener-a", "Читач A", 5,
            "Прийнята правка A", "Видання E", 100L, 200L)
        val backendMaySettle = CompletableDeferred<Unit>()
        val actualBackendVerdict = CompletableDeferred<ReviewRemoteResult>()
        val hookEntered = CompletableDeferred<Unit>()
        val hookMayReturn = CompletableDeferred<Unit>()
        val writes = FakeStore().also { it.seed(seed) }
        val observedEnqueues = mutableListOf<ListenerReview>()
        val acceptedHooks = mutableListOf<ListenerReview>()
        var physicalAcceptance = false
        var receiptAwaitCount = 0
        val consumedVerdicts = mutableListOf<ReviewRemoteResult>()
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
                check(review == accepted && ListenerReviewCodec.documentId(review.workId, review.uid) == id)
                observedEnqueues += review
                writes.documents[id] = ListenerReviewCodec.toMap(review)
                physicalAcceptance = true
                // The actual returned local receipt awaits this backend producer's controlled verdict.
                return ReviewWriteReceipt.Queued {
                    receiptAwaitCount++
                    actualBackendVerdict.await().also { consumedVerdicts += it }
                }
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, now = { 200L }, scope = backgroundScope,
            onAccepted = { review ->
                acceptedHooks += review
                hookEntered.complete(Unit)
                hookMayReturn.await()
            })
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch { lifecycle.results.collect { events += it } }
        val backend = backgroundScope.launch {
            backendMaySettle.await()
            actualBackendVerdict.complete(ReviewRemoteResult.PUBLISHED)
        }
        val callerReturned = CompletableDeferred<ReviewSaveResult>()
        val caller = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            callerReturned.complete(lifecycle.enqueueSave(workId, "listener-a", "Читач A", 5,
                "Прийнята правка A", "Видання E", seed))
        }
        try {
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            runCurrent() // Subscribe the event observer and start the real held backend producer first.
            caller.start()
            runCurrent()

            assertTrue(physicalAcceptance)
            assertEquals(listOf(accepted), observedEnqueues)
            assertEquals(accepted, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertTrue(hookEntered.isCompleted)
            assertFalse(hookMayReturn.isCompleted)
            assertEquals(listOf(accepted), acceptedHooks)
            assertTrue(caller.isActive)
            assertFalse(callerReturned.isCompleted)
            assertFalse(actualBackendVerdict.isCompleted)
            assertEquals(mapOf(id to accepted), lifecycle.state.value.pending)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertFalse(lifecycle.state.value.hasFailedMutation)

            // Cancel only the suspended public caller. The lifecycle's provided scope and backend remain alive.
            caller.cancel()
            runCurrent()
            assertTrue(caller.isCancelled)
            assertTrue(caller.isCompleted)
            assertFalse(callerReturned.isCompleted)
            assertFalse(hookMayReturn.isCompleted)
            assertTrue(backend.isActive)
            assertEquals(mapOf(id to accepted), lifecycle.state.value.pending)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)

            // Settle the very backend verdict awaited by the returned Queued receipt, not a lifecycle callback.
            backendMaySettle.complete(Unit)
            runCurrent()
            assertTrue(backend.isCompleted)
            assertTrue(actualBackendVerdict.isCompleted)
            assertEquals(ReviewRemoteResult.PUBLISHED, actualBackendVerdict.await())
            assertEquals(listOf(accepted), observedEnqueues)
            assertEquals(accepted, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            // FIRST functional RED: accepted write must settle through its surviving owner despite caller cancellation.
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(accepted), lifecycle.state.value.confirmed)
            assertEquals(listOf(accepted), lifecycle.state.value.visible)
            assertEquals(1, receiptAwaitCount)
            assertEquals(listOf(ReviewRemoteResult.PUBLISHED), consumedVerdicts)
            assertEquals(listOf(ReviewSaveResult.QUEUED, ReviewSaveResult.PUBLISHED), events.map { it.result })
            assertTrue(events.all { it.workId == workId && it.documentId == id })
            assertEquals(events[0].generation, events[1].generation)
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertEquals(listOf(accepted), acceptedHooks)

            runCurrent() // All immediate work drained; no extra verdict or hook release is manufactured.
            assertEquals(1, receiptAwaitCount)
            assertEquals(listOf(ReviewRemoteResult.PUBLISHED), consumedVerdicts)
            assertEquals(2, events.size)
            assertEquals(mapOf<String, ListenerReview>(), lifecycle.state.value.pending)
        } finally {
            caller.cancel()
            backend.cancel()
            collecting.cancel()
            caller.join()
            backend.join()
            collecting.join()
            // Never release the held hook or complete another backend verdict during teardown.
        }
    }

    @Test
    fun acceptedSaveRetiresPendingAfterBackendSuccessWhileQueuedNoticeIsBackpressured() = runTest {
        val workId = "backpressured-accepted-save-work"
        val id = "backpressured-accepted-save-work_listener-a"
        val seed = ListenerReview("backpressured-accepted-save-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val accepted = ListenerReview("backpressured-accepted-save-work", "listener-a", "Читач A", 5,
            "Прийнята правка A", "Видання E", 100L, 200L)
        val writes = FakeStore().also { it.seed(seed) }
        val observedEnqueues = mutableListOf<ListenerReview>()
        val backendMaySettle = CompletableDeferred<Unit>()
        val actualBackendVerdict = CompletableDeferred<ReviewRemoteResult>()
        var receiptAwaitCount = 0
        val consumedVerdicts = mutableListOf<ReviewRemoteResult>()
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
                check(review == accepted && ListenerReviewCodec.documentId(review.workId, review.uid) == id)
                observedEnqueues += review
                writes.documents[id] = ListenerReviewCodec.toMap(review)
                return ReviewWriteReceipt.Queued {
                    receiptAwaitCount++
                    actualBackendVerdict.await().also { consumedVerdicts += it }
                }
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, now = { 200L }, scope = backgroundScope)
        val observerEntered = CompletableDeferred<Unit>()
        val observerMayResume = CompletableDeferred<Unit>()
        val events = mutableListOf<ReviewSaveEvent>()
        val collecting = backgroundScope.launch {
            lifecycle.results.collect { event ->
                events += event
                if (events.size == 1) {
                    observerEntered.complete(Unit)
                    observerMayResume.await()
                }
            }
        }
        val backend = backgroundScope.launch {
            backendMaySettle.await()
            actualBackendVerdict.complete(ReviewRemoteResult.PUBLISHED)
        }
        val callerResult = CompletableDeferred<ReviewSaveResult>()
        val caller = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            callerResult.complete(lifecycle.enqueueSave(workId, "listener-a", "Читач A", 5,
                "Прийнята правка A", "Видання E", seed))
        }
        val probeEntered = CompletableDeferred<Unit>()
        val probeResult = CompletableDeferred<ReviewSaveResult>()
        val probe = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            probeEntered.complete(Unit)
            probeResult.complete(lifecycle.enqueueSave(workId, "listener-a", "Читач A", 0,
                null, null, null))
        }
        try {
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            runCurrent() // Subscribe the real collector before emitting any public attempt.

            assertEquals(ReviewSaveResult.FAILED, lifecycle.enqueueSave(workId, "listener-a", "Читач A", 0,
                null, null, null))
            runCurrent()
            assertTrue(observerEntered.isCompleted)
            assertFalse(observerMayResume.isCompleted)
            assertEquals(listOf(ReviewSaveResult.FAILED), events.map { it.result })
            val first = events.single()
            assertEquals(workId, first.workId)
            assertEquals(id, first.documentId)

            // These are 16 real public invalid-save FAILED notices; no flow mutation or fabricated event.
            repeat(16) {
                assertEquals(ReviewSaveResult.FAILED, lifecycle.enqueueSave(workId, "listener-a", "Читач A", 0,
                    null, null, null))
            }
            assertEquals(1, events.size)
            assertTrue(observedEnqueues.isEmpty()) // Invalid attempts never reached the physical write seam.
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertFalse(lifecycle.state.value.hasFailedMutation)

            caller.start()
            runCurrent()
            assertTrue(callerResult.isCompleted)
            assertEquals(ReviewSaveResult.QUEUED, callerResult.await())
            assertTrue(caller.isCompleted)
            assertEquals(listOf(accepted), observedEnqueues)
            assertEquals(accepted, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            assertEquals(mapOf(id to accepted), lifecycle.state.value.pending)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertFalse(actualBackendVerdict.isCompleted)
            assertFalse(observerMayResume.isCompleted)
            assertEquals(1, events.size)

            // This actual public rejection cannot return: its suspending notice is behind A's QUEUED emission.
            probe.start()
            runCurrent()
            assertTrue(probeEntered.isCompleted)
            assertTrue(probe.isActive)
            assertFalse(probeResult.isCompleted)
            assertEquals(1, events.size)
            assertEquals(listOf(accepted), observedEnqueues)
            assertTrue(backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

            backendMaySettle.complete(Unit)
            runCurrent()
            assertTrue(backend.isCompleted)
            assertTrue(actualBackendVerdict.isCompleted)
            assertEquals(ReviewRemoteResult.PUBLISHED, actualBackendVerdict.await())
            assertFalse(observerMayResume.isCompleted)
            assertEquals(listOf(first), events) // The real UI subscriber is still held; nothing was dropped/released.
            assertTrue(probe.isActive)
            assertFalse(probeResult.isCompleted)
            // FIRST functional RED: actual backend success must retire pending even while QUEUED delivery is blocked.
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertEquals(listOf(accepted), lifecycle.state.value.confirmed)
            assertEquals(listOf(accepted), lifecycle.state.value.visible)
            assertEquals(1, receiptAwaitCount)
            assertEquals(listOf(ReviewRemoteResult.PUBLISHED), consumedVerdicts)
            assertFalse(lifecycle.state.value.hasFailedMutation)

            // Executed only after state retirement passes: drain actual retained notices without inventing callbacks.
            observerMayResume.complete(Unit)
            runCurrent()
            assertTrue(probeResult.isCompleted)
            assertEquals(ReviewSaveResult.FAILED, probeResult.await())
            assertTrue(probe.isCompleted)
            val prefix = (0L..16L).map { offset ->
                ReviewSaveEvent(workId, id, first.generation + offset, ReviewSaveResult.FAILED)
            }
            val queuedA = ReviewSaveEvent(workId, id, first.generation + 17L, ReviewSaveResult.QUEUED)
            val rejectedProbe = ReviewSaveEvent(workId, id, first.generation + 18L, ReviewSaveResult.FAILED)
            val publishedA = ReviewSaveEvent(workId, id, queuedA.generation, ReviewSaveResult.PUBLISHED)
            assertEquals(prefix + listOf(queuedA, rejectedProbe, publishedA), events)
            assertEquals(1, receiptAwaitCount)
            assertEquals(listOf(ReviewRemoteResult.PUBLISHED), consumedVerdicts)
            assertEquals(listOf(accepted), observedEnqueues)
            runCurrent()
            assertEquals(20, events.size)
            assertEquals(1, receiptAwaitCount)
        } finally {
            caller.cancel()
            probe.cancel()
            backend.cancel()
            collecting.cancel()
            caller.join()
            probe.join()
            backend.join()
            collecting.join()
            // Cleanup never releases the held observer or completes another backend verdict.
        }
    }

    @Test
    fun acceptedDeleteSettlesOnceAfterCallerIsCancelledWhileAwaitingReceipt() = runTest {
        val workId = "cancelled-accepted-delete-work"
        val id = "cancelled-accepted-delete-work_listener-a"
        val seed = ListenerReview("cancelled-accepted-delete-work", "listener-a", "Читач A", 3,
            "Початковий відгук", "Видання E", 100L, null)
        val writes = FakeStore().also { it.seed(seed) }
        val observedEnqueues = mutableListOf<String>()
        val receiptEntered = CompletableDeferred<Unit>()
        val backendMaySettle = CompletableDeferred<Unit>()
        val actualBackendVerdict = CompletableDeferred<Boolean>()
        var receiptAwaitCount = 0
        val consumedVerdicts = mutableListOf<Boolean>()
        val transport = object : ListenerReviewsStore by writes {
            override suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt {
                check(documentId == id)
                observedEnqueues += documentId
                check(writes.documents.remove(documentId) != null)
                return ReviewDeleteReceipt.Queued {
                    receiptAwaitCount++
                    receiptEntered.complete(Unit)
                    actualBackendVerdict.await().also { consumedVerdicts += it }
                }
            }
        }
        val lifecycle = ListenerReviewLifecycle(transport, scope = backgroundScope)
        val observerEntered = CompletableDeferred<Unit>()
        val observerMayResume = CompletableDeferred<Unit>()
        val events = mutableListOf<ReviewDeleteEvent>()
        val collecting = backgroundScope.launch {
            lifecycle.deleteResults.collect { event ->
                events += event
                if (events.size == 1) {
                    observerEntered.complete(Unit)
                    observerMayResume.await()
                }
            }
        }
        val backend = backgroundScope.launch {
            backendMaySettle.await()
            actualBackendVerdict.complete(true)
        }
        val callerResult = CompletableDeferred<ReviewDeleteResult>()
        val caller = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            callerResult.complete(lifecycle.delete(workId, "listener-a"))
        }
        try {
            lifecycle.open(workId, "listener-a")
            lifecycle.refresh(workId)
            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
            assertEquals(seed, ListenerReviewCodec.fromMap(writes.documents.getValue(id)))
            runCurrent() // The real public delete-results subscriber is present before local acceptance.

            caller.start()
            runCurrent()
            assertEquals(listOf(id), observedEnqueues)
            assertFalse(writes.documents.containsKey(id))
            assertEquals(setOf(id), lifecycle.state.value.deleting)
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.visible)
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertTrue(receiptEntered.isCompleted)
            assertEquals(1, receiptAwaitCount)
            assertTrue(consumedVerdicts.isEmpty())
            assertTrue(caller.isActive)
            assertFalse(callerResult.isCompleted)
            assertFalse(actualBackendVerdict.isCompleted)
            assertTrue(observerEntered.isCompleted)
            assertFalse(observerMayResume.isCompleted)
            assertEquals(listOf(ReviewDeleteResult.QUEUED), events.map { it.result })
            val queued = events.single()
            assertEquals(workId, queued.workId)
            assertEquals(id, queued.documentId)

            // Cancel only the actual awaiting caller. The provided owner, observer and backend stay alive.
            caller.cancel()
            caller.join()
            runCurrent()
            assertTrue(caller.isCancelled)
            assertFalse(callerResult.isCompleted)
            assertTrue(backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            assertTrue(collecting.isActive)
            assertTrue(backend.isActive)
            assertFalse(observerMayResume.isCompleted)
            assertFalse(actualBackendVerdict.isCompleted)
            assertEquals(listOf(queued), events)
            assertEquals(setOf(id), lifecycle.state.value.deleting)
            assertFalse(writes.documents.containsKey(id))

            backendMaySettle.complete(Unit)
            runCurrent()
            assertTrue(backend.isCompleted)
            assertTrue(actualBackendVerdict.isCompleted)
            assertTrue(actualBackendVerdict.await())
            assertTrue(backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            assertFalse(observerMayResume.isCompleted)
            assertTrue(collecting.isActive)
            assertEquals(listOf(queued), events)
            // FIRST functional oracle: actual backend success must retire the accepted deleting overlay.
            assertEquals(emptySet<String>(), lifecycle.state.value.deleting)
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.visible)
            assertEquals(emptyMap<String, ListenerReview>(), lifecycle.state.value.pending)
            assertFalse(lifecycle.state.value.hasFailedMutation)
            assertFalse(writes.documents.containsKey(id))
            assertEquals(listOf(id), observedEnqueues)
            assertEquals(1, receiptAwaitCount)
            assertEquals(listOf(true), consumedVerdicts)
            assertTrue(caller.isCancelled)
            assertFalse(callerResult.isCompleted)

            // Only after retirement passes: release the real observer and inspect retained ordered terminal delivery.
            observerMayResume.complete(Unit)
            runCurrent()
            assertEquals(listOf(queued,
                ReviewDeleteEvent(workId, id, queued.generation, ReviewDeleteResult.DELETED)), events)
            assertEquals(1, receiptAwaitCount)
            assertEquals(listOf(true), consumedVerdicts)
            assertEquals(emptySet<String>(), lifecycle.state.value.deleting)
            assertEquals(emptyList<ListenerReview>(), lifecycle.state.value.confirmed)
            assertFalse(writes.documents.containsKey(id))
            runCurrent()
            assertEquals(2, events.size)
            assertEquals(1, receiptAwaitCount)
        } finally {
            caller.cancel()
            backend.cancel()
            collecting.cancel()
            caller.join()
            backend.join()
            collecting.join()
            // Cleanup never releases the observer, accepts another mutation or creates another backend verdict.
        }
    }


}
