package com.slukhayka.audiobooks.data.reviews

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlin.coroutines.resume
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

    override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> {
        // The ordered query needs a (workId ASC, createdAt DESC) composite
        // index; until it exists (or on any other transport hiccup) the
        // plain equality query serves — decode() enforces newest-first.
        val ordered = runCatching {
            firestore.collection(COLLECTION)
                .whereEqualTo(FIELD_WORK_ID, workId)
                .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
                .get()
                .awaitDocuments()
        }.getOrNull()
        if (ordered != null) return ordered
        return firestore.collection(COLLECTION)
            .whereEqualTo(FIELD_WORK_ID, workId)
            .get()
            .awaitDocuments()
            ?: emptyList()
    }

    override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        for (chunk in workIds.chunked(MAX_WHERE_IN)) {
            result += firestore.collection(COLLECTION)
                .whereIn(FIELD_WORK_ID, chunk)
                .get()
                .awaitDocuments()
                .orEmpty()
        }
        return result
    }

    override suspend fun setDocument(documentId: String, document: Map<String, Any>): Boolean {
        // set() on a document key is idempotent — an edit replaces, never
        // duplicates. With persistence enabled the task completes once the
        // write is durably committed LOCALLY, so an offline review returns
        // true here and rides the persistence queue to the server (#280).
        return runCatching {
            firestore.collection(COLLECTION).document(documentId).set(document).awaitUnit()
            true
        }.getOrDefault(false)
    }

    override suspend fun removeDocument(documentId: String): Boolean = runCatching {
        firestore.collection(COLLECTION).document(documentId).delete().awaitUnit()
        true
    }.getOrDefault(false)

    /** Bridges the Play Services [Task] onto a coroutine: documents or null on failure. */
    private suspend fun Task<com.google.firebase.firestore.QuerySnapshot>.awaitDocuments(): List<Map<String, Any>>? =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { snapshot -> cont.resume(snapshot.documents.mapNotNull { it.data }) }
            addOnFailureListener { cont.resume(null) }
        }

    private suspend fun Task<Void>.awaitUnit(): Void? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

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
            return FirestoreListenerReviewsStore(FirebaseFirestore.getInstance(app))
        }
    }
}
