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

    companion object {
        const val NO_SHARED_STORE = "no-shared-store"
    }
}
