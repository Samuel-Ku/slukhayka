package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#691) — the single place that decides whether public collections
 * exist at all.
 *
 * When no shared store is configured (no Firestore), the LOCAL collections
 * keep working exactly as before and there is simply no public surface: the
 * gate reports [available] = false, publishing refuses honestly, and reading
 * public collections yields nothing. No half-state, no empty-but-present
 * screen, and never a silent queue.
 */
class PublicCollectionsGate(
    private val sharedStore: ListenerCollectionsSharedStore?
) {

    /** Public surfaces render only when this is true. */
    val available: Boolean get() = sharedStore != null

    suspend fun publish(
        collection: ListenerCollection,
        authorId: String,
        pseudonym: String
    ): PublishResult =
        sharedStore?.publish(collection, authorId, pseudonym)
            ?: PublishResult.Refused(NO_SHARED_STORE)

    suspend fun renameAuthor(authorId: String, pseudonym: String): PublishResult =
        sharedStore?.renameAuthor(authorId, pseudonym)
            ?: PublishResult.Refused(NO_SHARED_STORE)

    suspend fun deleteAuthorProfile(authorId: String): PublishResult =
        sharedStore?.deleteAuthorProfile(authorId)
            ?: PublishResult.Refused(NO_SHARED_STORE)

    /** Without a shared store there is nothing public to show — not an error. */
    suspend fun publishedBy(authorId: String): List<PublishedCollection> =
        sharedStore?.publishedBy(authorId).orEmpty()

    /** #692 — collections containing one book; empty without a shared store. */
    suspend fun containing(bookId: String): List<PublishedCollection> =
        sharedStore?.containing(bookId).orEmpty()

    /** #694 — voting is online-only; without a shared store it refuses honestly. */
    suspend fun vote(documentId: String, voterKey: String, stars: Int): PublishResult =
        sharedStore?.vote(documentId, voterKey, stars)
            ?: PublishResult.Refused(NO_SHARED_STORE)

    /** #694 — the listener's own vote; without a shared store there is none. */
    suspend fun myVote(voterKey: String): Int? = sharedStore?.myVote(voterKey)

    /** #696 — reporting is online-only; without a shared store it refuses. */
    suspend fun report(documentId: String, reporterKey: String): PublishResult =
        sharedStore?.report(documentId, reporterKey)
            ?: PublishResult.Refused(NO_SHARED_STORE)

    /** #696 — deleting one's own hidden collection. */
    suspend fun deleteOwnCollection(documentId: String): PublishResult =
        sharedStore?.deleteOwnCollection(documentId)
            ?: PublishResult.Refused(NO_SHARED_STORE)

    /** #693 — the rail; empty without a shared store (no fake shelf). */
    suspend fun topPublic(limit: Int): List<PublishedCollection> =
        sharedStore?.topPublic(limit).orEmpty()

    /** #693 — a curator's visible collections; empty without a shared store. */
    suspend fun visibleBy(authorId: String): List<PublishedCollection> =
        sharedStore?.visibleBy(authorId).orEmpty()

    companion object {
        const val NO_SHARED_STORE = "no-shared-store"
    }
}
