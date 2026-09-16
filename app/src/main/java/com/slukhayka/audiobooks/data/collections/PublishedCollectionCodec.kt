package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#691) — a collection as it exists OUTSIDE the device.
 *
 * [authorId] is `sha256(uid)` (see [CuratorIdentity]): the raw uid is never a
 * field, never an id, never a key. [pseudonym] is the listener's public name.
 */
data class PublishedCollection(
    val authorId: String,
    val collectionId: String,
    val pseudonym: String,
    val title: String,
    val description: String,
    val bookIds: List<String>,
    /**
     * Spec-51 (#695) — each book's reason, positionally parallel to [bookIds].
     * Without it a fork of someone else's collection would lose the reasons the
     * AC requires, so the wire shape must carry them.
     */
    val reasons: List<String> = emptyList(),
    /**
     * Spec-51 (#694) — the transactional rating aggregate. Zero votes means
     * zero here AND no average anywhere: the card shows stars only when
     * [ratingCount] is real.
     */
    val ratingSum: Int = 0,
    val ratingCount: Int = 0,
    /**
     * Spec-51 (#696) — moderation state. [hidden] is set by the third unique
     * complaint and can never be cleared; public surfaces never render a
     * hidden collection, while its author sees it (and may only delete it).
     */
    val hidden: Boolean = false,
    val reportCount: Int = 0,
    val publishedAt: Long
) {
    /** The public document id: the author and the collection, still hashed. */
    val documentId: String get() = "$authorId-$collectionId"
}

/**
 * The document codec. Firestore rules are the second gate; this is the first:
 * a hostile or corrupt document decodes to null (a miss), never to a
 * half-valid object, and nothing over its limit is ever encoded.
 */
object PublishedCollectionCodec {

    const val MAX_PSEUDONYM_LEN = 40
    const val MAX_BOOKS = 500

    fun encode(collection: PublishedCollection): Map<String, Any?> = mapOf(
        "authorId" to collection.authorId,
        "collectionId" to collection.collectionId,
        "pseudonym" to collection.pseudonym.take(MAX_PSEUDONYM_LEN),
        "title" to ListenerCollectionLimits.cleanTitle(collection.title),
        "description" to ListenerCollectionLimits.cleanDescription(collection.description),
        "bookIds" to collection.bookIds.take(MAX_BOOKS),
        "reasons" to collection.reasons.take(MAX_BOOKS)
            .map { ListenerCollectionLimits.cleanReason(it) },
        "ratingSum" to collection.ratingSum.coerceAtLeast(0),
        "ratingCount" to collection.ratingCount.coerceAtLeast(0),
        "hidden" to collection.hidden,
        "reportCount" to collection.reportCount.coerceAtLeast(0),
        "publishedAt" to collection.publishedAt
    )

    /** @return null when the document is not a well-formed published collection. */
    fun decode(document: Map<String, Any?>?): PublishedCollection? {
        if (document == null) return null
        val authorId = document["authorId"] as? String ?: return null
        val collectionId = document["collectionId"] as? String ?: return null
        val title = document["title"] as? String ?: return null
        // A published collection must have an author and a real title: those
        // two are what make it addressable and worth showing.
        if (authorId.isBlank() || collectionId.isBlank()) return null
        if (ListenerCollectionLimits.cleanTitle(title).isBlank()) return null

        val books = (document["bookIds"] as? List<*>)
            ?.filterIsInstance<String>()
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return PublishedCollection(
            authorId = authorId,
            collectionId = collectionId,
            pseudonym = (document["pseudonym"] as? String).orEmpty().take(MAX_PSEUDONYM_LEN),
            title = ListenerCollectionLimits.cleanTitle(title),
            description = ListenerCollectionLimits.cleanDescription(document["description"] as? String),
            bookIds = books.take(MAX_BOOKS),
            // Parallel and positionally aligned: a short list is padded, a long
            // one truncated — a reason can never attach to the wrong book.
            reasons = (document["reasons"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { ListenerCollectionLimits.cleanReason(it) }
                ?.take(MAX_BOOKS)
                .orEmpty()
                .let { cleaned ->
                    List(books.take(MAX_BOOKS).size) { index -> cleaned.getOrElse(index) { "" } }
                },
            // #694 — a hostile aggregate is a miss, never a fabricated number:
            // a negative sum/count decodes to the honest zero.
            ratingSum = ((document["ratingSum"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            ratingCount = ((document["ratingCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            hidden = (document["hidden"] as? Boolean) ?: false,
            reportCount = ((document["reportCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            publishedAt = (document["publishedAt"] as? Number)?.toLong() ?: 0L
        )
    }
}
