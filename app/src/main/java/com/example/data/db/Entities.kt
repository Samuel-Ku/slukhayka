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
    val createdAt: Long = System.currentTimeMillis(),
    // SAF tree URI of the local folder this book was imported from (wayfinder
    // #48): kept so a future rescan can re-read chapter metadata without
    // asking the user to pick the folder again. Null for streamed 4read books
    // and single-file imports.
    val sourceTreeUri: String? = null,
    // Multi-source catalog (spec-10 T2): the Work-level dedup key, computed as
    // normalized title|author|narrator (see MergeKey). Books imported from
    // different sources with the same key merge into one Work card. Empty for
    // rows that predate the merge (migration 7->8 leaves them unmatched until
    // re-import).
    @ColumnInfo(defaultValue = "")
    val mergeKey: String = ""
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
    val isDownloaded: Boolean = false,
    // SHA-256 of the copied local file (wayfinder #48): lets re-imports of the
    // same file be detected and skipped without duplicating storage. Null for
    // streamed 4read chapters.
    val contentHash: String? = null
)

/**
 * One playable source of a Work (spec-10 T2). The Work card is the
 * AudiobookEntity row; every source (4read, soundbooks, audiobookmp3, lihtar,
 * local, …) is a row here. `streamOnly` gates the download action per the T1
 * verdicts; local imports carry type "local" with a blank url.
 */
@Entity(tableName = "sources", indices = [Index("bookId")])
data class SourceEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val type: String,
    val url: String,
    val streamOnly: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
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

@Entity(
    tableName = "playback_progress",
    indices = [Index("bookId")],
    // Spec-10 T2: listening state is keyed per source (ADR-0001), so the
    // position of the same book on two sources never corrupts each other.
    primaryKeys = ["bookId", "sourceKey"]
)
data class PlaybackProgressEntity(
    val bookId: String,
    // Which source this position belongs to; "" = the book's primary source
    // (legacy rows and the single-source case).
    val sourceKey: String = "",
    val currentChapterIndex: Int = 0,
    val currentPositionSeconds: Long = 0L,
    val lastListenedAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    // Wall-clock epoch of the last pause (wayfinder #25): drives the smart
    // rewind on resume — the longer the pause, the further back playback
    // rewinds. Null once the rewind has been applied.
    val lastPausedAtEpochMs: Long? = null
)

/**
 * One discrete listening transition (spec-16, wayfinder #53). The
 * [PlaybackProgressEntity] row stays the authoritative "where am I now"
 * read model; this log is history for undo, future sync and listening
 * intelligence. Append-only and capped (see PlaybackEventPolicy) — the state
 * row is never reconstructed from it.
 *
 * Only discrete transitions are recorded (RESUME, PAUSE, SEEK ≥ 5 min,
 * CHAPTER_CHANGE, TIMER_STOP, COMPLETED, RELISTEN, SOURCE_SWITCH); periodic
 * position ticks and sub-threshold seeks are noise and never land here.
 * `fromPositionSeconds` is set only for SEEK / SOURCE_SWITCH — the
 * pre-jump position an undo returns to. `deviceId` stays "" until sync
 * (wayfinder #56) lands.
 */
@Entity(
    tableName = "playback_events",
    indices = [Index("bookId"), Index("sourceKey"), Index("timestamp")]
)
data class PlaybackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    // Which source the transition belongs to; "" = the book's primary source
    // (same convention as PlaybackProgressEntity.sourceKey).
    val sourceKey: String = "",
    // Stable String kind — see PlaybackEventKind. Strings, not enum ordinals,
    // so a future sync does not depend on enum declaration order.
    val kind: String,
    val chapterIndex: Int = 0,
    val positionSeconds: Long = 0L,
    // The pre-transition position, only for SEEK / SOURCE_SWITCH (undo).
    val fromPositionSeconds: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = ""
)

/** Stable String kinds of [PlaybackEventEntity] (spec-16). */
object PlaybackEventKind {
    const val RESUME = "RESUME"
    const val PAUSE = "PAUSE"
    const val SEEK = "SEEK"
    const val CHAPTER_CHANGE = "CHAPTER_CHANGE"
    const val TIMER_STOP = "TIMER_STOP"
    const val COMPLETED = "COMPLETED"
    const val RELISTEN = "RELISTEN"
    const val SOURCE_SWITCH = "SOURCE_SWITCH"
}

/**
 * Durable ledger of playback failures (wayfinder #52). Appended from
 * [com.example.player.AudioPlayerManager.reportPlaybackFailure] so support
 * can see error codes, hosts and audio engine modes per book even if no
 * logcat was captured. Written on the IO dispatcher; never thrown back into
 * the player path — observability must not break playback.
 */
@Entity(tableName = "playback_failures", indices = [Index("bookId")])
data class PlaybackFailureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val bookId: String,
    val chapterIndex: Int,
    // Media3 error.errorCodeName (e.g. ERROR_CODE_IO_UNSPECIFIED), or our own
    // synthetic codes for non-ExoPlayer failures (PREPARE_TIMEOUT, UNKNOWN).
    val errorCodeName: String,
    // The stream URL or local file path being played when the failure hit.
    val streamUrl: String,
    // Player state mode snapshot at failure time (IDLE/BUFFERING/READY/ENDED).
    val audioEngineMode: String
)
