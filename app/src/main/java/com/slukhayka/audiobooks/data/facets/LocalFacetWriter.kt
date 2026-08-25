package com.slukhayka.audiobooks.data.facets

import com.slukhayka.audiobooks.data.db.AuthorAliasEntity
import com.slukhayka.audiobooks.data.db.AuthorFacetEntity
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.EditionFacetEntity
import com.slukhayka.audiobooks.data.db.GenreAssertionEntity
import com.slukhayka.audiobooks.data.db.GenreFacetEntity
import com.slukhayka.audiobooks.data.db.WorkFacetEntity
import com.slukhayka.audiobooks.data.db.WorkFacetSeriesEntity
import com.slukhayka.audiobooks.data.db.WorkGenreEntity

data class GenreFacetAssertion(val rawText: String, val sourceId: String, val observedAt: Long)

data class WorkFacetDelta(
    val workId: String,
    val genres: List<GenreFacetAssertion> = emptyList(),
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
        val genreFacets = linkedMapOf<String, GenreFacetEntity>()
        val workSeries = linkedSetOf<WorkFacetSeriesEntity>()
        val workGenres = linkedSetOf<WorkGenreEntity>()
        val genreAssertions = linkedMapOf<String, GenreAssertionEntity>()
        val editionFacets = mutableListOf<EditionFacetEntity>()
        val authorFacets = mutableListOf<AuthorFacetEntity>()
        val authorAliases = mutableListOf<AuthorAliasEntity>()

        deltas.forEach { delta ->
            require(delta.work.workId.isNotBlank() && delta.work.workId.length <= 240)
            require(delta.work.genres.size <= LocalFacetWriter.MAX_ASSERTIONS_PER_WORK)
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
            delta.work.genres.forEach { assertion ->
                require(assertion.sourceId.isNotBlank() && assertion.sourceId.length <= 80)
                require(assertion.rawText.length <= 240)
                require(assertion.observedAt >= 0)
                GenreIdentity.fromSourceText(assertion.rawText).forEach { genre ->
                    genreFacets[genre.id] = GenreFacetEntity(
                        id = genre.id,
                        displayName = genre.label,
                        normalizedName = FacetIdentity.normalizedText(genre.label).orEmpty()
                    )
                    workGenres += WorkGenreEntity(delta.work.workId, genre.id)
                    val assertionId = FacetIdentity.boundedId(
                        "assertion",
                        "${delta.work.workId}|${genre.id}|${assertion.sourceId}|${assertion.rawText}"
                    )
                    genreAssertions[assertionId] = GenreAssertionEntity(
                        id = assertionId,
                        workId = delta.work.workId,
                        genreId = genre.id,
                        rawText = assertion.rawText,
                        sourceId = assertion.sourceId,
                        observedAt = assertion.observedAt
                    )
                }
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
                EditionFacetEntity(
                    editionId = edition.editionId,
                    workId = edition.workId,
                    narratorId = edition.narratorId,
                    language = edition.language,
                    durationSeconds = edition.durationSeconds?.takeIf { it > 0 },
                    durationBucketId = edition.durationBucketId,
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
            genreFacets.values.toList(),
            workGenres.toList(),
            genreAssertions.values.toList(),
            editionFacets,
            authorFacets,
            authorAliases
        )
    }
}
