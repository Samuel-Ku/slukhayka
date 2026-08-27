package com.slukhayka.audiobooks.data.facets

import com.slukhayka.audiobooks.data.db.AuthorAliasEntity
import com.slukhayka.audiobooks.data.db.AuthorFacetEntity
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.EditionFacetEntity
import com.slukhayka.audiobooks.data.db.GenreAssertionEntity
import com.slukhayka.audiobooks.data.db.GenreFacetEntity
import com.slukhayka.audiobooks.data.db.GenreSourceFacetRows
import com.slukhayka.audiobooks.data.db.WorkFacetEntity
import com.slukhayka.audiobooks.data.db.WorkFacetSeriesEntity
import com.slukhayka.audiobooks.data.db.WorkGenreEntity
import com.slukhayka.audiobooks.data.metadata.DurationSanity
import com.slukhayka.audiobooks.data.metadata.EditionDurationPolicy
import com.slukhayka.audiobooks.data.metadata.FacetDurationBucket

data class GenreFacetAssertion(
    val rawText: String,
    val sourceId: String,
    val observedAt: Long,
    val documentUpdatedAt: Long = observedAt
)

/** Canonical shared assertion input; the stable id is never re-derived from display text. */
data class CanonicalGenreFacetAssertion(
    val genreId: String,
    val rawText: String,
    val sourceId: String,
    val observedAt: Long,
    val assertionId: String,
    val documentUpdatedAt: Long
)

/** One Source-owned document replacement; an empty assertion list removes that Source's set. */
data class GenreSourceFacetReplacement(
    val sourceId: String,
    val documentUpdatedAt: Long,
    val assertions: List<CanonicalGenreFacetAssertion>
)

data class WorkFacetDelta(
    val workId: String,
    val genres: List<GenreFacetAssertion> = emptyList(),
    val genreSourceReplacements: List<GenreSourceFacetReplacement> = emptyList(),
    val canonicalAuthorId: String? = null,
    val seriesIds: Set<String> = emptySet(),
    val updatedAt: Long = 0
)

data class EditionFacetDelta(
    val editionId: String,
    val workId: String,
    val narratorId: String? = null,
    val language: String? = null,
    val durationSeconds: Long? = null,
    val durationBucketId: String? = null,
    val chapterCount: Int? = null,
    val isAbridged: Boolean? = null,
    val availabilityAvailable: Boolean? = null,
    val availabilityObservedAtMillis: Long? = null,
    val availabilityTtlSeconds: Long? = null,
    val updatedAt: Long = 0
)

data class AuthorFacetDelta(
    val authorId: String,
    val displayName: String,
    val aliases: List<AuthorAliasDelta> = emptyList(),
    val updatedAt: Long = 0
)

data class AuthorAliasDelta(val rawText: String, val sourceId: String, val observedAt: Long)

data class LocalFacetDelta(
    val work: WorkFacetDelta,
    val editions: List<EditionFacetDelta> = emptyList(),
    val authors: List<AuthorFacetDelta> = emptyList()
)

/** Frozen Wave-3 handoff: bounded facet batches commit through one Room transaction. */
interface LocalFacetWriter {
    suspend fun apply(deltas: List<LocalFacetDelta>)

    companion object {
        const val MAX_DELTAS_PER_BATCH = 100
        const val MAX_ASSERTIONS_PER_WORK = 24
        const val MAX_RELATED_ROWS_PER_DELTA = 24
    }
}

