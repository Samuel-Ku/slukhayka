package com.slukhayka.audiobooks.acceptance

import android.app.Application
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.slukhayka.audiobooks.data.reviews.CombinedAverage
import com.slukhayka.audiobooks.data.reviews.FirestoreListenerReviewsStore
import com.slukhayka.audiobooks.data.reviews.ListenerReview
import com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec
import com.slukhayka.audiobooks.data.reviews.ReviewSaveEvent
import com.slukhayka.audiobooks.data.reviews.ListenerReviewLifecycle
import com.slukhayka.audiobooks.data.reviews.ReviewSaveResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run through review-persistence.py: separate instrumentation processes, never a fake store. */
class ListenerReviewPersistenceTest {
    @Test
    fun reviewSurvivesARealProcessRestartWithoutBecomingConfirmedOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.applicationContext.javaClass == Application::class.java) {
            "Build with -PacceptancePersistence=true: production App must never start"
        }
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString("acceptanceProject") == PROJECT)
        val run = requireNotNull(arguments.getString("acceptanceRun"))
        require(run.matches(Regex("[a-z0-9-]{1,60}")))
        val phase = requireNotNull(arguments.getString("acceptancePhase"))
        val automatic = arguments.getString("acceptanceReconcile") == "automatic"
        val mutation = arguments.getString("acceptanceMutation") ?: "creation"
        require(mutation == "creation" || mutation == "edit")
        val edit = mutation == "edit"
        val rejected = arguments.getString("acceptanceCase") == "rejection"
        val editRejected = edit && rejected
        require(!editRejected || automatic) { "EDIT rejection requires automatic reconciliation" }
        val uid = if (editRejected) "qa-rejected-edit" else if (edit) "qa-accepted-edit" else if (rejected) "qa-rejected" else "qa-accepted"
        val workId = if (edit) "qa-$run-edit-${if (rejected) "rejection" else "ack"}" else "qa-$run-${if (rejected) "rejection" else "ack"}"
        val preferences = context.getSharedPreferences("acceptance-$run", 0)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("acceptanceEvidence", "phase=$phase case=$uid pid=${Process.myPid()} queuePid=${preferences.getInt("queue-$uid", -1)}")
        })
        val options = FirebaseOptions.Builder().setProjectId(PROJECT)
            .setApplicationId("1:1234567890:android:acceptance")
            .setApiKey("acceptance-demo-key").build()
        val app = FirebaseApp.initializeApp(context, options, "acceptance-$run")
        val firestore = FirebaseFirestore.getInstance(app)
        firestore.useEmulator("10.0.2.2", 8089)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lifecycle = ListenerReviewLifecycle(FirestoreListenerReviewsStore(firestore),
            now = { if (edit) 200L else System.currentTimeMillis() }, scope = scope)
        lifecycle.open(workId, uid)
        val seed = ListenerReview(workId, if (edit) uid else "qa-seed", "Тестовий читач", 3,
            body = if (edit) "Початковий відгук" else null,
            editionTag = if (edit) "Тестове видання" else null, createdAt = 100L)
        // Independent EDIT oracle: all fields are fixed before any public/cache read.
        val expectedEdit = if (edit) seed.copy(rating = 5, body = "Переживає restart", editedAt = 200L) else null
        val documentId = ListenerReviewCodec.documentId(workId, uid)
        try {
            when (phase) {
                "seed" -> {
                    val receipt = FirestoreListenerReviewsStore(firestore).enqueueReview(seed)
                    require(receipt is com.slukhayka.audiobooks.data.reviews.ReviewWriteReceipt.Queued)
                    assertEquals(com.slukhayka.audiobooks.data.reviews.ReviewRemoteResult.PUBLISHED, receipt.awaitRemote())
                    // The rejection fixture permits only this original CREATE; future EDIT is UPDATE.
                    if (editRejected) firestore.waitForPendingWrites().finish()
                    val authoritativeSeed = firestore.collection("book_reviews")
                        .document(ListenerReviewCodec.documentId(workId, seed.uid)).get(Source.SERVER).finish()
                    assertTrue(authoritativeSeed.exists())
                    assertFalse(authoritativeSeed.metadata.isFromCache)
                    assertFalse(authoritativeSeed.metadata.hasPendingWrites())
                    assertEquals(ListenerReviewCodec.documentId(workId, seed.uid), authoritativeSeed.id)
                    assertEquals(seed, requireNotNull(authoritativeSeed.data?.let(ListenerReviewCodec::fromMap)))
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("acceptanceSeedPayload", JSONObject(requireNotNull(authoritativeSeed.data))
                            .put("documentId", authoritativeSeed.id).toString())
                    })
                    lifecycle.refresh(workId)
                    assertEquals(listOf(seed), lifecycle.state.value.confirmed)
                    assertTrue(preferences.edit().putInt("seed-$uid", Process.myPid()).commit())
                }
                "queue" -> {
                    if (editRejected) {
                        val seedPid = preferences.getInt("seed-$uid", -1)
                        assertTrue("The own seed must have completed in an earlier process", seedPid > 0)
                        assertFalse("Queue must not reuse the seed process", seedPid == Process.myPid())
                    }
                    firestore.disableNetwork().finish()
                    lifecycle.refresh(workId)
                    assertEquals(listOf(3), lifecycle.state.value.confirmed.map { it.rating })
                    assertEquals(ReviewSaveResult.QUEUED, lifecycle.enqueueSave(workId, uid, "Тестовий читач", 5, "Переживає restart", seed.editionTag, if (edit) seed else null))
                    assertEquals(listOf(5), lifecycle.state.value.pending.values.map { it.rating })
                    assertEquals(listOf(3), lifecycle.state.value.confirmed.map { it.rating })
                    // Public SDK cache read is a persistence barrier and independent queue evidence.
                    val snapshot = firestore.collection("book_reviews").whereEqualTo("workId", workId)
                        .get(Source.CACHE).finish()
                    assertTrue(snapshot.metadata.hasPendingWrites())
                    assertEquals(if (edit) 1 else 2, snapshot.size())
                    val queuedDocument = snapshot.documents.single { it.id == documentId }
                    assertTrue(queuedDocument.metadata.hasPendingWrites())
                    assertEquals(lifecycle.state.value.pending.getValue(documentId),
                        requireNotNull(queuedDocument.data?.let(ListenerReviewCodec::fromMap)))
                    if (edit) {
                        assertEquals("Complete deterministic EDIT payload before death", expectedEdit,
                            lifecycle.state.value.pending.getValue(documentId))
                        assertEquals(listOf(seed), lifecycle.state.value.confirmed)
                        assertEquals(3.0, CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating })!!.value, 0.0)
                        assertEquals(100L, lifecycle.state.value.pending.getValue(documentId).createdAt)
                        assertEquals(200L, requireNotNull(lifecycle.state.value.pending.getValue(documentId).editedAt))
                    }
                    assertTrue(preferences.edit().putInt("queue-$uid", Process.myPid()).commit())
                }
                "restart-offline" -> {
                    if (editRejected) assertTrue("A recorded queue process is required", preferences.getInt("queue-$uid", -1) > 0)
                    firestore.disableNetwork().finish()
                    assertFalse("A fresh process is required", preferences.getInt("queue-$uid", -1) == Process.myPid())
                    val snapshot = firestore.collection("book_reviews").whereEqualTo("workId", workId)
                        .get(Source.CACHE).finish()
                    assertTrue("SDK queue must survive process death", snapshot.metadata.hasPendingWrites())
                    assertEquals(if (edit) 1 else 2, snapshot.size())
                    val restoredDocument = snapshot.documents.single { it.id == documentId }
                    assertTrue(restoredDocument.metadata.hasPendingWrites())
                    val sdkPending = requireNotNull(restoredDocument.data?.let(ListenerReviewCodec::fromMap))
                    assertEquals(workId, sdkPending.workId)
                    assertEquals(uid, sdkPending.uid)
                    assertEquals(5, sdkPending.rating)
                    if (edit) {
                        assertEquals("Complete deterministic EDIT payload after death", expectedEdit, sdkPending)
                        assertEquals(100L, sdkPending.createdAt)
                        assertEquals(200L, requireNotNull(sdkPending.editedAt))
                    }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("acceptancePendingPayload", JSONObject(ListenerReviewCodec.toMap(sdkPending))
                            .put("documentId", documentId).toString())
                    })
                    lifecycle.refresh(workId)
                    assertEquals("Offline queued rating must not become a confirmed vote", listOf(3), lifecycle.state.value.confirmed.map { it.rating })
                    assertEquals("Pending card survives restart", listOf(5), lifecycle.state.value.pending.values.map { it.rating })
                    assertEquals(sdkPending, lifecycle.state.value.pending.getValue(documentId))
                    if (edit) assertEquals(listOf(seed), lifecycle.state.value.confirmed)
                    assertEquals(3.0, CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating })!!.value, 0.0)
                }
                "reconnect" -> {
                    if (editRejected) {
                        val queuePid = preferences.getInt("queue-$uid", -1)
                        assertTrue("The queued EDIT must have a recorded process", queuePid > 0)
                        assertFalse("Reconnect must not reuse the queued EDIT process", queuePid == Process.myPid())
                    }
                    firestore.disableNetwork().finish()
                    lifecycle.refresh(workId)
                    assertEquals(listOf(3), lifecycle.state.value.confirmed.map { it.rating })
                    val restored = lifecycle.state.value.pending.getValue(documentId)
                    assertEquals(workId, restored.workId)
                    assertEquals(uid, restored.uid)
                    assertEquals(5, restored.rating)
                    assertEquals("Переживає restart", restored.body)
                    if (edit) assertEquals("Complete deterministic EDIT payload before reconnect", expectedEdit, restored)
                    val terminalEvents = mutableListOf<ReviewSaveEvent>()
                    val firstTerminal = CompletableDeferred<ReviewSaveEvent>()
                    // UNDISPATCHED installs the complete scoped collector before enabling network.
                    val collecting = if (automatic) launch(start = CoroutineStart.UNDISPATCHED) {
                        lifecycle.results.collect { event ->
                            if (event.workId == workId && event.documentId == documentId &&
                                (event.result == ReviewSaveResult.PUBLISHED || event.result == ReviewSaveResult.FAILED)) {
                                terminalEvents += event
                                firstTerminal.complete(event)
                            }
                        }
                    } else null
                    val settled = if (automatic) async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(20_000L) {
                            lifecycle.state.first { it.workId == workId && it.uid == uid && it.pending.isEmpty() }
                        }
                    } else null
                    try {
                        firestore.enableNetwork().finish()
                        firestore.waitForPendingWrites().finish()
                        val server = firestore.collection("book_reviews").whereEqualTo("workId", workId)
                            .get(Source.SERVER).finish()
                        assertFalse(server.metadata.isFromCache)
                        assertFalse(server.metadata.hasPendingWrites())
                        val actual = server.documents.associate { doc ->
                            assertFalse(doc.metadata.isFromCache)
                            assertFalse(doc.metadata.hasPendingWrites())
                            doc.id to requireNotNull(doc.data?.let(ListenerReviewCodec::fromMap))
                        }
                        val expected = (if (rejected) listOf(seed) else if (edit) listOf(requireNotNull(expectedEdit)) else listOf(seed, restored))
                            .associateBy { ListenerReviewCodec.documentId(it.workId, it.uid) }
                        assertEquals("Complete authoritative payload and document identity", expected, actual)
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("acceptanceEvidence", "SERVER phase=$phase mutation=$mutation reconcile=${if (automatic) "automatic" else "manual"} fromCache=false hasPendingWrites=false")
                            putString("acceptanceServerPayload", server.documents.joinToString(prefix = "[", postfix = "]") {
                                JSONObject(requireNotNull(it.data)).put("documentId", it.id).toString()
                            })
                        })
                        if (automatic) {
                            val event = withTimeout(20_000L) { firstTerminal.await() }
                            assertEquals(if (rejected) ReviewSaveResult.FAILED else ReviewSaveResult.PUBLISHED, event.result)
                            if (editRejected) {
                                assertEquals("FAILED belongs to the restarted EDIT Work", workId, event.workId)
                                assertEquals("FAILED belongs to the exact seeded document", documentId, event.documentId)
                                assertTrue("A real recovered submission has an observed generation", event.generation > 0L)
                            }
                            assertTrue(requireNotNull(settled).await().pending.isEmpty())
                            // Bounded observation only; no UI refresh or production timer.
                            delay(1_000L)
                            requireNotNull(collecting).cancelAndJoin()
                            assertEquals("Exactly one terminal event in the recorded window", 1, terminalEvents.size)
                            instrumentation.sendStatus(0, Bundle().apply {
                                putString("acceptanceEvidence", "TERMINAL count=${terminalEvents.size} result=${event.result} documentId=$documentId observationMs=1000")
                                if (editRejected) putString("acceptanceTerminalEvent", JSONObject()
                                    .put("workId", event.workId).put("documentId", event.documentId)
                                    .put("generation", event.generation).put("result", event.result.name).toString())
                            })
                        } else {
                            lifecycle.refresh(workId)
                        }
                        if (rejected) {
                            assertEquals(listOf(seed), lifecycle.state.value.confirmed)
                            assertEquals("Exact restored retry draft", restored, lifecycle.state.value.failedSave[documentId])
                            assertTrue(lifecycle.state.value.failedDelete.isEmpty())
                            assertFalse(lifecycle.state.value.readFailed)
                            if (editRejected) {
                                assertEquals("Only the full exact EDIT remains retryable",
                                    mapOf(documentId to requireNotNull(expectedEdit)), lifecycle.state.value.failedSave)
                                assertEquals("Backend rejection restores the original full voice", listOf(seed), lifecycle.state.value.visible)
                                assertTrue("Rejected EDIT creates no deleting intent", lifecycle.state.value.deleting.isEmpty())
                            }
                            instrumentation.sendStatus(0, Bundle().apply {
                                putString("acceptanceFailedPayload", JSONObject(ListenerReviewCodec.toMap(
                                    lifecycle.state.value.failedSave.getValue(documentId))).put("documentId", documentId).toString())
                            })
                        } else {
                            assertEquals(expected.values.toSet(), lifecycle.state.value.confirmed.toSet())
                        }
                        assertTrue(lifecycle.state.value.pending.isEmpty())
                        assertEquals(if (rejected) 3.0 else if (edit) 5.0 else 4.0,
                            CombinedAverage.average(emptyList(), lifecycle.state.value.confirmed.map { it.rating })!!.value, 0.0)
                    } finally {
                        collecting?.cancel()
                        settled?.cancel()
                    }
                }
                else -> error("Unknown phase")
            }
            Log.i("ReviewPersistence", "PASS case=$uid phase=$phase pid=${Process.myPid()}")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("acceptanceEvidence", "PASS phase=$phase case=$uid pid=${Process.myPid()}")
            })
        } finally {
            scope.cancel()
            if (phase == "seed" || phase == "reconnect") {
                firestore.terminate().finish()
                app.delete()
            }
        }
    }

    private fun <T> Task<T>.finish(): T = com.google.android.gms.tasks.Tasks.await(this, 40, TimeUnit.SECONDS)

    private companion object { const val PROJECT = "demo-slukhayka-acceptance" }
}
