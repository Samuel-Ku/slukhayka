package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * #735 / ADR-0041 — the curated and live collection match corpus is the
 * Медіатека, never the ephemeral union: one card per owned Work, carrying the
 * source the listener actually imported it from. A Work outside the library
 * can never appear in a collection, and an owned Work is matchable offline.
 *
 * The corpus is a pure projection so the matching rule is unit-testable
 * without Room; the caller supplies the current library rows.
 */
fun libraryMatchCorpus(books: List<AudiobookEntity>): List<GlobalSearchResult> =
    books
        .groupBy(::workKeyOf)
        .map { (_, members) -> members.first().asMatchCard() }

/** One Work, one card: the Works anchor, else `mergeKey`, else the book id. */
private fun workKeyOf(book: AudiobookEntity): String =
    book.workId?.takeIf { it.isNotBlank() }
        ?: book.mergeKey.takeIf { it.isNotBlank() }
        ?: book.id

private fun AudiobookEntity.asMatchCard(): GlobalSearchResult {
    val sourceId = sourceIdForUrl(sourceUrl)
    return GlobalSearchResult(
        title = title,
        author = author,
        narrator = narrator,
        mergeKey = mergeKey.ifBlank { MergeKey.keyFor(title, author) },
        coverImageUrl = coverImageUrl,
        durationSeconds = totalDurationSeconds.takeIf { it > 0L },
        sources = listOf(GlobalSearchSource(sourceId, sourceDisplayName(sourceId), sourceUrl)),
        language = language
    )
}