class RoomLocalFacetWriter(private val dao: AudiobookDao) : LocalFacetWriter {
    override suspend fun apply(deltas: List<LocalFacetDelta>) {
        require(deltas.size <= LocalFacetWriter.MAX_DELTAS_PER_BATCH) { "Facet batch is too large" }
        val workFacets = mutableListOf<WorkFacetEntity>()
        val workSeries = linkedSetOf<WorkFacetSeriesEntity>()
        val genreSources = mutableListOf<GenreSourceFacetRows>()
        val editionFacets = mutableListOf<EditionFacetEntity>()
        val authorFacets = mutableListOf<AuthorFacetEntity>()
        val authorAliases = mutableListOf<AuthorAliasEntity>()

        deltas.forEach { delta ->
            require(delta.work.workId.isNotBlank() && delta.work.workId.length <= 240)
            require(delta.work.genres.size <= LocalFacetWriter.MAX_ASSERTIONS_PER_WORK)
            require(delta.work.genreSourceReplacements.size <= LocalFacetWriter.MAX_RELATED_ROWS_PER_DELTA)
            require(delta.work.seriesIds.size <= LocalFacetWriter.MAX_RELATED_ROWS_PER_DELTA)
            require(delta.editions.size <= LocalFacetWriter.MAX_RELATED_ROWS_PER_DELTA)
            require(delta.authors.size <= LocalFacetWriter.MAX_RELATED_ROWS_PER_DELTA)
            require(delta.work.canonicalAuthorId == null || delta.work.canonicalAuthorId.length <= 80)
            require(delta.work.updatedAt >= 0)
            workFacets += WorkFacetEntity(
                workId = delta.work.workId,
                canonicalAuthorId = delta.work.canonicalAuthorId,
                updatedAt = delta.work.updatedAt
            )
            delta.work.seriesIds.forEach { seriesId ->
                require(seriesId.isNotBlank() && seriesId.length <= 80)
                workSeries += WorkFacetSeriesEntity(delta.work.workId, seriesId)
            }
            val localReplacements = delta.work.genres.groupBy { it.sourceId }.map { (sourceId, assertions) ->
                assertions.forEach { assertion ->
                    require(assertion.sourceId.isNotBlank() && assertion.sourceId.length <= 80)
                    require(assertion.rawText.length <= 240)
                    require(assertion.observedAt >= 0)
                    require(assertion.documentUpdatedAt >= 0)
                }
                val documentUpdatedAt = assertions.map { it.documentUpdatedAt }.distinct().singleOrNull()
                requireNotNull(documentUpdatedAt) { "One Source genre set must share one documentUpdatedAt" }
                GenreSourceFacetReplacement(
                    sourceId = sourceId,
                    documentUpdatedAt = documentUpdatedAt,
                    assertions = assertions.flatMap { assertion ->
                        GenreIdentity.fromSourceText(assertion.rawText).map { genre ->
                            CanonicalGenreFacetAssertion(
                                genreId = genre.id,
                                rawText = assertion.rawText,
                                sourceId = assertion.sourceId,
                                observedAt = assertion.observedAt,
                                assertionId = FacetIdentity.boundedId(
                                    "assertion",
                                    "${delta.work.workId}|${genre.id}|${assertion.sourceId}|${assertion.rawText}"
                                ),
                                documentUpdatedAt = assertion.documentUpdatedAt
                            )
                        }
                    }
                )
            }
            val replacements = localReplacements + delta.work.genreSourceReplacements
            require(replacements.map { it.sourceId }.distinct().size == replacements.size) {
                "One Work delta may replace a Source genre set only once"
            }
            require(replacements.sumOf { it.assertions.size } <= LocalFacetWriter.MAX_ASSERTIONS_PER_WORK)
            replacements.forEach { replacement ->
                require(replacement.sourceId.isNotBlank() && replacement.sourceId.length <= 80)
                require(replacement.documentUpdatedAt >= 0)
                val genres = linkedMapOf<String, GenreFacetEntity>()
                val memberships = linkedSetOf<WorkGenreEntity>()
                val assertions = linkedMapOf<String, GenreAssertionEntity>()
                replacement.assertions.forEach { assertion ->
                    require(assertion.sourceId == replacement.sourceId)
                    require(assertion.documentUpdatedAt == replacement.documentUpdatedAt)
                    require(assertion.genreId.isNotBlank() && assertion.genreId.length <= 80)
                    require(assertion.assertionId.isNotBlank() && assertion.assertionId.length <= 240)
                    require(assertion.sourceId.isNotBlank() && assertion.sourceId.length <= 80)
                    require(assertion.rawText.length <= 240)
                    require(assertion.observedAt >= 0)
                    val genre = requireNotNull(GenreIdentity.fromCanonical(assertion.genreId, assertion.rawText))
                    genres[genre.id] = GenreFacetEntity(
                        id = genre.id,
                        displayName = genre.label,
                        normalizedName = FacetIdentity.normalizedText(genre.label).orEmpty()
                    )
                    memberships += WorkGenreEntity(delta.work.workId, genre.id, replacement.sourceId)
                    val assertionRowId = FacetIdentity.boundedId(
                        "assertion-row",
                        "${delta.work.workId}|${replacement.sourceId}|${assertion.assertionId}|${genre.id}"
                    )
                    assertions[assertionRowId] = GenreAssertionEntity(
                        id = assertionRowId,
                        assertionId = assertion.assertionId,
                        workId = delta.work.workId,
                        genreId = genre.id,
                        rawText = assertion.rawText,
                        sourceId = assertion.sourceId,
                        observedAt = assertion.observedAt
                    )
                }
                genreSources += GenreSourceFacetRows(
                    workId = delta.work.workId,
                    sourceId = replacement.sourceId,
                    documentUpdatedAt = replacement.documentUpdatedAt,
                    genres = genres.values.toList(),
                    memberships = memberships.toList(),
                    assertions = assertions.values.toList()
                )
            }
            editionFacets += delta.editions.map { edition ->
                require(edition.workId == delta.work.workId)
                require(edition.editionId.isNotBlank() && edition.editionId.length <= 240)
                require(edition.narratorId == null || edition.narratorId.length <= 80)
                require(edition.language == null || edition.language.length <= 24)
                require(edition.durationBucketId == null || edition.durationBucketId.length <= 40)
                require(edition.updatedAt >= 0)
                val availabilityParts = listOf(
                    edition.availabilityAvailable,
                    edition.availabilityObservedAtMillis,
                    edition.availabilityTtlSeconds
                )
                require(availabilityParts.all { it == null } || availabilityParts.all { it != null }) {
                    "Edition availability must be entirely absent or entirely present"
                }
                require(edition.availabilityObservedAtMillis == null || edition.availabilityObservedAtMillis >= 0)
                require(
                    edition.availabilityTtlSeconds == null ||
                        edition.availabilityTtlSeconds in 1L..EditionAvailabilityPolicy.MAX_TTL_SECONDS
                )
                val plausibleDuration = edition.durationSeconds?.takeIf(DurationSanity::isPlausible)
                val durationBucketId = if (edition.durationSeconds != null) {
                    plausibleDuration?.let(EditionDurationPolicy::bucketFor)?.wireName
                } else {
                    edition.durationBucketId
                }
                EditionFacetEntity(
                    editionId = edition.editionId,
                    workId = edition.workId,
                    narratorId = edition.narratorId,
                    language = edition.language,
                    durationSeconds = plausibleDuration,
                    durationBucketId = durationBucketId,
                    chapterCount = edition.chapterCount?.takeIf { it > 0 },
                    isAbridged = edition.isAbridged,
                    availabilityAvailable = edition.availabilityAvailable,
                    availabilityObservedAtMillis = edition.availabilityObservedAtMillis,
                    availabilityTtlSeconds = edition.availabilityTtlSeconds,
                    updatedAt = edition.updatedAt
                )
            }
            delta.authors.forEach { author ->
                require(author.authorId.isNotBlank() && author.authorId.length <= 80)
                require(author.aliases.size <= LocalFacetWriter.MAX_RELATED_ROWS_PER_DELTA)
                require(author.displayName.length <= 120)
                require(author.updatedAt >= 0)
                val normalizedName = requireNotNull(FacetIdentity.normalizedText(author.displayName))
                authorFacets += AuthorFacetEntity(author.authorId, author.displayName, normalizedName, author.updatedAt)
                author.aliases.forEach { alias ->
                    require(alias.rawText.length <= 120)
                    require(alias.sourceId.isNotBlank() && alias.sourceId.length <= 80)
                    require(alias.observedAt >= 0)
                    FacetIdentity.normalizedText(alias.rawText)?.let { normalizedAlias ->
                        authorAliases += AuthorAliasEntity(
                            authorId = author.authorId,
                            normalizedAlias = normalizedAlias,
                            rawAlias = alias.rawText,
                            sourceId = alias.sourceId,
                            observedAt = alias.observedAt
                        )
                    }
                }
            }
        }
        dao.applyFacetRows(
            workFacets,
            workSeries.toList(),
            genreSources,
            editionFacets,
            authorFacets,
            authorAliases
        )
    }
}
