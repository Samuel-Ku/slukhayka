package com.slukhayka.audiobooks.data.reviews

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.metadata.PopularityAssertionPolicy

/**
 * #738 / ADR-0022 — the local evidence of one owned Work for the library
 * rating: the owned book itself, the source ratings observed for it (never a
 * default/placeholder value) and the listener ratings known locally. A pool
 * may be empty; the combined average decides honestly whether any vote exists.
 */
data class LibraryRatingEvidence(
    val book: AudiobookEntity,
    val workKey: String,
    val sourceRatings: List<Double?>,
    val listenerRatings: List<Int>
)

/** One ranked row of the library rating: only Works with real votes appear. */
data class LibraryRating(
    val book: AudiobookEntity,
    val workKey: String,
    val average: Double,
    val count: Int
)

/**
 * #738 — the library rating rule. It reuses the ONE honest combined average
 * (ADR-0022): a Work with no source rating and no listener rating is ABSENT,
 * never a fabricated zero. Ordering is deterministic: higher average first,
 * then more votes, then title, then key.
 */
object LibraryRatingRanking {

    fun rank(evidence: List<LibraryRatingEvidence>): List<LibraryRating> =
        evidence.mapNotNull { item ->
            val combined = CombinedAverage.average(item.sourceRatings, item.listenerRatings)
                ?: return@mapNotNull null
            LibraryRating(
                book = item.book,
                workKey = item.workKey,
                average = combined.value,
                count = combined.count
            )
        }.sortedWith(
            compareByDescending<LibraryRating> { it.average }
                .thenByDescending { it.count }
                .thenBy { it.book.title.lowercase() }
                .thenBy { it.workKey }
        )
}

/**
 * #738 — assembles the local evidence from rows the device already holds:
 * owned books (the Медіатека) and the persisted source-rating assertions.
 * The assertion's raw claim is used as-is — a book with no assertion has no
 * source vote, and the `audiobooks.rating` display column is deliberately NOT
 * a source of truth here (it carries a placeholder for local imports).
 *
 * Listener ratings arrive from the local projection (#739); an empty map
 * keeps the row source-only until then.
 */
fun libraryRatingEvidence(
    books: List<AudiobookEntity>,
    ratingAssertions: List<PopularityAssertionEntity>,
    listenerRatingsByWork: Map<String, List<Int>> = emptyMap()
): List<LibraryRatingEvidence> {
    val ratingsByWork = ratingAssertions
        .groupBy { it.mergeKey }
        .mapValues { (_, rows) -> rows.map { PopularityAssertionPolicy.ratingValue(it.rawValue) } }
    return books
        .groupBy { it.mergeKey.ifBlank { it.workId.orEmpty().ifBlank { it.id } } }
        .map { (key, members) ->
            LibraryRatingEvidence(
                book = members.first(),
                workKey = key,
                sourceRatings = ratingsByWork[key].orEmpty(),
                listenerRatings = listenerRatingsByWork[key].orEmpty()
            )
        }
}
