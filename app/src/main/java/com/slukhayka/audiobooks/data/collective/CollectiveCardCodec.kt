package com.slukhayka.audiobooks.data.collective

/**
 * #522 / ADR-0028 — the shared catalogue-card lane's pure wire shape. It has
 * ONE allowed field set: decoding rejects any document carrying an unknown
 * field, so a hostile or corrupted contribution that smuggles a query,
 * contributor id, position, cookie, session header or track URL is a miss,
 * never a half-fact. Encoding refuses anything the bounds reject.
 */
object CollectiveCardCodec {

    const val FIELD_SOURCE_ID = "sourceId"
    const val FIELD_SOURCE_URL = "sourceUrl"
    const val FIELD_TITLE = "title"
    const val FIELD_AUTHOR = "author"
    const val FIELD_NARRATOR = "narrator"
    const val FIELD_LANGUAGE = "language"
    const val FIELD_COVER_URL = "coverUrl"
    const val FIELD_SERIES_TITLE = "seriesTitle"
    const val FIELD_SERIES_INDEX = "seriesIndex"
    const val FIELD_DURATION_SECONDS = "durationSeconds"
    const val FIELD_CHAPTER_COUNT = "chapterCount"
    const val FIELD_OBSERVED_AT = "observedAt"

    /** The ONLY keys a card document may carry. */
    val ALLOWED_FIELDS: Set<String> = setOf(
        FIELD_SOURCE_ID,
        FIELD_SOURCE_URL,
        FIELD_TITLE,
        FIELD_AUTHOR,
        FIELD_NARRATOR,
        FIELD_LANGUAGE,
        FIELD_COVER_URL,
        FIELD_SERIES_TITLE,
        FIELD_SERIES_INDEX,
        FIELD_DURATION_SECONDS,
        FIELD_CHAPTER_COUNT,
        FIELD_OBSERVED_AT
    )

    /** Encodes one publishable card; null when the bounds reject it. */
    fun toMap(card: CollectiveCardPublication): Map<String, Any>? {
        if (!CollectiveCardLimits.isPublishable(card)) return null
        return buildMap {
            put(FIELD_SOURCE_ID, card.sourceId)
            put(FIELD_SOURCE_URL, card.sourceUrl)
            put(FIELD_TITLE, card.title)
            put(FIELD_AUTHOR, card.author)
            put(FIELD_NARRATOR, card.narrator)
            put(FIELD_LANGUAGE, card.language)
            card.coverUrl?.let { put(FIELD_COVER_URL, it) }
            card.seriesTitle?.let { put(FIELD_SERIES_TITLE, it) }
            card.seriesIndex?.let { put(FIELD_SERIES_INDEX, it.toLong()) }
            card.durationSeconds?.let { put(FIELD_DURATION_SECONDS, it) }
            card.chapterCount?.let { put(FIELD_CHAPTER_COUNT, it.toLong()) }
            put(FIELD_OBSERVED_AT, card.observedAt)
        }
    }

    /** Decodes one document; null on a forbidden/missing/invalid field. */
    fun fromMap(data: Map<String, Any>): CollectiveCardPublication? {
        if (data.keys.any { it !in ALLOWED_FIELDS }) return null
        val card = CollectiveCardPublication(
            sourceId = data[FIELD_SOURCE_ID] as? String ?: return null,
            sourceUrl = data[FIELD_SOURCE_URL] as? String ?: return null,
            title = data[FIELD_TITLE] as? String ?: return null,
            author = data[FIELD_AUTHOR] as? String ?: return null,
            narrator = data[FIELD_NARRATOR] as? String ?: "",
            language = data[FIELD_LANGUAGE] as? String ?: "",
            coverUrl = data[FIELD_COVER_URL] as? String,
            seriesTitle = data[FIELD_SERIES_TITLE] as? String,
            seriesIndex = (data[FIELD_SERIES_INDEX] as? Number)?.toInt(),
            durationSeconds = (data[FIELD_DURATION_SECONDS] as? Number)?.toLong(),
            chapterCount = (data[FIELD_CHAPTER_COUNT] as? Number)?.toInt(),
            observedAt = (data[FIELD_OBSERVED_AT] as? Number)?.toLong() ?: return null
        )
        return card.takeIf { CollectiveCardLimits.isPublishable(it) }
    }
}

/** #522 — the ordered high-water mark of the collective lane. */
data class CollectiveCursor(
    val observedAt: Long,
    val documentId: String
)

/** #522 — one bounded ordered page of collective cards. */
data class CollectivePage(
    val cards: List<CollectiveCardPublication>,
    /** High-water mark of the last raw document, even on a terminal page. */
    val nextCursor: CollectiveCursor?
)

object CollectivePageLimits {
    /** A page is a bounded read: never the whole collection. */
    const val MAX_PAGE_SIZE: Int = 100

    fun bounded(limit: Int): Int = limit.coerceIn(0, MAX_PAGE_SIZE)
}
