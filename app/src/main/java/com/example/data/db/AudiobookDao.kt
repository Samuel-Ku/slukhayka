package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM audiobooks ORDER BY title ASC")
    fun getAllAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE isDownloaded = 1 ORDER BY title ASC")
    fun getDownloadedAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun getAudiobookById(id: String): AudiobookEntity?

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    fun observeAudiobookById(id: String): Flow<AudiobookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiobooks(books: List<AudiobookEntity>)

    @Query("UPDATE audiobooks SET isDownloaded = :isDownloaded, downloadProgress = :progress WHERE id = :bookId")
    suspend fun updateDownloadState(bookId: String, isDownloaded: Boolean, progress: Float)

    @Query("UPDATE audiobooks SET seriesTitle = :seriesTitle, seriesUrl = :seriesUrl, seriesIndex = :seriesIndex WHERE id = :bookId")
    suspend fun updateSeriesFields(bookId: String, seriesTitle: String?, seriesUrl: String?, seriesIndex: Int?)

    @Query("UPDATE audiobooks SET preferredSpeed = :speed WHERE id = :bookId")
    suspend fun updatePreferredSpeed(bookId: String, speed: Float?)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersListForBook(bookId: String): List<ChapterEntity>

    // Wayfinder #39: every chapter of every book, so the library can compute
    // cumulative positions and real total durations for its cards and sorts.
    @Query("SELECT * FROM chapters")
    fun getAllChapters(): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET isDownloaded = :isDownloaded, localFilePath = :filePath WHERE id = :chapterId")
    suspend fun updateChapterDownloadState(chapterId: String, isDownloaded: Boolean, filePath: String?)

    /** Real chapter duration discovered during playback (replaces placeholder 0). */
    @Query("UPDATE chapters SET durationSeconds = :durationSeconds WHERE id = :chapterId")
    suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long)

    /** Real chapter/duration counts once the book's chapters are known. */
    @Query("UPDATE audiobooks SET totalChapters = :totalChapters, totalDurationSeconds = :totalDurationSeconds WHERE id = :bookId")
    suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long)

    /**
     * Back-fills the real page metadata (author, narrator, genre, rating,
     * series) onto a catalogue book once its page has been fetched, replacing
     * the seeded placeholders. Nulls keep the stored value.
     */
    @Query(
        "UPDATE audiobooks SET " +
            "author = COALESCE(:author, author), " +
            "narrator = COALESCE(:narrator, narrator), " +
            "genre = COALESCE(:genre, genre), " +
            "rating = COALESCE(:rating, rating), " +
            "seriesTitle = COALESCE(:seriesTitle, seriesTitle), " +
            "seriesIndex = COALESCE(:seriesIndex, seriesIndex), " +
            "seriesUrl = COALESCE(:seriesUrl, seriesUrl) " +
            "WHERE id = :bookId"
    )
    suspend fun updateBookMetadata(
        bookId: String,
        author: String?,
        narrator: String?,
        genre: String?,
        rating: Float?,
        seriesTitle: String?,
        seriesIndex: Int?,
        seriesUrl: String?
    )

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestampSeconds ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    // Cascade book deletion (spec #8 ticket T2): the entities have no FK
    // constraints, so deletion is coordinated explicitly by the repository.
    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: String)

    @Query("DELETE FROM playback_progress WHERE bookId = :bookId")
    suspend fun deletePlaybackProgressForBook(bookId: String)

    @Query("DELETE FROM audiobooks WHERE id = :bookId")
    suspend fun deleteAudiobook(bookId: String)

    // Playback Progress
    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity?

    @Query("UPDATE playback_progress SET lastPausedAtEpochMs = :pausedAt WHERE bookId = :bookId")
    suspend fun updatePausedAt(bookId: String, pausedAt: Long?)

    @Query("SELECT * FROM playback_progress ORDER BY lastListenedAt DESC")
    fun getAllPlaybackProgress(): Flow<List<PlaybackProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaybackProgress(progress: PlaybackProgressEntity)
    @Query("SELECT * FROM audiobooks WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("UPDATE audiobooks SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: String, isFavorite: Boolean)

    @Query("UPDATE audiobooks SET coverImageUrl = :coverUrl WHERE id = :bookId")
    suspend fun updateCoverImageUrl(bookId: String, coverUrl: String)

    @Query("UPDATE audiobooks SET isDownloaded = 0, downloadProgress = 0 WHERE id = :bookId")
    suspend fun markBookNotDownloaded(bookId: String)

    @Query("UPDATE chapters SET isDownloaded = 0, localFilePath = NULL WHERE bookId = :bookId")
    suspend fun clearChaptersDownloadState(bookId: String)

    @Query("UPDATE audiobooks SET isDownloaded = 0, downloadProgress = 0")
    suspend fun markAllNotDownloaded()

    @Query("UPDATE chapters SET isDownloaded = 0, localFilePath = NULL")
    suspend fun clearAllChaptersDownloadState()

    // Listening Stats
    @Query("SELECT * FROM listening_stats ORDER BY dateIso DESC")
    fun getAllListeningStats(): Flow<List<ListeningStatEntity>>

    @Query("SELECT * FROM listening_stats WHERE dateIso = :dateIso")
    suspend fun getListeningStatForDate(dateIso: String): ListeningStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveListeningStat(stat: ListeningStatEntity)
}
