package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * #522 — the publish half of the tracer: when a book is VERIFIED in a direct
 * source (a real import lands it), the install offers that public card to the
 * collective lane. Only DIRECT, non-scam, Ukrainian sources pass
 * [CollectiveCardLimits]; the first publishable Source of the book wins, so a
 * legacy 4read URL never blocks a real source. Best-effort and silent —
 * a failing write never touches the import that triggered it.
 */
class CollectiveCatalogPublisher(
    private val store: CollectiveCardStore?,
    private val book: suspend (String) -> AudiobookEntity?,
    private val sources: suspend (String) -> List<SourceEntity>,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** @return true when a card for this Work actually reached the lane. */
    suspend fun publishVerified(bookId: String): Boolean {
        val store = store ?: return false
        val book = runCatching { book(bookId) }.getOrNull() ?: return false
        val sources = runCatching { sources(bookId) }.getOrNull().orEmpty()
            .filter { it.url.isNotBlank() }
        for (source in sources) {
            val card = cardOf(book, source, clock())
            if (!CollectiveCardLimits.isPublishable(card)) continue
            return runCatching { store.putCard(card) }.isSuccess
        }
        return false
    }

    private fun cardOf(
        book: AudiobookEntity,
        source: SourceEntity,
        observedAt: Long
    ): CollectiveCardPublication = CollectiveCardPublication(
        sourceId = source.type,
        sourceUrl = source.url,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        language = book.language,
        coverUrl = book.coverImageUrl,
        seriesTitle = book.seriesTitle,
        seriesIndex = book.seriesIndex,
        durationSeconds = book.totalDurationSeconds.takeIf { it > 0L },
        chapterCount = book.totalChapters.takeIf { it > 0 },
        observedAt = observedAt
    )

    companion object {
        /** The source a card's URL names, exposed for callers that pre-filter. */
        fun sourceIdOf(url: String): String = sourceIdForUrl(url)
    }
}
