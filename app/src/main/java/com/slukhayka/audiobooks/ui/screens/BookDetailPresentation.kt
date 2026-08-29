package com.slukhayka.audiobooks.ui.screens

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.reviews.CombinedAverage
import com.slukhayka.audiobooks.data.reviews.CombinedAverageResult
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator

/** The one listener-facing metadata hierarchy for a book detail page. */
data class BookDetailPresentation(
    val title: String,
    val author: String,
    val description: String,
    val narrator: String,
    val genre: String,
    val totalDurationSeconds: Long,
    val totalChapters: Int,
    val seriesTitle: String?,
    val seriesUrl: String?,
    val seriesIndex: Int?,
    val sourceHeading: String,
    val sources: List<BookDetailSourcePresentation>,
    val combinedAverage: CombinedAverageResult?
)

data class BookDetailSourcePresentation(
    val sourceId: String,
    val name: String,
    val url: String,
    val streamOnly: Boolean,
    val rating: Double?,
    val isCurrent: Boolean,
    val selectable: Boolean,
    val differingDescription: String?,
    val differingNarrator: String?,
    val differingGenres: List<String>
)

fun bookDetailPresentation(
    book: AudiobookEntity,
    sourceProfiles: List<LibraryEntries.SourceProfile>,
    playableSources: List<SourceCatalog.WorkSourceRow>,
    listenerRatings: List<Int> = emptyList()
): BookDetailPresentation {
    val description = MetadataAssertions.pickBestBlurb(
        listOf(book.description) + sourceProfiles.map { it.description }
    ).orEmpty()
    val canonicalNarrator = MetadataAssertions.normalizeClaimedText(book.displayNarrator).orEmpty()
    val canonicalGenres = genreTokens(book.genre)
    val sources = playableSources.map { source ->
        val isCurrent = source.url == book.sourceUrl
        val profile = sourceProfiles.firstOrNull { it.url == source.url }
        val profileDescription = MetadataAssertions.normalizeDescription(profile?.description)
        val profileNarrator = MetadataAssertions.normalizeClaimedText(profile?.narrator).orEmpty()
        val profileGenres = profile?.genres.orEmpty().map(String::trim).filter(String::isNotBlank)
        BookDetailSourcePresentation(
            sourceId = source.sourceId,
            name = source.sourceName,
            url = source.url,
            streamOnly = source.streamOnly,
            rating = profile?.rating ?: book.rating.takeIf { isCurrent && it > 0f }?.toDouble(),
            isCurrent = isCurrent,
            // The source block is informational. Playback chooses the best
            // source through the shared coordinator; a detail-page tap must
            // never become an implicit source switch.
            selectable = false,
            differingDescription = profileDescription.takeIf {
                it.isNotBlank() && !it.equals(description, ignoreCase = true)
            },
            differingNarrator = profileNarrator.takeIf {
                it.isNotBlank() && !it.equals(canonicalNarrator, ignoreCase = true)
            },
            differingGenres = profileGenres.takeUnless {
                genreTokens(it.joinToString(" · ")) == canonicalGenres
            }.orEmpty()
        )
    }
    return BookDetailPresentation(
        title = book.title,
        author = book.displayAuthor,
        description = description,
        narrator = book.displayNarrator,
        genre = book.genre,
        totalDurationSeconds = book.totalDurationSeconds,
        totalChapters = book.totalChapters,
        seriesTitle = book.seriesTitle,
        seriesUrl = book.seriesUrl,
        seriesIndex = book.seriesIndex,
        sourceHeading = when (sources.size) {
            0 -> ""
            1 -> "Джерело"
            else -> "Джерела"
        },
        sources = sources,
        combinedAverage = CombinedAverage.average(
            sourceRatings = sourceProfiles.map { it.rating },
            listenerRatings = listenerRatings
        )
    )
}

private fun genreTokens(value: String): Set<String> = value
    .split(Regex("""\s*[·,;]\s*"""))
    .map { it.trim().lowercase() }
    .filter { it.isNotBlank() }
    .toSet()
