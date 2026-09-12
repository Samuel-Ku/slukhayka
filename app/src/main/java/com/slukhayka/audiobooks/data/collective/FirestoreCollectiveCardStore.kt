package com.slukhayka.audiobooks.data.collective

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * #522 / ADR-0028 — the thin Firestore transport of the collective catalogue
 * lane. It reads/writes the pure [CollectiveCardCodec] shape in the
 * `catalog_cards` collection, one document per (Source, Work); the codec's
 * allowed-field set is what keeps a hostile document from becoming a card.
 *
 * Degrade-never-throw: a missing document, a network failure, a timeout or an
 * unreadable document all yield an empty page / a no-op — the caller keeps
 * its local projection. Firebase is optional: [create] returns null without a
 * Firebase configuration, so the lane simply does not exist.
 */
class FirestoreCollectiveCardStore(
    private val firestore: FirebaseFirestore
) : CollectiveCardStore {

    override suspend fun putCard(card: CollectiveCardPublication) {
        val document = CollectiveCardCodec.toMap(card) ?: return
        runCatching {
            firestore.collection(COLLECTION)
                .document(cardDocumentId(card))
                .set(document)
                .awaitOrNull()
        }
    }

    override suspend fun getCardsPage(after: CollectiveCursor?, limit: Int): CollectivePage {
        val bounded = CollectivePageLimits.bounded(limit)
        if (bounded == 0) return CollectivePage(emptyList(), null)
        return try {
            var query = firestore.collection(COLLECTION)
                .orderBy(CollectiveCardCodec.FIELD_OBSERVED_AT)
                .orderBy(FieldPath.documentId())
            if (after != null) {
                query = query.startAfter(after.observedAt, after.documentId)
            }
            val snapshot = query.limit((bounded + 1).toLong()).get().awaitOrNull()
                ?: return CollectivePage(emptyList(), null)
            val pageDocuments = snapshot.documents.take(bounded)
            val cards = pageDocuments.mapNotNull { document ->
                document.data?.let { CollectiveCardCodec.fromMap(it) }
            }
            val nextCursor = pageDocuments.lastOrNull()?.let { document ->
                (document.data?.get(CollectiveCardCodec.FIELD_OBSERVED_AT) as? Number)
                    ?.toLong()
                    ?.let { observedAt -> CollectiveCursor(observedAt, document.id) }
            }
            CollectivePage(cards, nextCursor)
        } catch (e: Exception) {
            CollectivePage(emptyList(), null)
        }
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "catalog_cards"

        /**
         * The deterministic document key of one (Source, Work) card, so a
         * re-publication of the same observation replaces its own row instead
         * of duplicating it.
         */
        fun cardDocumentId(card: CollectiveCardPublication): String =
            "${card.sourceId}-${Integer.toHexString(card.mergeKey.hashCode())}"

        /** Null without Firebase configuration — the lane then does not exist. */
        fun create(context: Context): FirestoreCollectiveCardStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreCollectiveCardStore(FirebaseFirestore.getInstance(app))
        }
    }
}
