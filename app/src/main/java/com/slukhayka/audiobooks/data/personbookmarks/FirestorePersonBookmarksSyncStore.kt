package com.slukhayka.audiobooks.data.personbookmarks

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executor
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

    override suspend fun write(documentId: String, fields: Map<String, Any>): Boolean =
        firestore.collection(PersonBookmarksSyncCodec.COLLECTION).document(documentId)
            .set(fields + ("updatedAt" to FieldValue.serverTimestamp())).awaitCompletion()

    override suspend fun delete(documentId: String): Boolean =
        firestore.collection(PersonBookmarksSyncCodec.COLLECTION).document(documentId)
            .delete().awaitCompletion()


    companion object {
        fun create(context: Context): FirestorePersonBookmarksSyncStore? {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context) ?: return null
            return FirestorePersonBookmarksSyncStore(FirebaseFirestore.getInstance(app))
        }
    }
}

/** Firestore writes complete successfully with a null Void result. */
internal suspend fun Task<*>.awaitCompletion(): Boolean = try {
    await()
    true
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener(Executor { it.run() }) { task ->
        if (!cont.isActive) return@addOnCompleteListener
        when {
            task.isCanceled -> cont.cancel()
            task.isSuccessful -> cont.resume(task.result)
            else -> cont.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
        }
    }
}
