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

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersListForBook(bookId: String): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET isDownloaded = :isDownloaded, localFilePath = :filePath WHERE id = :chapterId")
    suspend fun updateChapterDownloadState(chapterId: String, isDownloaded: Boolean, filePath: String?)

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestampSeconds ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    // Playback Progress
    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity?

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
