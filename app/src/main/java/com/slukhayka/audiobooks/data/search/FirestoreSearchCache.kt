package com.slukhayka.audiobooks.data.search

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-33 T1 (#229) — the Firestore implementation of [SearchCache]: reads
 * the merged search result of one query from the `search_results` collection,
 * document per normalized query key. Thin Android glue (like the transport
 * adapters — no unit tests here); the read-path policy (normalization,
 * freshness, no-negative-cache) and the document shape are pinned by the
 * fixture tests over the seam ([SearchCacheTest]) and by [SearchResultCodec]
 * tests.
 *
 * Degrade-never-throw: a missing document, a network failure, a timeout or
 * an unreadable document all yield null — the caller falls through to the
 * live sources silently. Firebase itself is optional: [create] returns null
 * when the app has no Firebase configuration (no `google-services.json`
 * keys), so the shared layer simply does not exist and search behaves
 * exactly as before.
 */
class FirestoreSearchCache(private val firestore: FirebaseFirestore) : SearchCache {

    override suspend fun readDocument(queryKey: String): Map<String, Any>? {
        return try {
            val snapshot = firestore.collection(COLLECTION).document(queryKey).get()
                .awaitOrNull() ?: return null
            if (snapshot.exists()) snapshot.data else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun writeDocument(queryKey: String, document: Map<String, Any>) {
        // Best-effort fire-and-forget: the write proceeds in the background; a
        // synchronous failure (a closed Firestore, a bad document) is caught
        // here and the caller never sees it. set() on a document key is
        // idempotent — a re-write replaces, never duplicates.
        runCatching {
            firestore.collection(COLLECTION).document(queryKey).set(document)
        }
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "search_results"

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json — [FirebaseApp.initializeApp]
         * then returns null instead of throwing).
         */
        fun create(context: Context): FirestoreSearchCache? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreSearchCache(FirebaseFirestore.getInstance(app))
        }
    }
}