package com.slukhayka.audiobooks.data.listening

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ADR-0023 (spec-43 T6) — the Firestore implementation of
 * [ListenerProgressSyncStore] over the `listening_state` collection:
 * `{uid}_{editionId}` documents whose `updatedAt` is written by the SERVER.
 *
 * Thin Android glue (like every Firestore store — no unit tests here); the
 * policy and the document shape are pinned by the JVM fixture tests over the
 * seam. Firebase itself is optional: [create] returns null without config,
 * and Progress Sync then simply does not exist — degrade-never by contract.
 */
class FirestoreListenerProgressSyncStore(private val firestore: FirebaseFirestore) :
    ListenerProgressSyncStore {

    override suspend fun fetchDocument(documentId: String): Map<String, Any>? =
        firestore.collection(COLLECTION).document(documentId).get().awaitSnapshot()?.data

    override suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean {
        val stamped = fields + mapOf(FIELD_UPDATED_AT to FieldValue.serverTimestamp())
        return runCatching {
            firestore.collection(COLLECTION).document(documentId).set(stamped).awaitUnit() != null
        }.getOrDefault(false)
    }

    override suspend fun readServerUpdatedAtMs(documentId: String): Long? {
        val snapshot = firestore.collection(COLLECTION).document(documentId)
            .get(Source.SERVER) // the ordering stamp only counts when the server vouches for it
            .awaitSnapshot()
            ?: return null
        return snapshot.getLong(FIELD_UPDATED_AT)
    }

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    private suspend fun Task<com.google.firebase.firestore.DocumentSnapshot>.awaitSnapshot():
        com.google.firebase.firestore.DocumentSnapshot? = awaitTask()

    private suspend fun Task<Void>.awaitUnit(): Void? = awaitTask()

    companion object {
        /** ADR-0023 (spec-43 T6) — the Progress Sync collection. */
        private const val COLLECTION = "listening_state"
        private const val FIELD_UPDATED_AT = ProgressSyncCodec.FIELD_UPDATED_AT

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json).
         */
        fun create(context: Context): FirestoreListenerProgressSyncStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreListenerProgressSyncStore(FirebaseFirestore.getInstance(app))
        }
    }
}
