package com.slukhayka.audiobooks.data.collections

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
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
        // #695 — the reasons travel with the books: a fork of this collection
        // rebuilds its composition from them, so dropping them here would
        // quietly break the fork AC.
        val document = PublishedCollectionFactory.of(collection, authorId, pseudonym, clock())
            ?: return PublishResult.Refused("no-pseudonym")
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

    override suspend fun containing(bookId: String): List<PublishedCollection> {
        if (bookId.isBlank()) return emptyList()
        // #692 — the book page asks "who curated this book?"; an array-contains
        // query answers it in one request (never an N+1 over the collections).
        val documents = try {
            firestore.collection(COLLECTION)
                .whereArrayContains(FIELD_BOOK_IDS, bookId)
                .get()
                .awaitDocuments()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        // #696 — a hidden collection is not a public surface: it never appears
        // in a book's block, the rail or a curator profile.
        return documents.mapNotNull(PublishedCollectionCodec::decode).filterNot { it.hidden }
    }

    override suspend fun vote(documentId: String, voterKey: String, stars: Int): PublishResult {
        if (documentId.isBlank() || voterKey.isBlank() || !CollectionRating.isValidStars(stars)) {
            return PublishResult.Refused("bad-vote")
        }
        return try {
            // The aggregate and the vote travel in ONE transaction: a re-vote
            // replaces the person's previous stars and never doubles a count.
            firestore.runTransaction<Void> { transaction ->
                val voteRef = firestore.collection(VOTES).document(voterKey)
                val previous = transaction.get(voteRef).getLong(FIELD_STARS)?.toInt()
                val collectionRef = firestore.collection(COLLECTION).document(documentId)
                val snapshot = transaction.get(collectionRef)
                val sum = snapshot.getLong(FIELD_RATING_SUM)?.toInt() ?: 0
                val count = snapshot.getLong(FIELD_RATING_COUNT)?.toInt() ?: 0
                val (newSum, newCount) = CollectionRating.applyVote(sum, count, previous, stars)
                transaction.set(
                    voteRef,
                    mapOf(
                        FIELD_DOCUMENT_ID to documentId,
                        FIELD_STARS to stars,
                        FIELD_CREATED_AT to clock()
                    )
                )
                transaction.update(
                    collectionRef,
                    mapOf(FIELD_RATING_SUM to newSum, FIELD_RATING_COUNT to newCount)
                )
                null
            }.awaitWrite()
            PublishResult.Published
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PublishResult.Refused("vote-failed")
        }
    }

    override suspend fun topPublic(limit: Int): List<PublishedCollection> {
        if (limit <= 0) return emptyList()
        // The average is computed (sum/count), which Firestore cannot order by,
        // so the query takes a BOUNDED candidate page by vote count and the
        // shared ranking picks the real top. A collection with no votes can
        // never outrank a voted one, so the candidate rule is sound.
        val documents = try {
            firestore.collection(COLLECTION)
                .orderBy(FIELD_RATING_COUNT, Query.Direction.DESCENDING)
                .limit(TOP_CANDIDATES)
                .get()
                .awaitDocuments()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return CollectionRanking.top(
            documents.mapNotNull(PublishedCollectionCodec::decode).filterNot { it.hidden },
            limit
        )
    }

    override suspend fun visibleBy(authorId: String): List<PublishedCollection> =
        queryByAuthor(authorId).filterNot { it.hidden }

    override suspend fun deleteOwnCollection(documentId: String): PublishResult = when {
        documentId.isBlank() -> PublishResult.Refused("bad-collection")
        delete(documentId) -> PublishResult.Published
        else -> PublishResult.Refused("delete-failed")
    }

    override suspend fun report(documentId: String, reporterKey: String): PublishResult {
        if (documentId.isBlank() || reporterKey.isBlank()) return PublishResult.Refused("bad-report")
        return try {
            // One complaint per person and the threshold travel in ONE
            // transaction: a duplicate never counts, and hidden can only be
            // turned ON (the rules enforce the same one-way transition).
            firestore.runTransaction<Void> { transaction ->
                val reportRef = firestore.collection(REPORTS).document(reporterKey)
                if (!transaction.get(reportRef).exists()) {
                    val collectionRef = firestore.collection(COLLECTION).document(documentId)
                    val snapshot = transaction.get(collectionRef)
                    val count = snapshot.getLong(FIELD_REPORT_COUNT)?.toInt() ?: 0
                    val hidden = snapshot.getBoolean(FIELD_HIDDEN) ?: false
                    transaction.set(
                        reportRef,
                        mapOf(FIELD_DOCUMENT_ID to documentId, FIELD_CREATED_AT to clock())
                    )
                    transaction.update(
                        collectionRef,
                        mapOf(
                            FIELD_REPORT_COUNT to count + 1,
                            FIELD_HIDDEN to CollectionModeration.nextHidden(hidden, count)
                        )
                    )
                }
                null
            }.awaitWrite()
            PublishResult.Published
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PublishResult.Refused("report-failed")
        }
    }

    override suspend fun myVote(voterKey: String): Int? {
        if (voterKey.isBlank()) return null
        val document = try {
            firestore.collection(VOTES).document(voterKey).get().awaitDocument()
        } catch (_: Exception) {
            null
        } ?: return null
        return (document[FIELD_STARS] as? Number)?.toInt()?.takeIf { CollectionRating.isValidStars(it) }
    }

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

    /** Bridges one document read: its fields, or null on failure/missing. */
    private suspend fun Task<com.google.firebase.firestore.DocumentSnapshot>.awaitDocument(): Map<String, Any>? =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { snapshot -> cont.resume(snapshot.data) }
            addOnFailureListener { cont.resume(null) }
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
        /** #694 — anonymous votes: one document per (person, collection) key. */
        private const val VOTES = "curator_collection_votes"
        /** #696 — anonymous complaints, same key shape as a vote. */
        private const val REPORTS = "curator_collection_reports"
        private const val FIELD_AUTHOR_ID = "authorId"
        private const val FIELD_BOOK_IDS = "bookIds"
        private const val FIELD_RATING_SUM = "ratingSum"
        private const val FIELD_RATING_COUNT = "ratingCount"
        private const val FIELD_DOCUMENT_ID = "documentId"
        private const val FIELD_STARS = "stars"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_HIDDEN = "hidden"
        private const val FIELD_REPORT_COUNT = "reportCount"

        /** #693 — the bounded candidate page the rail ranks. */
        private const val TOP_CANDIDATES = 50L

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
