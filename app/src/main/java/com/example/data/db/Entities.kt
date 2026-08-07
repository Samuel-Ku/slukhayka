package com.example.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "audiobooks")
data class AudiobookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val coverDrawableRes: Int,
    val coverImageUrl: String? = null,
    val genre: String,
    val sourceUrl: String,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f,
    val totalDurationSeconds: Long = 0L,
    val totalChapters: Int = 0,
    val rating: Float = 4.9f,
    val isFavorite: Boolean = false,
    // 4read series (cycle) metadata (spec-9 T1): parsed from the poster's
    // `poster__series` chip and `poster__label--blue` volume badge.
    val seriesTitle: String? = null,
    val seriesUrl: String? = null,
    val seriesIndex: Int? = null,
    // Per-book playback speed (wayfinder #26): null means "use the global
    // default" from PlaybackSettings.
    val preferredSpeed: Float? = null,
    // When the book entered the library (wayfinder #39): drives the
    // "recently added" sort. Migration 6->7 backfills existing rows with the
    // migration-run time; new imports stamp their own insert time.
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "listening_stats")
data class ListeningStatEntity(
    @PrimaryKey val dateIso: String,
    val listenedSeconds: Long = 0L
)

@Entity(tableName = "chapters", indices = [Index("bookId")])
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val durationSeconds: Long,
    val streamUrl: String,
    val localFilePath: String? = null,
    val isDownloaded: Boolean = false
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val timestampSeconds: Long,
    val note: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playback_progress", indices = [Index("bookId")])
data class PlaybackProgressEntity(
    @PrimaryKey val bookId: String,
    val currentChapterIndex: Int = 0,
    val currentPositionSeconds: Long = 0L,
    val lastListenedAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    // Wall-clock epoch of the last pause (wayfinder #25): drives the smart
    // rewind on resume — the longer the pause, the further back playback
    // rewinds. Null once the rewind has been applied.
    val lastPausedAtEpochMs: Long? = null
)
