package com.slukhayka.audiobooks.data.entries

import android.util.Log
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import kotlinx.coroutines.flow.map
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.source.FourReadAdapter
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADR-0002 — Library Entries: the deep module that owns Work-level library
 * state — the library flows, book reads, per-source aggregation and the
 * mutations that change what the library IS: cascading delete (level-2, files
 * removed), remove-from-library (level-1, files kept), favourite and metadata
 * back-fill. Tombstones are written exactly as before the split, so removed
 * Works never reappear after a catalogue sync.
 *
 * The module performs NO network I/O on construction; the per-source page
 * fetches ([fetchSourceProfiles], [refreshBookCoverAndDetails]) only touch the
 * network when the caller invokes them.
 */
class LibraryEntries(
    private val dao: AudiobookDao,
    private val sourceAdapters: List<SourceAdapter>
) {

    // The 4read transport/parser behind the book-detail refresh (cover +
    // metadata back-fill). Same adapter lookup the other modules use.
    private val fourReadAdapter: SourceAdapter =
        sourceAdapters.firstOrNull { it.sourceId == "4read" } ?: FourReadAdapter()

    // ---------------------------------------------------------------------
    // Library flows
    // ---------------------------------------------------------------------

    // ADR-0009: the DAO reads return the JOINed [BookRow]; the module maps it
    // back to the single shaped [AudiobookEntity] the UI keeps reading.
    val allBooks: Flow<List<AudiobookEntity>> =
        dao.getAllAudiobooks().map { rows -> rows.map { it.toAudiobookEntity() } }
    val downloadedBooks: Flow<List<AudiobookEntity>> =
        dao.getDownloadedAudiobooks().map { rows -> rows.map { it.toAudiobookEntity() } }
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAllBookmarks()
    val recentProgress: Flow<List<PlaybackProgressEntity>> = dao.getAllPlaybackProgress()

    // Wayfinder #39: every chapter, for the library's cumulative position and
    // real total durations. One query; recomputed in memory on change.
    val allChapters: Flow<List<ChapterEntity>> = dao.getAllChapters()

    // ---------------------------------------------------------------------
    // Book reads
    // ---------------------------------------------------------------------

    /** Keep joined fields in equality until UI StateFlow/Compose has observed them. */
    fun observeBookRow(bookId: String): Flow<BookRow?> = dao.observeAudiobookById(bookId)

    fun observeBook(bookId: String): Flow<AudiobookEntity?> =
        observeBookRow(bookId).map { it?.toAudiobookEntity() }
    suspend fun getBookSync(bookId: String): AudiobookEntity? =
        dao.getAudiobookById(bookId)?.toAudiobookEntity()

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = dao.getChaptersForBook(bookId)

    fun observeSources(bookId: String): Flow<List<SourceEntity>> = dao.getSourcesForBook(bookId)
    suspend fun getSourcesForBook(bookId: String): List<SourceEntity> = dao.getSourcesForBookSync(bookId)

    // ---------------------------------------------------------------------
    // Favourites
    // ---------------------------------------------------------------------

    suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {
        dao.setFavorite(bookId, isFavorite)
    }

    fun getFavoriteAudiobooks(): Flow<List<AudiobookEntity>> =
        dao.getFavoriteAudiobooks().map { rows -> rows.map { it.toAudiobookEntity() } }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    /**
     * Back-fills real page metadata (author/narrator/genre/rating) onto the
     * audiobooks row. Series is NOT part of it anymore (ADR-0009): it belongs
     * to the Work, written through [updateSeries].
     */
    suspend fun updateBookMetadata(
        bookId: String,
        author: String? = null,
        narrator: String? = null,
        genre: String? = null,
        rating: Float? = null
    ) = dao.updateBookMetadata(bookId, author, narrator, genre, rating)

    /** Series belongs to the Work row (ADR-0009); the caller resolves the delta. */
    suspend fun updateSeries(
        bookId: String,
        seriesTitle: String?,
        seriesUrl: String?,
        seriesIndex: Int?
    ) = dao.updateSeriesFields(bookId, seriesTitle, seriesUrl, seriesIndex)

    /** What one Source asserts about a Work, used to resolve its presentation. */
    data class SourceProfile(
        val sourceId: String,
        val sourceName: String,
        val url: String,
        val description: String = "",
        val rating: Double? = null,
        val narrator: String = "",
        val genres: List<String> = emptyList(),
        /** Spec-40 #282 — visitors' comments parsed from the same page fetch. */
        val visitorComments: List<String> = emptyList()
    )

    /**
     * Per-Source assertions for a Work. Best-effort per Source: a failed page
     * contributes no claim, while the remaining claims can still resolve the
     * canonical book-detail presentation.
     */
    /**
     * #266 — lazy description backfill on card open: when the book's stored
     * description is blank or the template fallback, take the best real
     * blurb among the Work's carrier profiles and persist it locally.
     * Returns true when a row was updated. Best-effort: never throws.
     */
    suspend fun fillMissingDescriptionFromProfiles(
        bookId: String,
        profileBlurbs: List<String>
    ): Boolean {
        return try {
            if (profileBlurbs.isEmpty()) return false
            val row = dao.getAudiobookById(bookId) ?: return false
            val current = com.slukhayka.audiobooks.data.metadata.MetadataAssertions
                .normalizeDescription(row.description)
            val needsFill = current.isBlank() ||
                current.startsWith(com.slukhayka.audiobooks.data.metadata.MetadataAssertions.FALLBACK_DESCRIPTION_PREFIX)
            if (!needsFill) return false
            val best = com.slukhayka.audiobooks.data.metadata.MetadataAssertions
                .pickBestBlurb(profileBlurbs) ?: return false
            dao.updateBookDescription(bookId, best)
            true
        } catch (e: Exception) {
            Log.w("LibraryEntries", "fillMissingDescriptionFromProfiles failed", e)
            false
        }
    }

    suspend fun fetchSourceProfiles(bookId: String): List<SourceProfile> =
        withContext(Dispatchers.IO) {
            val sources = dao.getSourcesForBookSync(bookId)
            sources.mapNotNull { source ->
                val adapter = sourceAdapters.firstOrNull { it.sourceId == source.type }
                    ?: return@mapNotNull null
                try {
                    val detail = adapter.fetchBookPage(source.url)
                    // A page that yielded nothing (blank title AND no chapters)
                    // is a failure, not an empty block.
                    if (detail.title.isBlank() && detail.chapters.isEmpty()) return@mapNotNull null
                    SourceProfile(
                        sourceId = source.type,
                        sourceName = sourceDisplayName(source.type),
                        url = source.url,
                        description = detail.description,
                        rating = detail.rating,
                        narrator = detail.narrator,
                        genres = detail.genres,
                        visitorComments = detail.visitorComments
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    // ---------------------------------------------------------------------
    // Deletion (spec #8 T2/T3, wayfinder #28)
    // ---------------------------------------------------------------------

    /**
     * Cascading book deletion (spec #8 tickets T2/T3): removes local audio
     * files, chapters, bookmarks, playback progress and finally the book
     * itself. The entities have no FK constraints, so the cascade is
     * coordinated here.
     */
    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        // Durable tombstone (v11, wayfinder #55 Q8): the 4read catalogue re-
        // lists deleted books on every sync, so without a durable marker the
        // next sync would resurrect the deleted book after a restart.
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
        // ADR-0007: the physical copies live on the TRACK rows.
        dao.getTracksForBookSync(bookId).forEach { track ->
            track.localFilePath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.w("LibraryEntries", "Failed to delete file $path", e)
                }
            }
        }
        dao.deleteChaptersForBook(bookId)
        dao.deleteBookmarksForBook(bookId)
        dao.deletePlaybackProgressForBook(bookId)
        // Tracks FIRST — deleteTracksForBook joins the sources table, so the
        // source rows must still exist while it runs (otherwise orphaned track
        // rows would survive the cascade).
        dao.deleteTracksForBook(bookId)
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
        // ADR-0009: the Library Entry row leaves with the book (the Work row
        // stays — it is the browse layer's identity, not the user's copy).
        dao.deleteLibraryEntry(bookId)
        dao.deleteAudiobook(bookId)
    }

    /**
     * Level-1 deletion — "прибрати з медіатеки" (wayfinder #28): the Room rows
     * (book, chapters, bookmarks, progress) are removed but downloaded audio
     * files stay on disk, so nothing physical is lost. The book can be
     * re-added from the catalogue.
     */
    suspend fun removeFromLibrary(bookId: String) = withContext(Dispatchers.IO) {
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
        dao.deleteChaptersForBook(bookId)
        dao.deleteBookmarksForBook(bookId)
        dao.deletePlaybackProgressForBook(bookId)
        // Tracks first (same reason as deleteBook — the track delete joins
        // sources).
        dao.deleteTracksForBook(bookId)
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
        // ADR-0009: the Library Entry row leaves with the book.
        dao.deleteLibraryEntry(bookId)
        dao.deleteAudiobook(bookId)
    }

    // ---------------------------------------------------------------------
    // Book-detail refresh (spec-14 T5)
    // ---------------------------------------------------------------------

    /**
     * Back-fills a book's cover and real metadata from its source page on
     * every book-page open. The adapter owns the page parse; the module only
     * persists what the seam's [SourceBookDetail] carries. Never overwrites
     * existing (seeded) chapters with live-page ones — that duplicated rows
     * on every book-detail open.
     */
    suspend fun refreshBookCoverAndDetails(bookId: String) = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext
        val chapters = dao.getChaptersListForBook(bookId)

        // 1. We skip audio metadata (embedded picture) extraction because MediaMetadataRetriever
        // frequently causes "Media Quality Service not found" and "getEmbeddedPicture failed" errors
        // on emulators and some devices, and is extremely slow for network streams.
        var audioCoverUrl: String? = null

        if (audioCoverUrl != null) {
            dao.updateCoverImageUrl(bookId, audioCoverUrl)
            return@withContext
        }

        // 2. Fall back to book's webpage (spec-14 T5: the adapter owns the
        // page parse; the module persists what the seam provides). All claim
        // normalization / deltas come from the one MetadataAssertions module
        // (ADR-0004) — never re-derived here.
        if (book.sourceUrl.isNotBlank()) {
            // Spec-24 T9 (#170): the page-open heal dispatches to the book's
            // OWN source adapter — it was 4read-hard-coded, so sound-books
            // (and every non-4read) row never back-filled its cover. The one
            // pure sourceIdForUrl dispatch; 4read stays the fallback for
            // unknown urls.
            val sourceId = sourceIdForUrl(book.sourceUrl)
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
                ?: fourReadAdapter
            val detail = adapter.fetchBookPage(book.sourceUrl)
            // Cover applies only when the claim is non-blank — never clears a
            // stored cover with an absent one.
            MetadataAssertions.coverDelta(detail.coverImageUrl)?.let { cover ->
                dao.updateCoverImageUrl(bookId, cover)
            }
            // Real metadata (author/narrator/genre/duration/rating/series) is
            // back-filled on EVERY book-page open — the catalogue seed only
            // ever had placeholders, and a book may already carry them from a
            // previous session, so gating on chapters.isEmpty() would leave
            // "4read.org" / "4:00:00" forever. Brand placeholder claims are
            // scrubbed to absent at write time (ADR-0004).
            val author = MetadataAssertions.normalizeClaimedText(detail.author)
            val narrator = MetadataAssertions.normalizeClaimedText(detail.narrator)
            val genres = detail.genres.joinToString(" · ").ifBlank { null }
            val rating = detail.rating?.toFloat()
            // Series applies only when its URL changed; unchanged → nulls
            // (COALESCE keeps the stored series).
            val series = MetadataAssertions.seriesDelta(
                existingSeriesUrl = book.seriesUrl,
                claimedUrl = detail.series?.url,
                claimedTitle = detail.series?.name,
                claimedIndex = detail.series?.position
            )
            val knownDuration = MetadataAssertions.durationDelta(
                book.totalDurationSeconds,
                detail.totalDurationSeconds
            )
            if (detail.totalDurationSeconds != null || author != null ||
                narrator != null || genres != null ||
                rating != null || series != null
            ) {
                dao.updateBookStats(
                    bookId,
                    chapters.size.takeIf { it > 0 } ?: detail.chapters.size,
                    knownDuration
                )
                dao.updateBookMetadata(
                    bookId,
                    author = author,
                    narrator = narrator,
                    genre = genres,
                    rating = rating
                )
                // ADR-0009: series persists on the Work row, not audiobooks.
                if (series != null) {
                    dao.updateSeriesFields(bookId, series.title, series.url, series.index)
                }
            }
            // Same guard as SourceCatalog.getChaptersList: never overwrite
            // existing (seeded) chapters with live-page ones -- that
            // duplicated rows on every book-detail open. ADR-0007: the
            // Edition's logical chapters AND the 4read source's tracks are
            // materialized together (one module, one id format — a mixed
            // `ch`/`ch_` format used to duplicate the whole chapter list).
            if (chapters.isEmpty() && detail.chapters.isNotEmpty()) {
                val edition = dao.getEditionForWork(bookId)
                val editionId = edition?.id ?: com.slukhayka.audiobooks.data.EditionId.forBook(
                    book.mergeKey ?: "", bookId, book.narrator
                )
                if (edition == null) {
                    dao.insertEdition(
                        com.slukhayka.audiobooks.data.db.EditionEntity(
                            id = editionId,
                            workId = bookId,
                            narrator = book.narrator,
                            totalChapters = detail.chapters.size,
                            totalDurationSeconds = detail.totalDurationSeconds ?: 0L
                        )
                    )
                }
                val source = dao.getSourcesForBookSync(bookId).firstOrNull { it.type == sourceId }
                    ?: com.slukhayka.audiobooks.data.db.SourceEntity(
                        id = "$sourceId-$editionId",
                        bookId = bookId,
                        editionId = editionId,
                        type = sourceId,
                        url = book.sourceUrl,
                        streamOnly = com.slukhayka.audiobooks.data.source.streamOnlyFor(sourceId),
                        addedAt = System.currentTimeMillis()
                    ).also { dao.insertSources(listOf(it)) }
                val materialized = MetadataAssertions.materializeChaptersAndTracks(
                    editionId = editionId,
                    sourceId = source.id,
                    bookId = bookId,
                    bookTitle = book.title,
                    chapters = detail.chapters
                )
                dao.insertChapters(materialized.chapters)
                dao.insertTracks(materialized.tracks)
            }
        }
    }
}
