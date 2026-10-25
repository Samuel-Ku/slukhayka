package com.slukhayka.audiobooks.data.collective

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * #527 / ADR-0028 — the thin Firestore transport of the shared block lane:
 * one `catalog_blocks` document per block identity (source + kind + canonical
 * provenance context), ordered by the SERVER revision. A re-publication
 * replaces its own row, so the lane never accumulates history and a reader's
 * cursor stays monotonic.
 *
 * #1238 — the order is `revision`, assigned by the conditional publish inside
 * the lease transaction, not by `fetchedAt`: a device clock is the observation
 * time, never the order of the shared lane. Rows written before #1238 carry no
 * revision and are outside this ordered page until an owner re-publishes them
 * under the lease; the local Room state is untouched either way.
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
        val ref = blockDocument(firestore, block.blockKey) ?: return
        runCatching { ref.set(document).awaitOrNull() }
    }

    override suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int): CollectiveBlockPage {
        val bounded = CollectivePageLimits.bounded(limit)
        if (bounded == 0) return CollectiveBlockPage(emptyList(), null)
        return try {
            var query = firestore.collection(COLLECTION)
                .orderBy(CollectiveBlockCodec.FIELD_REVISION)
                .orderBy(FieldPath.documentId())
            if (after != null) query = query.startAfter(after.revision, after.documentId)
            val snapshot = query.limit((bounded + 1).toLong()).get().awaitOrNull()
                ?: return CollectiveBlockPage(emptyList(), null)
            val pageDocuments = snapshot.documents.take(bounded)
            val blocks = pageDocuments.mapNotNull { document ->
                document.data?.let { CollectiveBlockCodec.fromMap(it) }
            }
            val nextCursor = pageDocuments.lastOrNull()?.let { document ->
                val revision = (document.data?.get(CollectiveBlockCodec.FIELD_REVISION) as? Number)
                    ?.toLong() ?: 0L
                CollectiveBlockCursor(revision, document.id)
            }
            CollectiveBlockPage(blocks, nextCursor)
        } catch (e: Exception) {
            CollectiveBlockPage(emptyList(), null)
        }
    }

    override suspend fun getBlock(blockKey: String): CollectiveFeedBlock? {
        val ref = blockDocument(firestore, blockKey) ?: return null
        return try {
            val snapshot = ref.get().awaitOrNull() ?: return null
            snapshot.data?.let { CollectiveBlockCodec.fromMap(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "catalog_blocks"

        /**
         * #1238 — one row per identity. The legacy identity keeps the #527 id,
         * so existing shared rows and the old cursor stay addressable.
         */
        fun blockDocumentId(blockKey: String): String? =
            parseCollectiveBlockKey(blockKey)?.let(::collectiveBlockDocumentId)

        /** The document of one identity; null for a key that is not a block key. */
        fun blockDocument(firestore: FirebaseFirestore, blockKey: String): DocumentReference? =
            blockDocumentId(blockKey)?.let { firestore.collection(COLLECTION).document(it) }

        /** Null without Firebase configuration — the lane then does not exist. */
        fun create(context: Context): FirestoreCollectiveBlockStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreCollectiveBlockStore(FirebaseFirestore.getInstance(app))
        }
    }
}
