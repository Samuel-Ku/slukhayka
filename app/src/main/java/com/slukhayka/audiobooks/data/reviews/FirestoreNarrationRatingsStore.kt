package com.slukhayka.audiobooks.data.reviews

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ADR-0023 (#348) — the Firestore implementation of [NarrationRatingsStore]:
 * narration ratings in the `edition_ratings` collection, document per
 * `${workId}_${uid}_${editionId}`, queried per Work (`workId ==`, ordered by
 * `createdAt` desc with a plain fallback when no composite index exists —
 * the seam sorts client-side anyway).
 *
 * Thin Android glue (no unit tests here); policy and shape are pinned by the
 * JVM fixture tests over the seam. Firebase optional: [create] returns null
 * without configuration, and the rating UI simply does not exist.
 */
class FirestoreNarrationRatingsStore(private val firestore: FirebaseFirestore) : NarrationRatingsStore {

    override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> {
        // Ordered query needs a (workId ASC, createdAt DESC) composite index;
        // until it exists (or on any transport hiccup) the plain equality
        // query serves — decode() enforces newest-first.
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

    override suspend fun setDocument(documentId: String, document: Map<String, Any>): Boolean =
        runCatching {
            firestore.collection(COLLECTION).document(documentId).set(document).awaitUnit()
            true
        }.getOrDefault(false)

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

    /**
     * Resumes the unit OR THROWS the task's failure — the callers' fail-closed
     * wrappers must see an exception to honestly report false (#348 review
     * finding: resuming null made a failing write read as success).
     */
    private suspend fun Task<Void>.awaitUnit(): Void? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        // Play Services tasks are not cancellable mid-flight; nothing to hook.
    }

    companion object {
        /** ADR-0023 — the narration-ratings collection. */
        private const val COLLECTION = "edition_ratings"
        private const val FIELD_WORK_ID = "workId"
        private const val FIELD_CREATED_AT = "createdAt"

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured ([FirebaseApp.initializeApp] then returns null).
         */
        fun create(context: Context): FirestoreNarrationRatingsStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreNarrationRatingsStore(FirebaseFirestore.getInstance(app))
        }
    }
}
