package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.Index

/** Derived Work-level discovery projection; never replaces the Work row. */
@Entity(
    tableName = "work_facets",
    indices = [Index("canonicalAuthorId")],
    primaryKeys = ["workId"]
)
data class WorkFacetEntity(
    val workId: String,
    val canonicalAuthorId: String?,
    val updatedAt: Long
)

/** A Work may belong to several Series; the projection preserves that shape. */
@Entity(
    tableName = "work_facet_series",
    primaryKeys = ["workId", "seriesId"],
    indices = [Index("seriesId")]
)
data class WorkFacetSeriesEntity(val workId: String, val seriesId: String)

/** Stable display dictionary for normalized genre ids. */
@Entity(tableName = "genre_facets", primaryKeys = ["id"])
data class GenreFacetEntity(val id: String, val displayName: String, val normalizedName: String)

/** Indexed Work↔genre read relation used by the Paging query. */
@Entity(
    tableName = "work_genres",
    primaryKeys = ["workId", "genreId"],
    indices = [Index("genreId")]
)
data class WorkGenreEntity(val workId: String, val genreId: String)

/** Raw provenance-bearing input retained beside its derived genre relation. */
@Entity(
    tableName = "genre_assertions",
    primaryKeys = ["id"],
    indices = [Index("workId"), Index("genreId"), Index("sourceId")]
)
data class GenreAssertionEntity(
    val id: String,
    val workId: String,
    val genreId: String,
    val rawText: String,
    val sourceId: String,
    val observedAt: Long
)

/** Additive Edition-level shape frozen for duration/narrator filtering lanes. */
@Entity(
    tableName = "edition_facets",
    primaryKeys = ["editionId"],
    indices = [Index("workId"), Index("narratorId"), Index("durationBucketId"), Index("language")]
)
data class EditionFacetEntity(
    val editionId: String,
    val workId: String,
    val narratorId: String?,
    val language: String?,
    val durationSeconds: Long?,
    val durationBucketId: String?,
    val chapterCount: Int?,
    val isAbridged: Boolean?,
    val availabilityAvailable: Boolean?,
    val availabilityObservedAtMillis: Long?,
    val availabilityTtlSeconds: Long?,
    val updatedAt: Long
)

/** Canonical author dictionary prepared for #306 without author-search behavior. */
@Entity(tableName = "author_facets", primaryKeys = ["id"], indices = [Index("normalizedName")])
data class AuthorFacetEntity(val id: String, val displayName: String, val normalizedName: String, val updatedAt: Long)

/** Bounded provenance-bearing alias relation prepared for the author FTS lane. */
@Entity(
    tableName = "author_aliases",
    primaryKeys = ["authorId", "normalizedAlias", "sourceId"],
    indices = [Index("normalizedAlias"), Index("sourceId")]
)
data class AuthorAliasEntity(
    val authorId: String,
    val normalizedAlias: String,
    val rawAlias: String,
    val sourceId: String,
    val observedAt: Long
)

/** Bounded options exposed to Compose; the full Work catalogue is never collected. */
data class GenreFacetOption(val id: String, val label: String, val workCount: Int)
