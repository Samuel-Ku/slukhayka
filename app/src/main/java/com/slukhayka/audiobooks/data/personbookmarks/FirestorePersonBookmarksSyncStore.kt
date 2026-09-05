package com.slukhayka.audiobooks.data.personbookmarks

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Thin #404 transport; policy and LWW decisions stay outside Firebase. */
class FirestorePersonBookmarksSyncStore(private val firestore: FirebaseFirestore) : PersonBookmarksSyncStore {
    override suspend fun fetch(uid: String): List<Map<String, Any>> =
        firestore.collection(PersonBookmarksSyncCodec.COLLECTION).whereEqualTo("uid", uid)
            .get().await()?.documents?.mapNotNull { document ->
                document.data?.mapValues { (_, value) ->
                    (value as? Timestamp)?.toDate()?.time ?: value
                }
            } ?: emptyList()

    override suspend fun write(documentId: String, fields: Map<String, Any>): Boolean = runCatching {
        firestore.collection(PersonBookmarksSyncCodec.COLLECTION).document(documentId)
            .set(fields + ("updatedAt" to FieldValue.serverTimestamp())).await() != null
    }.getOrDefault(false)

    override suspend fun delete(documentId: String): Boolean = runCatching {
        firestore.collection(PersonBookmarksSyncCodec.COLLECTION).document(documentId)
            .delete().await() != null
    }.getOrDefault(false)

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }; addOnFailureListener { cont.resume(null) }
    }

    companion object {
        fun create(context: Context): FirestorePersonBookmarksSyncStore? {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context) ?: return null
            return FirestorePersonBookmarksSyncStore(FirebaseFirestore.getInstance(app))
        }
    }
}
