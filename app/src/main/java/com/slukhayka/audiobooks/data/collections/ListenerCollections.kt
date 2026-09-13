package com.slukhayka.audiobooks.data.collections

/** One book's place in a collection. Order is insertion order. */
data class ListenerCollectionItem(
    val bookId: String,
    /** Why this book belongs here — already hygienic (see the limits). */
    val reason: String,
    val addedAt: Long
)

/**
 * A listener's own collection (spec-51, #689). Local-first: it exists offline
 * and never depends on a network round trip. All text here has already passed
 * [ListenerCollectionLimits].
 */
data class ListenerCollection(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val items: List<ListenerCollectionItem> = emptyList()
) {
    /** The first real item — the cover source the AC asks for. */
    val coverBookId: String? get() = items.firstOrNull()?.bookId

    fun contains(bookId: String): Boolean = items.any { it.bookId == bookId }
}

/**
 * The local facade's contract, mirroring `ListenerReviewsStore`: tiny, honest,
 * and testable on fakes without Room.
 */
interface ListenerCollectionsStore {

    /** @return the new collection's id, or null when the title is unusable. */
    suspend fun create(title: String?, description: String?): String?

    /**
     * Adds a book once. A duplicate is a no-op that reports the honest false —
     * a collection is a set of books, and the order is insertion order.
     */
    suspend fun add(collectionId: String, bookId: String, reason: String?): Boolean

    suspend fun remove(collectionId: String, bookId: String): Boolean

    /** Renaming to an unusable title changes nothing and reports false. */
    suspend fun rename(collectionId: String, title: String?): Boolean

    suspend fun updateDescription(collectionId: String, description: String?): Boolean

    /** @return true when a collection was really deleted (with its items). */
    suspend fun delete(collectionId: String): Boolean

    suspend fun all(): List<ListenerCollection>
}
