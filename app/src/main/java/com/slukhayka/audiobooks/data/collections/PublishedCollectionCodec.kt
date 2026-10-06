package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.achievements.ShowcaseAwardSnapshot
import com.slukhayka.audiobooks.data.achievements.ShowcasePublication

/**
 * Spec-51 (#692) — one published position: the frozen display snapshot of a
 * book at publish time, so someone else's collection renders its composition
 * without owning those books. [reason] is the curator's own line.
 */
data class PublishedCollectionItem(
    val bookId: String,
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val reason: String = ""
)

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
    /**
     * #692 — the composition with display snapshots, positionally parallel to
     * [bookIds]. Legacy documents carry none; the reader then shows the honest
     * count instead of inventing titles.
     */
    val items: List<PublishedCollectionItem> = emptyList(),
    /**
     * #705 (T7) — the curator's showcase: up to three awards they chose to make
     * public, riding on every one of their collection documents.
     *
     * It lives HERE rather than in a document of its own for the same reason
     * [pseudonym] does: the public profile has no author document, so anything
     * the profile shows has to travel with the collections it already reads.
     * That also makes the ticket's fourth criterion fall out on its own — no
     * publications means no document to read a showcase from.
     *
     * Empty is the honest default and the honest "nothing is published".
     */
    val showcase: List<ShowcaseAwardSnapshot> = emptyList(),
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

    /** A book's author in a display snapshot — real names are short. */
    const val MAX_AUTHOR_LEN = 200

    /** An award's name in the showcase — a short label, never a sentence. */
    const val MAX_AWARD_NAME_LEN = 80

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
        "items" to collection.items.take(MAX_BOOKS).map { item -> item.toMap() },
        "showcase" to encodeShowcase(collection.showcase),
        "publishedAt" to collection.publishedAt
    )

    /**
     * #705 — the wire form of a showcase, bounded and cleaned.
     *
     * Public because the store writes the showcase on its own, without a whole
     * collection to hand: one encoder means the partial write and the full one
     * can never drift apart.
     */
    fun encodeShowcase(awards: List<ShowcaseAwardSnapshot>): List<Map<String, Any?>> =
        awards.take(ShowcasePublication.MAX_PUBLISHED).map { award -> award.toMap() }

    private fun ShowcaseAwardSnapshot.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to ListenerCollectionLimits.clean(name, MAX_AWARD_NAME_LEN)
    )

    private fun PublishedCollectionItem.toMap(): Map<String, Any?> = mapOf(
        "bookId" to bookId,
        "title" to ListenerCollectionLimits.cleanTitle(title),
        "author" to ListenerCollectionLimits.clean(author, MAX_AUTHOR_LEN),
        "coverUrl" to coverUrl?.takeIf { it.isNotBlank() },
        "reason" to ListenerCollectionLimits.cleanReason(reason)
    )

    /** A display snapshot; a malformed entry is dropped, never half-shown. */
    private fun decodeItem(entry: Any?): PublishedCollectionItem? {
        val map = entry as? Map<*, *> ?: return null
        val bookId = (map["bookId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return PublishedCollectionItem(
            bookId = bookId,
            title = ListenerCollectionLimits.cleanTitle(map["title"] as? String),
            author = ListenerCollectionLimits.clean(map["author"] as? String, MAX_AUTHOR_LEN),
            coverUrl = (map["coverUrl"] as? String)?.takeIf { it.isNotBlank() },
            reason = ListenerCollectionLimits.cleanReason(map["reason"] as? String)
        )
    }

    /**
     * One showcase entry; a malformed one is dropped, never half-shown.
     *
     * An award with no id cannot be addressed and an award with no name cannot
     * be read, so either one missing means the entry does not survive.
     */
    private fun decodeAward(entry: Any?): ShowcaseAwardSnapshot? {
        val map = entry as? Map<*, *> ?: return null
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val name = ListenerCollectionLimits.clean(map["name"] as? String, MAX_AWARD_NAME_LEN)
        if (name.isBlank()) return null
        return ShowcaseAwardSnapshot(id = id, name = name)
    }

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
            items = (document["items"] as? List<*>)
                ?.mapNotNull { entry -> decodeItem(entry) }
                ?.take(MAX_BOOKS)
                .orEmpty(),
            // #705 — the showcase is bounded and each entry is validated, so a
            // hostile list can never put more than three awards on a profile,
            // and an entry that cannot be named is dropped rather than shown
            // blank. Anything unusable decodes to the honest empty.
            showcase = (document["showcase"] as? List<*>)
                ?.mapNotNull { entry -> decodeAward(entry) }
                ?.take(ShowcasePublication.MAX_PUBLISHED)
                .orEmpty(),
            hidden = (document["hidden"] as? Boolean) ?: false,
            reportCount = ((document["reportCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            publishedAt = (document["publishedAt"] as? Number)?.toLong() ?: 0L
        )
    }
}
