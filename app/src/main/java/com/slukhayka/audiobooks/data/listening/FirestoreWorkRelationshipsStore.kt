package com.slukhayka.audiobooks.data.listening

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * #581 W0.3 — the Firestore implementation of [WorkRelationshipsStore] over
 * the `work_relationships` collection: thin Android glue in the exact shape
 * of [FirestoreListenerProgressSyncStore] (ADR-0023 house pattern) —
 * server-stamped writes, SERVER-source read-back of the ordering stamp,
 * fail-closed around every call. Firebase itself is optional: [create]
 * returns null without config, and Work-relationship sync then simply does
 * not exist — degrade-never by contract.
 */
class FirestoreWorkRelationshipsStore(private val firestore: FirebaseFirestore) :
    WorkRelationshipsStore {

    override suspend fun fetchDocument(documentId: String): Map<String, Any>? =
        firestore.collection(WorkRelationshipsCodec.COLLECTION).document(documentId)
            .get().awaitSnapshot()?.data

    override suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean {
        val stamped = fields + mapOf(WorkRelationshipsCodec.FIELD_UPDATED_AT to FieldValue.serverTimestamp())
        return runCatching {
            firestore.collection(WorkRelationshipsCodec.COLLECTION).document(documentId)
                .set(stamped).awaitUnit() != null
        }.getOrDefault(false)
    }

    override suspend fun readServerUpdatedAtMs(documentId: String): Long? {
        val snapshot = firestore.collection(WorkRelationshipsCodec.COLLECTION).document(documentId)
            .get(Source.SERVER) // the ordering stamp only counts when the server vouches for it
            .awaitSnapshot()
            ?: return null
        return snapshot.getLong(WorkRelationshipsCodec.FIELD_UPDATED_AT)
    }

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    private suspend fun Task<com.google.firebase.firestore.DocumentSnapshot>.awaitSnapshot():
        com.google.firebase.firestore.DocumentSnapshot? = awaitTask()

    private suspend fun Task<Void>.awaitUnit(): Void? = awaitTask()

    companion object {
        /** The default Firebase app's Firestore, or null when Firebase is not configured. */
        fun create(context: Context): FirestoreWorkRelationshipsStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreWorkRelationshipsStore(FirebaseFirestore.getInstance(app))
        }
    }
}
