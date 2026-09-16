package com.slukhayka.audiobooks.data.collections

/** Spec-51 (#691) — the honest outcome of an online-only action. */
sealed interface PublishResult {
    data object Published : PublishResult

    /**
     * Nothing left the device. There is deliberately NO queue: a refusal is
     * final and visible, never a silent retry that publishes later.
     */
    data class Refused(val reason: String) : PublishResult
}

/**
 * Spec-51 (#691) — publishing is ONLINE-ONLY. The shared store is the only
 * door out of the device, and every method can honestly refuse.
 *
 * A missing implementation (no Firestore configured) must leave the local
 * collections fully usable and expose NO public surface — see the callers.
 */
interface ListenerCollectionsSharedStore {

    /** @return [PublishResult.Published], or a [PublishResult.Refused] reason. */
    suspend fun publish(
        collection: ListenerCollection,
        authorId: String,
        pseudonym: String
    ): PublishResult

    /** Replaces the pseudonym on the author document AND all their collections. */
    suspend fun renameAuthor(authorId: String, pseudonym: String): PublishResult

    /** Removes the author document and every collection it owns. */
    suspend fun deleteAuthorProfile(authorId: String): PublishResult

    /** What is publicly visible for this author. */
    suspend fun publishedBy(authorId: String): List<PublishedCollection>

    /**
     * Spec-51 (#692) — every VISIBLE published collection that carries this
     * book, for the book page's «Добірки з цією книгою» block. Ordering is the
     * caller's ([CollectionRanking]); this is a plain read.
     */
    suspend fun containing(bookId: String): List<PublishedCollection>
}
