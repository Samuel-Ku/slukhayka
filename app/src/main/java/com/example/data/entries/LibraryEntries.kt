package com.example.data.entries

import android.util.Log
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.db.SourceEntity
import com.example.data.db.TombstoneEntity
import com.example.data.metadata.MetadataAssertions
import com.example.data.source.FourReadAdapter
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBookDetail
import com.example.data.source.sourceDisplayName
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

    val allBooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()
    val downloadedBooks: Flow<List<AudiobookEntity>> = dao.getDownloadedAudiobooks()
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAllBookmarks()
    val recentProgress: Flow<List<PlaybackProgressEntity>> = dao.getAllPlaybackProgress()

    // Wayfinder #39: every chapter, for the library's cumulative position and
    // real total durations. One query; recomputed in memory on change.
    val allChapters: Flow<List<ChapterEntity>> = dao.getAllChapters()

    // ---------------------------------------------------------------------
    // Book reads
    // ---------------------------------------------------------------------

    fun observeBook(bookId: String): Flow<AudiobookEntity?> = dao.observeAudiobookById(bookId)
    suspend fun getBookSync(bookId: String): AudiobookEntity? = dao.getAudiobookById(bookId)

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = dao.getChaptersForBook(bookId)

    fun observeSources(bookId: String): Flow<List<SourceEntity>> = dao.getSourcesForBook(bookId)
    suspend fun getSourcesForBook(bookId: String): List<SourceEntity> = dao.getSourcesForBookSync(bookId)

    // ---------------------------------------------------------------------
    // Favourites
    // ---------------------------------------------------------------------

    suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {
        dao.setFavorite(bookId, isFavorite)
    }

    fun getFavoriteAudiobooks(): Flow<List<AudiobookEntity>> = dao.getFavoriteAudiobooks()

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    /** Back-fills real page metadata (author/narrator/genre/rating/series). */
    suspend fun updateBookMetadata(
        bookId: String,
        author: String? = null,
        narrator: String? = null,
        genre: String? = null,
        rating: Float? = null,
        seriesTitle: String? = null,
        seriesIndex: Int? = null,
        seriesUrl: String? = null
    ) = dao.updateBookMetadata(bookId, author, narrator, genre, rating, seriesTitle, seriesIndex, seriesUrl)

    /**
     * Spec-15 T5 — what ONE source says about a Work, for the labelled
     * per-source blocks on the book detail page. Built from that source's own
     * page through its adapter ([SourceBookDetail]); the aggregate profile of
     * the Work stays on the primary [AudiobookEntity].
     */
    data class SourceProfile(
        val sourceId: String,
        val sourceName: String,
        val url: String,
        val description: String = "",
        val rating: Double? = null,
        val narrator: String = "",
        val genres: List<String> = emptyList()
    )

    /**
     * Spec-15 T5 — the per-source aggregation of a Work's detail: for every
     * Source row carrying the book, fetch that source's page through its own
     * adapter and render what IT says (description, rating, narrator, genres).
     * Best-effort per source — a failing source simply contributes no block,
     * never a blank page. Uses the existing adapter seam, no third parser.
     */
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
                        genres = detail.genres
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
        dao.getChaptersListForBook(bookId).forEach { chapter ->
            chapter.localFilePath?.let { path ->
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
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
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
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
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
            val detail = fourReadAdapter.fetchBookPage(book.sourceUrl)
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
                    rating = rating,
                    seriesTitle = series?.title,
                    seriesIndex = series?.index,
                    seriesUrl = series?.url
                )
            }
            // Same guard as SourceCatalog.getChaptersList: never overwrite
            // existing (seeded) chapters with live-page ones -- that
            // duplicated rows on every book-detail open.
            if (chapters.isEmpty() && detail.chapters.isNotEmpty()) {
                // The same id format as every other site ("_ch_") so a
                // concurrent fetch-then-insert (e.g. an offline Download
                // racing this refresh) produces identical rows and
                // @Insert(REPLACE) dedupes them — a mixed `ch`/`ch_` format
                // used to duplicate the whole chapter list.
                dao.insertChapters(
                    MetadataAssertions.materializeChapters(bookId, book.title, detail.chapters)
                )
            }
        }
    }
}
