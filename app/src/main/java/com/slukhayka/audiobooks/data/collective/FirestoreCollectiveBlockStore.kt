package com.slukhayka.audiobooks.data.collective

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * #527 / ADR-0028 — the thin Firestore transport of the shared block lane:
 * one `catalog_blocks` document per block identity (sourceId + kind), ordered
 * by `fetchedAt`. A re-publication replaces its own row, so the lane never
 * accumulates history and a reader's cursor stays monotonic.
 *
 * Degrade-never-throw: a missing document, a network failure, a timeout or an
 * unreadable document all yield an empty page / a no-op. Firebase is optional:
 * [create] returns null without a configuration, so the lane simply does not
 * exist and the local blocks keep working.
 */
class FirestoreCollectiveBlockStore(
    private val firestore: FirebaseFirestore
) : CollectiveBlockStore {

    override suspend fun putBlock(block: CollectiveFeedBlock) {
        val document = CollectiveBlockCodec.toMap(block) ?: return
        runCatching {
            firestore.collection(COLLECTION)
                .document(blockDocumentId(block))
                .set(document)
                .awaitOrNull()
        }
    }

    override suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int): CollectiveBlockPage {
        val bounded = CollectivePageLimits.bounded(limit)
        if (bounded == 0) return CollectiveBlockPage(emptyList(), null)
        return try {
            var query = firestore.collection(COLLECTION)
                .orderBy(CollectiveBlockCodec.FIELD_FETCHED_AT)
                .orderBy(FieldPath.documentId())
            if (after != null) query = query.startAfter(after.fetchedAt, after.documentId)
            val snapshot = query.limit((bounded + 1).toLong()).get().awaitOrNull()
                ?: return CollectiveBlockPage(emptyList(), null)
            val pageDocuments = snapshot.documents.take(bounded)
            val blocks = pageDocuments.mapNotNull { document ->
                document.data?.let { CollectiveBlockCodec.fromMap(it) }
            }
            val nextCursor = pageDocuments.lastOrNull()?.let { document ->
                (document.data?.get(CollectiveBlockCodec.FIELD_FETCHED_AT) as? Number)
                    ?.toLong()
                    ?.let { fetchedAt -> CollectiveBlockCursor(fetchedAt, document.id) }
            }
            CollectiveBlockPage(blocks, nextCursor)
        } catch (e: Exception) {
            CollectiveBlockPage(emptyList(), null)
        }
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "catalog_blocks"

        /** One row per block identity: a re-publication replaces it. */
        fun blockDocumentId(block: CollectiveFeedBlock): String =
            "${block.sourceId}-${block.kind.name.lowercase()}"

        /** Null without Firebase configuration — the lane then does not exist. */
        fun create(context: Context): FirestoreCollectiveBlockStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreCollectiveBlockStore(FirebaseFirestore.getInstance(app))
        }
    }
}
