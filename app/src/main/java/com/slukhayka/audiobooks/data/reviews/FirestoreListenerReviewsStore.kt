package com.slukhayka.audiobooks.data.reviews

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.QuerySnapshot
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-40 #277 — the Firestore implementation of [ListenerReviewsStore]:
 * listener reviews in the `book_reviews` collection, document per
 * `${workId}_${uid}`, queried per Work (`workId ==` ordered by `createdAt`
 * desc — with a plain fallback when the compound query has no composite
 * index yet, since the seam sorts client-side anyway) and batched across
 * Works through `whereIn` chunks of 10.
 *
 * Thin Android glue (like the transport adapters — no unit tests here); the
 * read/write policy and the document shape are pinned by the JVM fixture
 * tests over the seam. Firebase itself is optional: [create] returns null
 * when the app has no configuration (no `google-services.json` keys), so
 * the reviews layer simply does not exist and the book page shows no block.
 */
class FirestoreListenerReviewsStore(private val firestore: FirebaseFirestore) : ListenerReviewsStore {
    private val snapshots = createSnapshots()

    private fun createSnapshots(): ConfirmedReviewSnapshots {
        // Named database instances must never share the supported default database's confirmed disk.
        require(firestore === FirebaseFirestore.getInstance(firestore.app))
        val app = firestore.app
        val context = app.applicationContext
        val namespace = ReviewSnapshotNamespace(
            projectId = requireNotNull(app.options.projectId),
            firebaseAppName = app.name,
            applicationId = context.packageName
        )
        return ConfirmedReviewSnapshots(namespace, AndroidAtomicReviewSnapshotStorage(context, namespace))
    }

    override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> =
        queryWorkDocumentsOrNull(workId).orEmpty()

    /**
     * Spec-620 (#623) — the read keeps a transport failure apart from a real
     * empty: the ordered query falling back to the plain one is normal (no
     * composite index yet), but a FAILING plain query is null, so the screen
     * can keep its last confirmed snapshot instead of showing an empty
     * community.
     */
    override suspend fun queryWorkDocumentsOrNull(workId: String): List<Map<String, Any>>? {
        val result = readSnapshot(workId, "", Source.DEFAULT)
        return (result as? ReviewReadResult.Snapshot)?.confirmed?.map(ListenerReviewCodec::toMap)
    }

    override suspend fun readReviews(workId: String, uid: String): ReviewReadResult {
        return readSnapshot(workId, uid, Source.DEFAULT)
    }

    override suspend fun awaitPendingWrites(): Boolean = try {
        firestore.waitForPendingWrites().awaitReviewWriteResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    override suspend fun readServerReviews(workId: String, uid: String): ReviewReadResult {
        val drain = snapshots.beginDrain()
        if (!awaitPendingWrites()) return ReviewReadResult.Failure
        val token = snapshots.beginServerRead(workId, drain) ?: return ReviewReadResult.Failure
        return readSnapshot(workId, uid, Source.SERVER, token)
    }

    private suspend fun readSnapshot(
        workId: String, uid: String, source: Source, eligibleServerToken: ReviewSnapshotReadToken? = null
    ): ReviewReadResult {
        val token = eligibleServerToken ?: if (source == Source.SERVER) return ReviewReadResult.Failure else snapshots.beginRead(workId)
        val snapshot = queryWorkSnapshot(workId, source)
            ?: return if (source == Source.SERVER) ReviewReadResult.Failure else snapshots.lastGood(workId)
        return snapshots.project(token, uid, snapshot.toFrame(
            if (source == Source.SERVER) ReviewSnapshotOrigin.POST_DRAIN_SERVER else ReviewSnapshotOrigin.DEFAULT_OR_CACHE
        ))
    }

    private fun QuerySnapshot.toFrame(origin: ReviewSnapshotOrigin): ReviewSnapshotFrame = ReviewSnapshotFrame(
        documents = documents.map { document ->
            ReviewSnapshotDocument(document.id, document.data?.let(ListenerReviewCodec::fromMap), document.metadata.hasPendingWrites())
        },
        fromCache = metadata.isFromCache,
        queryHasPendingWrites = metadata.hasPendingWrites(),
        origin = origin
    )

    private suspend fun queryWorkSnapshot(workId: String, source: Source = Source.DEFAULT): QuerySnapshot? {
        val collection = firestore.collection(COLLECTION).whereEqualTo(FIELD_WORK_ID, workId)
        // An unavailable composite index falls back to equality; both reads retain metadata.
        return collection.orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING).get(source).awaitSnapshot()
            ?: collection.get(source).awaitSnapshot()
    }

