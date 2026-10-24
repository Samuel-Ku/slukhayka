package com.slukhayka.audiobooks.ui

/**
 * #1241 — the engine adapter boundary of an explicit playback attempt.
 *
 * [MainViewModel] prepares the attempt itself (chapters, mapping, resume
 * anchor, ownership checks); handing the prepared result to the player is the
 * one side effect a test must be able to observe without building a real
 * ExoPlayer. Production passes no adapter and calls [playbackManager]'s
 * `loadAndPlayBook` directly; tests inject a recording adapter.
 */
data class PlaybackInstallRequest(
    val bookId: String,
    val attemptId: Long = -1L,
    val chapterId: String?,
    val chapterIndex: Int,
    val positionSeconds: Long,
    val autoPlay: Boolean,
    val forceRelisten: Boolean
)
