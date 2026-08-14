package com.example.data.db

import androidx.room.Entity
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
    val preferredSpeed: Float? = null
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
    val isCompleted: Boolean = false
)

enum class PlaybackEventKind {
    PLAY, PAUSE, SEEK, ERROR, SKIP,
    SOURCE_SWITCH, RELISTEN, RESUME, CHAPTER_CHANGE, TIMER_STOP, COMPLETED
}

object PlaybackEventFilter {
    fun matches(kind: PlaybackEventKind) = true
    fun shouldRecordResume(gapMs: Long?, nowMs: Long) = true
    fun shouldRecordResume(bookId: String, posSec: Long) = true
    fun shouldRecordResume(bookId: String, chapterIndex: Int, posSec: Long) = true
    fun shouldRecordPause(startMs: Long?, nowMs: Long) = true
    fun shouldRecordPause(bookId: String, posSec: Long) = true
    fun shouldRecordPause(bookId: String, chapterIndex: Int, posSec: Long) = true
    fun shouldRecordSeek(fromMs: Long, toMs: Long) = true
    fun shouldRecordSeek(bookId: String, fromSec: Long, toSec: Long) = true
}

object PlaybackEventPolicy {
    fun shouldRecord(kind: PlaybackEventKind) = true
    fun isStaleUndoCandidate(candidate: Any?, nowMs: Long) = false
    fun isAtUndoPosition(candidate: Any?, positionSeconds: Long) = false
    fun recordSeek(bookId: String, fromSec: Long, toSec: Long) {}
    fun recordSeek(bookId: String, fromSec: Long) {}
}

data class PlaybackEventEntity(
    val id: Long = 0,
    val bookId: String = "",
    val eventKind: String = "",
    val positionSeconds: Long = 0L,
    val fromPositionSeconds: Long? = null,
    val chapterIndex: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

