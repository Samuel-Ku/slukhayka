package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#691/#695) — the ONE place a local [ListenerCollection] becomes its
 * public document shape.
 *
 * It exists because the public shape and the local one are not the same thing:
 * `items` carry a per-book **reason**, and a fork of someone else's collection
 * copies exactly that composition. Building the document ad hoc — as the
 * Firestore store and its in-memory fake each used to — silently dropped the
 * reasons, so the fork AC («локальна копія зі складом і причинами») was met on
 * paper only. Every publish path goes through here now.
 *
 * The same limits as [PublishedCollectionCodec] apply: the two book lists are
 * positionally parallel and both truncate to [PublishedCollectionCodec.MAX_BOOKS].
 */
object PublishedCollectionFactory {

    /** The frozen display facts of one book, resolved at publish time. */
    data class ItemSnapshot(
        val title: String = "",
        val author: String = "",
        val coverUrl: String? = null
    )

    /**
     * @return the publishable document, or null when this collection can never
     * be published (no real identity, no usable pseudonym).
     */
    fun of(
        collection: ListenerCollection,
        authorId: String,
        pseudonym: String,
        publishedAt: Long,
        /** bookId -> the local facts known at publish time (best-effort). */
        itemSnapshots: Map<String, ItemSnapshot> = emptyMap()
    ): PublishedCollection? {
        if (!CuratorIdentity.isPublishable(authorId)) return null
        val cleanPseudonym = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
        if (cleanPseudonym.isEmpty()) return null
        val items = collection.items.take(PublishedCollectionCodec.MAX_BOOKS)
        return PublishedCollection(
            authorId = authorId,
            collectionId = collection.id,
            pseudonym = cleanPseudonym,
            title = collection.title,
            description = collection.description,
            bookIds = items.map { it.bookId },
            reasons = items.map { it.reason },
            // A fresh publication has no votes and no complaints — honest zeros.
            ratingSum = 0,
            ratingCount = 0,
            hidden = false,
            reportCount = 0,
            // #692 — the composition travels WITH its display snapshots, so the
            // reader renders titles/covers without owning those books.
            items = items.map { item ->
                val snapshot = itemSnapshots[item.bookId]
                PublishedCollectionItem(
                    bookId = item.bookId,
                    title = ListenerCollectionLimits.cleanTitle(snapshot?.title),
                    author = ListenerCollectionLimits.clean(snapshot?.author, PublishedCollectionCodec.MAX_AUTHOR_LEN),
                    coverUrl = snapshot?.coverUrl?.takeIf { it.isNotBlank() },
                    reason = item.reason
                )
            },
            publishedAt = publishedAt
        )
    }
}