    private suspend fun Task<QuerySnapshot>.awaitSnapshot(): QuerySnapshot? =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
        }

    override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        for (chunk in workIds.filter { it.isNotBlank() }.distinct().chunked(MAX_WHERE_IN)) {
            val tokens = chunk.associateWith(snapshots::beginRead)
            val snapshot = firestore.collection(COLLECTION).whereIn(FIELD_WORK_ID, chunk)
                .get(Source.DEFAULT).awaitSnapshot()
            for (workId in chunk) {
                val projected = if (snapshot == null) snapshots.lastGood(workId) else snapshots.project(
                    tokens.getValue(workId), "", ReviewSnapshotFrame(
                        documents = snapshot.documents.filter { it.data?.get(FIELD_WORK_ID) == workId }.map { document ->
                            ReviewSnapshotDocument(document.id, document.data?.let(ListenerReviewCodec::fromMap), document.metadata.hasPendingWrites())
                        },
                        fromCache = snapshot.metadata.isFromCache,
                        queryHasPendingWrites = snapshot.metadata.hasPendingWrites()
                    )
                )
                // Rejected tokens never reapply stale SDK rows; only the current durable truth may fill this batch.
                val available = if (projected == ReviewReadResult.Failure) snapshots.lastGood(workId) else projected
                if (available is ReviewReadResult.Snapshot) result += available.confirmed.map(ListenerReviewCodec::toMap)
            }
        }
        return result
    }

    override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt {
        val review = ListenerReviewCodec.fromMap(document) ?: return ReviewWriteReceipt.Rejected
        if (!ListenerReviewLimits.isWritable(review) || documentId != ListenerReviewCodec.documentId(review.workId, review.uid)) {
            return ReviewWriteReceipt.Rejected
        }
        return try {
            snapshots.enqueueMutation(documentId) { token ->
                val ack = snapshots.saveAcknowledgement(token, review)
                firestore.collection(COLLECTION).document(documentId).set(document).signalReviewAcknowledgement(ack::backendSettled)
                ack.receipt
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { ReviewWriteReceipt.Rejected }
    }

    override suspend fun removeDocument(documentId: String): Boolean = when (val receipt = enqueueDelete(documentId)) {
        ReviewDeleteReceipt.Rejected -> false
        is ReviewDeleteReceipt.Queued -> receipt.awaitRemote()
    }

    override suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt = try {
        snapshots.enqueueMutation(documentId) { token ->
            val ack = snapshots.deleteAcknowledgement(token)
            firestore.collection(COLLECTION).document(documentId).delete().signalReviewAcknowledgement(ack::backendSettled)
            ack.receipt
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { ReviewDeleteReceipt.Rejected }

    companion object {
        /** Spec-40 #277 — the listener-reviews collection. */
        private const val COLLECTION = "book_reviews"
        private const val FIELD_WORK_ID = "workId"
        private const val FIELD_CREATED_AT = "createdAt"

        /** Firestore's `whereIn` value bound — the batch chunk size. */
        private const val MAX_WHERE_IN = 10

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json — [FirebaseApp.initializeApp]
         * then returns null instead of throwing).
         */
        fun create(context: Context): FirestoreListenerReviewsStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return runCatching { FirestoreListenerReviewsStore(FirebaseFirestore.getInstance(app)) }.getOrNull()
        }
    }
}

/** Attach every backend outcome BEFORE returning local acceptance; caller cancellation cannot detach the producer. */
private fun Task<*>.signalReviewAcknowledgement(settle: (Boolean) -> Unit) {
    addOnSuccessListener(reviewWriteTaskExecutor) { settle(true) }
    addOnFailureListener(reviewWriteTaskExecutor) { settle(false) }
    addOnCanceledListener(reviewWriteTaskExecutor) { settle(false) }
}

/** Local enqueue is immediate; callers decide separately when to await the backend. */
internal fun Task<*>.toReviewWriteReceipt(): ReviewWriteReceipt =
    ReviewWriteReceipt.Queued {
        if (awaitReviewWriteResult()) {
            ReviewRemoteResult.PUBLISHED
        } else {
            ReviewRemoteResult.FAILED
        }
    }

/** The write Task's result without turning failure or cancellation into success. */
internal suspend fun Task<*>.awaitReviewWriteResult(): Boolean =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener(reviewWriteTaskExecutor) {
            if (continuation.isActive) continuation.resume(true)
        }
        addOnFailureListener(reviewWriteTaskExecutor) {
            if (continuation.isActive) continuation.resume(false)
        }
        addOnCanceledListener(reviewWriteTaskExecutor) {
            if (continuation.isActive) {
                // Firebase cancelled its own Task: that is a remote failure,
                // not cancellation of the caller's coroutine.
                continuation.resume(false)
            }
        }
    }

private val reviewWriteTaskExecutor = Executor { command -> command.run() }
