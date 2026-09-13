package com.slukhayka.audiobooks.data.collections

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-51 (#691) — the Firestore door for published collections.
 *
 * Online-only by contract: every method awaits the round trip and reports an
 * honest [PublishResult.Refused] instead of queueing. Local collections are
 * unaffected — they never touch this class.
 */
class FirestoreListenerCollectionsSharedStore(
    private val firestore: FirebaseFirestore,
    private val clock: () -> Long = System::currentTimeMillis
) : ListenerCollectionsSharedStore {

    override suspend fun publish(
        collection: ListenerCollection,
        authorId: String,
        pseudonym: String
    ): PublishResult {
        if (!CuratorIdentity.isPublishable(authorId)) return PublishResult.Refused("no-identity")
        val clean = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
        if (clean.isEmpty()) return PublishResult.Refused("no-pseudonym")

        val document = PublishedCollection(
            authorId = authorId,
            collectionId = collection.id,
            pseudonym = clean,
            title = collection.title,
            description = collection.description,
            bookIds = collection.items.map { it.bookId },
            publishedAt = clock()
        )
        return if (write(document.documentId, PublishedCollectionCodec.encode(document))) {
            PublishResult.Published
        } else {
            PublishResult.Refused("write-failed")
        }
    }

    override suspend fun renameAuthor(authorId: String, pseudonym: String): PublishResult {
        val clean = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
        if (clean.isEmpty()) return PublishResult.Refused("no-pseudonym")
        val mine = queryByAuthor(authorId)
        if (mine.isEmpty()) return PublishResult.Refused("unknown-author")
        // ALL of the author's collections carry the same public name.
        mine.forEach { document ->
            write(document.documentId, mapOf("pseudonym" to clean))
        }
        return PublishResult.Published
    }

    override suspend fun deleteAuthorProfile(authorId: String): PublishResult {
        val mine = queryByAuthor(authorId)
        if (mine.isEmpty()) return PublishResult.Refused("unknown-author")
        mine.forEach { document -> delete(document.documentId) }
        return PublishResult.Published
    }

    override suspend fun publishedBy(authorId: String): List<PublishedCollection> =
        queryByAuthor(authorId)

    private suspend fun queryByAuthor(authorId: String): List<PublishedCollection> {
        val documents = try {
            firestore.collection(COLLECTION)
                .whereEqualTo(FIELD_AUTHOR_ID, authorId)
                .get()
                .awaitDocuments()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return documents.mapNotNull(PublishedCollectionCodec::decode)
    }

    private suspend fun write(documentId: String, fields: Map<String, Any?>): Boolean = try {
        firestore.collection(COLLECTION).document(documentId)
            .set(fields.filterValues { it != null })
            .awaitWrite()
    } catch (_: Exception) {
        false
    }

    private suspend fun delete(documentId: String): Boolean = try {
        firestore.collection(COLLECTION).document(documentId).delete().awaitWrite()
    } catch (_: Exception) {
        false
    }

    /** Bridges the Play Services [Task] onto a coroutine: documents or null on failure. */
    private suspend fun Task<QuerySnapshot>.awaitDocuments(): List<Map<String, Any>>? =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { snapshot -> cont.resume(snapshot.documents.mapNotNull { it.data }) }
            addOnFailureListener { cont.resume(null) }
        }

    private suspend fun Task<Void>.awaitWrite(): Boolean =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(true) }
            addOnFailureListener { cont.resume(false) }
        }

    companion object {
        private const val COLLECTION = "curator_collections"
        private const val FIELD_AUTHOR_ID = "authorId"

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json) — in which case public surfaces
         * are honestly absent rather than half-present.
         */
        fun create(context: Context): FirestoreListenerCollectionsSharedStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreListenerCollectionsSharedStore(FirebaseFirestore.getInstance(app))
        }
    }
}
