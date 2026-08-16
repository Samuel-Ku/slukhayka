package com.slukhayka.audiobooks.data.universe

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-26 T5 — the Firestore implementation of [SharedUniverseStore]: reads
 * the shared resolution of one work from the `universe_resolutions`
 * collection, document per workId. Thin Android glue (like the transport
 * adapters — no unit tests here); the read-path logic and the document
 * shape are pinned by fixture tests over the seam and by
 * [SharedResolutionCodec] tests.
 *
 * Degrade-never-throw: a missing document, a network failure, a timeout or
 * an unreadable document all yield null — the caller falls through to
 * Wikidata silently. Firebase itself is optional: [create] returns null when
 * the app has no Firebase configuration (no `google-services.json` keys), so
 * the shared layer simply does not exist and the app behaves exactly as
 * before the Firestore work (spec-26 T5 AC1).
 */
class FirestoreUniverseStore(private val firestore: FirebaseFirestore) : SharedUniverseStore {

    override suspend fun getResolution(workId: String): UniverseResolution? {
        return try {
            val snapshot = firestore.collection(COLLECTION).document(workId).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else SharedResolutionCodec.fromMap(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun putResolution(
        workId: String,
        resolution: UniverseResolution,
        provenance: ResolutionProvenance
    ) {
        // Best-effort fire-and-forget (spec-26 T6 AC5): the write proceeds in
        // the background; a synchronous failure (a closed Firestore, a bad
        // document) is caught here and the caller never sees it. set() on a
        // document key is idempotent — a re-write replaces, never duplicates.
        runCatching {
            firestore.collection(COLLECTION).document(workId)
                .set(SharedResolutionCodec.toMapWithProvenance(resolution, provenance))
        }
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "universe_resolutions"

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json — [FirebaseApp.initializeApp]
         * then returns null instead of throwing).
         */
        fun create(context: Context): FirestoreUniverseStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreUniverseStore(FirebaseFirestore.getInstance(app))
        }
    }
}
