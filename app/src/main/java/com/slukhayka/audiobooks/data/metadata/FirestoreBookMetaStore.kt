package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-30 T2 (#217) — the Firestore implementation of [SharedBookMetaStore]:
 * reads the shared duration of one Edition from the `book_durations`
 * collection, document per editionId. Thin Android glue (like the transport
 * adapters — no unit tests here); the read-path logic and the document shape
 * are pinned by fixture tests over the seam and by [SharedDurationCodec]
 * tests.
 *
 * Degrade-never-throw: a missing document, a network failure, a timeout or an
 * unreadable document all yield null / an empty map — the caller falls
 * through to the local database silently. The batch read is chunked into
 * Firestore's `whereIn` bound (10 ids per query) so a screen of visible books
 * costs a handful of reads, never one per book; a failing chunk contributes
 * nothing. Firebase itself is optional: [create] returns null when the app
 * has no Firebase configuration (no `google-services.json` keys), so the
 * shared layer simply does not exist and the app behaves exactly as before.
 */
class FirestoreBookMetaStore(private val firestore: FirebaseFirestore) : SharedBookMetaStore {

    override suspend fun getDuration(editionId: String): Long? {
        return try {
            val snapshot = firestore.collection(COLLECTION).document(editionId).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else SharedDurationCodec.fromMap(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getDurations(editionIds: List<String>): Map<String, Long> {
        val ids = editionIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (chunk in ids.chunked(MAX_WHERE_IN)) {
            try {
                val snapshots = firestore.collection(COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .awaitOrNull() ?: continue
                for (doc in snapshots.documents) {
                    val decoded = SharedDurationCodec.fromMap(doc.data ?: continue) ?: continue
                    result[doc.id] = decoded
                }
            } catch (e: Exception) {
                // Degrade-never: a failing chunk contributes nothing.
            }
        }
        return result
    }

    override suspend fun putDuration(
        editionId: String,
        durationSeconds: Long,
        provenance: DurationProvenance
    ) {
        // Best-effort fire-and-forget: the write proceeds in the background; a
        // synchronous failure (a closed Firestore, a bad document) is caught
        // here and the caller never sees it. set() on a document key is
        // idempotent — a re-write replaces, never duplicates.
        runCatching {
            firestore.collection(COLLECTION).document(editionId)
                .set(SharedDurationCodec.toMap(durationSeconds, provenance))
        }
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "book_durations"

        /** Firestore's `whereIn` value bound — the batch chunk size. */
        private const val MAX_WHERE_IN = 10

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json — [FirebaseApp.initializeApp]
         * then returns null instead of throwing).
         */
        fun create(context: Context): FirestoreBookMetaStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreBookMetaStore(FirebaseFirestore.getInstance(app))
        }
    }
}
