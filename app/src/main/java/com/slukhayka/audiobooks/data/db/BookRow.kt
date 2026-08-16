package com.slukhayka.audiobooks.data.db

/**
 * ADR-0009 — the JOINed read shape of the split book row. Room does not
 * hydrate `@Ignore` projections from a result set, so every DAO read of
 * `audiobooks` returns this row instead: the persisted per-row metadata PLUS
 * the projections the v15 contract step moved to their owning tables —
 * series / workId / mergeKey from `works`, isFavorite / createdAt /
 * downloadProgress from `library_entries`, preferredSpeed from the Listening
 * State row (`playback_progress`).
 *
 * The modules map it back to [AudiobookEntity] ([toAudiobookEntity]) at their
 * read boundary, so the UI, the player and the widgets keep reading one
 * shaped row while the columns live where they belong.
 */
data class BookRow(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val coverDrawableRes: Int,
    val coverImageUrl: String? = null,
    val genre: String,
    val sourceUrl: String,
    val isDownloaded: Boolean = false,
    val totalDurationSeconds: Long = 0L,
    val totalChapters: Int = 0,
    val rating: Float = 4.9f,
    val sourceTreeUri: String? = null,
    // --- ADR-0009 projections (columns moved out of the audiobooks row) ----
    val seriesTitle: String? = null,
    val seriesUrl: String? = null,
    val seriesIndex: Int? = null,
    val preferredSpeed: Float? = null,
    val createdAt: Long = 0L,
    val isFavorite: Boolean = false,
    val downloadProgress: Float = 0f,
    // Nullable: the LEFT JOIN yields NULL for a book without a Works row.
    val mergeKey: String? = null,
    val workId: String? = null
) {
    /** Maps the JOINed row onto the [AudiobookEntity] projection carrier. */
    fun toAudiobookEntity(): AudiobookEntity = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        description = description,
        coverDrawableRes = coverDrawableRes,
        coverImageUrl = coverImageUrl,
        genre = genre,
        sourceUrl = sourceUrl,
        isDownloaded = isDownloaded,
        totalDurationSeconds = totalDurationSeconds,
        totalChapters = totalChapters,
        rating = rating,
        sourceTreeUri = sourceTreeUri
    ).also {
        it.seriesTitle = seriesTitle
        it.seriesUrl = seriesUrl
        it.seriesIndex = seriesIndex
        it.preferredSpeed = preferredSpeed
        it.createdAt = createdAt
        it.isFavorite = isFavorite
        it.downloadProgress = downloadProgress
        it.mergeKey = mergeKey ?: ""
        it.workId = workId
    }
}

/** The reverse mapping — the in-memory entity back to a [BookRow] (the fake DAO). */
fun AudiobookEntity.toBookRow(): BookRow = BookRow(
    id = id,
    title = title,
    author = author,
    narrator = narrator,
    description = description,
    coverDrawableRes = coverDrawableRes,
    coverImageUrl = coverImageUrl,
    genre = genre,
    sourceUrl = sourceUrl,
    isDownloaded = isDownloaded,
    totalDurationSeconds = totalDurationSeconds,
    totalChapters = totalChapters,
    rating = rating,
    sourceTreeUri = sourceTreeUri,
    seriesTitle = seriesTitle,
    seriesUrl = seriesUrl,
    seriesIndex = seriesIndex,
    preferredSpeed = preferredSpeed,
    createdAt = createdAt,
    isFavorite = isFavorite,
    downloadProgress = downloadProgress,
    mergeKey = mergeKey,
    workId = workId
)
