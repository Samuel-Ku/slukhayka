@file:androidx.annotation.OptIn(UnstableApi::class)

package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.repository.AudiobookRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val currentBook: AudiobookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 1000L,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerRemainingSeconds: Int = 0,
    val isBuffering: Boolean = false,
    val isOfflineMode: Boolean = false,
    val audioEngineMode: String = "4read Audio Engine",
    val currentStreamUrl: String = "",
    val lastErrorMsg: String = "",
    // Position-history undo (wayfinder #25): true while a big accidental seek
    // can be undone back to [undoFromPositionMs].
    val canUndoSeek: Boolean = false,
    val undoFromPositionMs: Long = 0L
)

/**
 * Creates the [Player] that [AudioPlayerManager] drives.
 *
 * This exists purely as a test seam (GitHub issue #4): production always uses
 * [AudioPlayerManager.DEFAULT_PLAYER_FACTORY], which builds a real ExoPlayer,
 * while JVM unit tests substitute `FakePlayerEngine` so they never touch
 * ExoPlayer, the network, or a real audio device.
 */
fun interface PlayerFactory {
    fun create(context: Context): Player
}

/**
 * AudioPlayerManager wraps Media3 ExoPlayer. This file opts into Media3's
 * `UnstableApi` surface (`@file:androidx.annotation.OptIn` — kotlin's
 * `@OptIn` is a no-op for androidx `@RequiresOptIn` markers) because we
 * intentionally call HttpDataSource.Factory accessors (`setUserAgent`,
 * `setDefaultRequestProperties`, etc.) that are not part of the stable API
 * yet.
 *
 * Historical note: before Phase 2.5 hotfix these calls were unguarded, which
 * caused `./gradlew lintDebug` to fail with 11 `UnsafeOptInUsageError`s and
 * was flagged as CRITICAL finding CR-004 in
 * docs/audits/2026-07-30-static-and-agents.md.
 *
 * Lifecycle change (background playback): the manager now owns ONE
 * long-lived [Player] for its whole lifetime instead of building a fresh
 * player per chapter. This is required for the MediaSession in
 * [PlaybackService] to keep working across chapter switches, and it avoids
 * paying the ExoPlayer construction cost on every chapter boundary.
 */
class AudioPlayerManager(
    private val context: Context,
    private val repository: AudiobookRepository,
    private val playerFactory: PlayerFactory = DEFAULT_PLAYER_FACTORY,
    /** Wall clock, injectable for deterministic smart-rewind tests. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Global playback preferences; defaults to the real SharedPreferences store. */
    settings: PlaybackSettings? = null
) {

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /**
     * Session telemetry (wayfinder #52): ring buffer of recent events and
     * attempt/failure counters, surfaced in the diagnostic overlay
     * (PlayerDebugOverlay) and the exported journal. In-memory only; failures
     * additionally land in the durable Room ledger via
     * [AudiobookRepository.recordPlaybackFailure].
     */
    val playbackEventLog = PlaybackEventLog()
    val playbackMetrics = PlaybackMetrics()

    /**
     * The single player instance, created on first playback (or first access
     * from [PlaybackService]) and reused for every chapter until [release].
     * The listener is attached exactly once.
     */
    private var mediaPlayer: Player? = null

    /** Access for [PlaybackService] to build its [androidx.media3.session.MediaSession]. */
    val player: Player get() = ensurePlayerCreated()

    /** Chapter currently loaded on the player; used by the single listener. */
    private var currentChapter: ChapterEntity? = null

    /** Whether the current prepare should auto-start once READY. */
    private var shouldAutoPlay: Boolean = false

    /**
     * Resume position to seek EXACTLY ONCE after the next READY transition,
     * or -1 when nothing is pending.
     *
     * Root cause of the 2026-08-08 device bug (resume seek loop): the READY
     * listener used to call `mp.seekTo(_playerState.currentPositionMs)` on
     * EVERY READY. When resuming at a saved position > 0 the state position
     * never advances while the player is in BUFFERING, so every READY re-issued
     * the same seek -> READY -> seek -> BUFFERING -> READY forever (device
     * logcat: buffered position kept resetting to the frozen resume position
     * 452000). Seeking is now a consumed one-shot: [prepareChapter],
     * [seekTo] while buffering and [applySmartRewindIfNeeded] arm it, the READY
     * listener fires it once and disarms.
     */
    private var pendingResumeSeekMs: Long = -1L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateProgressJob: Job? = null
    private var sleepTimer: CountDownTimer? = null
    private var prepareTimeoutJob: Job? = null

    /** Global playback preferences (wayfinder #26): default speed etc. */
    private val playbackSettings = settings ?: PlaybackSettings(context)

    /** Wall-clock epoch of the last in-session pause (wayfinder #25). */
    private var pausedAtEpochMs: Long? = null

    /** Position history for the "Повернутися" undo action (wayfinder #25). */
    private val seekHistory = SeekHistory()

    private fun getPlayerContext(): Context {
        return context.applicationContext
    }

    init {
        startProgressTracker()
    }

    /** Creates the player exactly once; subsequent calls return the same instance. */
    private fun ensurePlayerCreated(): Player {
        mediaPlayer?.let { return it }
        return playerFactory.create(getPlayerContext()).also { mp ->
            mp.addListener(playerListener)
            mediaPlayer = mp
        }
    }

    /**
     * Single listener attached once at player creation. Unlike the old
     * per-prepare listener, this one reads the "current chapter" state instead
     * of closing over a chapter local.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                prepareTimeoutJob?.cancel()
                val mp = mediaPlayer ?: return
                val localFile = currentChapter?.localFilePath?.let { java.io.File(it) }
                val isLocal = localFile != null && localFile.exists() && localFile.length() > 100
                _playerState.value = _playerState.value.copy(
                    isBuffering = false,
                    durationMs = if (mp.duration > 0) mp.duration else _playerState.value.durationMs,
                    audioEngineMode = if (isLocal) "Offline Local File" else "4read Direct Stream"
                )
                // Persist the real chapter duration once the stream reports it,
                // so the book's total duration is honest instead of a seeded
                // placeholder ("4:00:00" for every catalogue book).
                persistRealDurationIfKnown(mp.duration)
                applyPlaybackSpeed(_playerState.value.playbackSpeed)
                val validDur = if (mp.duration > 0) mp.duration else _playerState.value.durationMs
                // One-shot resume seek (see [pendingResumeSeekMs]): fire it once
                // and consume it. Never seek from the live state position here -
                // that re-armed on every READY and stalled resume forever.
                val pendingSeek = pendingResumeSeekMs
                pendingResumeSeekMs = -1L
                if (pendingSeek > 0 && pendingSeek < validDur) {
                    try { mp.seekTo(pendingSeek) } catch (_: Exception) {}
                }
                if (shouldAutoPlay || _playerState.value.isPlaying) {
                    try {
                        mp.play()
                        _playerState.value = _playerState.value.copy(isPlaying = true)
                    } catch (e: Exception) {
                        Log.e("AudioPlayer", "Error starting after prepare", e)
                    }
                } else {
                    _playerState.value = _playerState.value.copy(isPlaying = false)
                }
            } else if (playbackState == Player.STATE_ENDED) {
                onChapterCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            prepareTimeoutJob?.cancel()
            Log.w("AudioPlayer", "Stream playback error (${error.errorCodeName}) for URL: ${currentChapter?.streamUrl}")
            _playerState.value = _playerState.value.copy(
                lastErrorMsg = "Primary stream error (${error.errorCodeName})"
            )
            reportPlaybackFailure(
                errorCodeName = error.errorCodeName,
                detail = "Primary stream error (${error.errorCodeName})"
            )
        }
    }

    fun loadAndPlayBook(
        book: AudiobookEntity,
        chapters: List<ChapterEntity>,
        initialChapterIndex: Int = 0,
        initialPositionSeconds: Long = 0L,
        autoPlay: Boolean = true
    ) {
        val chapterIdx = initialChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        _playerState.value = _playerState.value.copy(
            currentBook = book,
            chapters = chapters,
            currentChapterIndex = chapterIdx,
            currentPositionMs = initialPositionSeconds * 1000L,
            durationMs = if (chapters.isNotEmpty()) chapters[chapterIdx].durationSeconds * 1000L else 1000L,
            isOfflineMode = book.isDownloaded,
            // Per-book speed memory (wayfinder #26): the book's own speed if it
            // has one, otherwise the global default. Applied to the engine once
            // the chapter reaches READY.
            playbackSpeed = book.preferredSpeed ?: playbackSettings.defaultSpeed,
            canUndoSeek = false,
            undoFromPositionMs = 0L
        )

        prepareChapter(chapterIdx, initialPositionSeconds * 1000L, autoPlay)
    }

    fun prepareChapter(chapterIndex: Int, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        val chapters = _playerState.value.chapters
        if (chapters.isEmpty() || chapterIndex !in chapters.indices) return

        val chapter = chapters[chapterIndex]
        val durationMs = chapter.durationSeconds * 1000L

        currentChapter = chapter
        shouldAutoPlay = autoPlay || _playerState.value.isPlaying

        _playerState.value = _playerState.value.copy(
            currentChapterIndex = chapterIndex,
            currentPositionMs = startPositionMs,
            durationMs = durationMs,
            isBuffering = true,
            currentStreamUrl = chapter.streamUrl,
            lastErrorMsg = "",
            // A chapter switch is deliberate — the seek history is cleared.
            canUndoSeek = false,
            undoFromPositionMs = 0L
        )

        // Arm the one-shot resume seek (see [pendingResumeSeekMs]): it is fired
        // by the READY listener exactly once and consumed, so a resume position
        // can never re-trigger a READY -> seek -> BUFFERING loop.
        pendingResumeSeekMs = startPositionMs.takeIf { it > 0 } ?: -1L

        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = scope.launch {
            delay(PREPARE_TIMEOUT_MS)
            if (_playerState.value.isBuffering) {
                Log.w("AudioPlayer", "Primary stream timeout")
                _playerState.value = _playerState.value.copy(
                    lastErrorMsg = "Primary stream timeout (${PREPARE_TIMEOUT_MS / 1000}s)"
                )
                reportPlaybackFailure(
                    errorCodeName = "PREPARE_TIMEOUT",
                    detail = "Primary stream timeout (${PREPARE_TIMEOUT_MS / 1000}s)"
                )
            }
        }

        if (autoPlay) {
            ensurePlaybackServiceStarted()
        }

        playbackMetrics.recordAttempt()
        playbackEventLog.record("PREPARE ch${chapterIndex} ${chapter.streamUrl}")

        try {
            // setMediaItem replaces the previous playlist entry and resets the
            // player to IDLE; prepare() re-enters BUFFERING -> READY.
            val mp = ensurePlayerCreated()
            mp.setMediaItem(buildMediaItem(chapter))
            mp.prepare()
        } catch (e: Exception) {
            prepareTimeoutJob?.cancel()
            Log.e("AudioPlayer", "Exception in prepareChapter", e)
            reportPlaybackFailure(
                errorCodeName = e::class.java.simpleName,
                detail = "Exception in prepareChapter (${e::class.java.simpleName})"
            )
        }
    }

    /**
     * Failed primary stream (PlaybackException OR 45s prepare timeout).
     *
     * Phase 2.5 hotfix (CR-002 / SF-003 / SF-005 / SF-006 in
     * docs/audits/2026-07-30-static-and-agents.md): the previous
     * implementation walked a hardcoded list of unrelated archive.org / GitHub
     * sample MP3s and silently played them as if they were the user-requested
     * chapter. Users heard time-machine / war-of-the-worlds audio while the
     * UI showed their selected book. That is the textbook "failure that looks
     * like success" and trips every reasonable trust assumption about media
     * playback.
     *
     * The contract: report the failure to PlayerState and let the UI decide
     * what to render. We do NOT synthesize audio from unrelated sources.
     *
     * Unlike the old code, the shared player is intentionally NOT released
     * here: the MediaSession in PlaybackService wraps it, and a subsequent
     * [play] re-prepares the same instance.
     */
    private fun reportPlaybackFailure(
        errorCodeName: String = "UNKNOWN",
        detail: String = "Цю главу зараз не вдалося відтворити. Спробуйте пізніше або інший розділ."
    ) {
        prepareTimeoutJob?.cancel()
        val state = _playerState.value
        val failedUrl = currentChapter?.streamUrl ?: state.currentStreamUrl
        playbackMetrics.recordFailure(errorCodeName)
        playbackEventLog.record("FAIL $errorCodeName ${failedUrl}")
        // The host part turns an opaque error into an actionable one ("which
        // server refused the stream"), without leaking credentials: stream
        // URLs never carry secrets.
        val host = failedUrl.toUri().host
        val enriched = if (host != null) "$detail (host: $host)" else detail
        _playerState.value = state.copy(
            isBuffering = false,
            isPlaying = false,
            currentStreamUrl = "",
            audioEngineMode = "Playback error",
            lastErrorMsg = enriched
        )
        val chapter = currentChapter
        val bookId = state.currentBook?.id
        if (bookId != null && chapter != null) {
            scope.launch {
                try {
                    repository.recordPlaybackFailure(
                        bookId = bookId,
                        chapterIndex = chapter.chapterIndex,
                        errorCodeName = errorCodeName,
                        streamUrl = failedUrl,
                        audioEngineMode = "Playback error"
                    )
                } catch (e: Exception) {
                    // Observability must never break playback (wayfinder #52).
                    Log.w("AudioPlayer", "Failed to record playback failure", e)
                }
            }
        }
    }

    /**
     * MediaItem with book/chapter metadata so the system media notification
     * (and Android Auto / lock screen) shows a real title instead of a URL.
     */
    private fun buildMediaItem(chapter: ChapterEntity): MediaItem {
        val book = _playerState.value.currentBook
        val metadata = MediaMetadata.Builder()
            .setTitle(chapter.title)
            .setArtist(book?.author?.ifBlank { null } ?: book?.title)
            .setAlbumTitle(book?.title)
            .setArtworkUri(book?.coverImageUrl?.toUri())
            .build()
        val localFile = chapter.localFilePath?.let { java.io.File(it) }
        return if (localFile != null && localFile.exists() && localFile.length() > 100) {
            MediaItem.Builder()
                .setUri(Uri.fromFile(localFile))
                .setMediaMetadata(metadata)
                .build()
        } else {
            MediaItem.Builder()
                .setUri(chapter.streamUrl.toUri())
                .setMediaMetadata(metadata)
                .build()
        }
    }

    /**
     * Starts [PlaybackService] so background playback survives the Activity
     * being destroyed. Called on every user-initiated play path.
     *
     * Uses `startService`, deliberately NOT `startForegroundService`: Media3's
     * MediaSessionService only calls `startForeground()` once playback is
     * actually playing (READY + playWhenReady). With a slow stream the 30s
     * startForeground deadline imposed on startForegroundService() would be
     * exceeded and the system would kill the app with
     * ForegroundServiceDidNotStartInTimeException (observed on device:
     * BUFFERING stream -> crash at 20:47:29). startService has no deadline,
     * and the service promotes itself to foreground the moment playback
     * starts. The call is always user-initiated while the app is in the
     * foreground, so the API 26+ background-start ban does not apply.
     */
    private fun ensurePlaybackServiceStarted() {
        try {
            context.startService(PlaybackService.playIntent(getPlayerContext()))
        } catch (e: Exception) {
            // e.g. IllegalStateException if a background start is ever rejected;
            // playback itself still works.
            Log.w("AudioPlayer", "Unable to start playback service", e)
        }
        // Media3 1.3.1 only promotes the service to foreground (and shows the
        // media notification) when at least one MediaController is connected to
        // the session: `MediaNotificationManager.shouldRunInForeground()`
        // returns false when `getConnectedControllerForSession() == null`.
        // Verified on device (OnePlus 8 Pro): with no controller the service
        // runs as a plain background service and the system kills it after
        // ~90s with "Stopping service due to app idle" even though playback
        // was PLAYING. Connect a controller we keep around for the process
        // lifetime; the UI still drives the same Player directly.
        ensureMediaControllerConnected()
    }

    /**
     * Connects a background [MediaController] to the session hosted by
     * [PlaybackService]. We never issue commands through it (the UI drives the
     * shared Player directly), but its mere presence tells Media3 the session
     * is controller-connected, which unlocks the foreground service + media
     * notification. It also exposes the session to Android Auto / headset
     * media buttons through the service's intent filter.
     */
    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private fun ensureMediaControllerConnected() {
        if (mediaController != null || mediaControllerFuture != null) return
        val token = SessionToken(
            getPlayerContext(),
            ComponentName(getPlayerContext(), PlaybackService::class.java)
        )
        val future = MediaController.Builder(getPlayerContext(), token).buildAsync()
        mediaControllerFuture = future
        future.addListener(
            {
                try {
                    mediaController = future.get()
                } catch (e: Exception) {
                    Log.w("AudioPlayer", "MediaController connect failed", e)
                }
            },
            ContextCompat.getMainExecutor(getPlayerContext())
        )
    }

    fun play() {
        _playerState.value = _playerState.value.copy(isPlaying = true)
        ensurePlaybackServiceStarted()
        // Smart rewind (wayfinder #25): coming back from a pause rewinds a few
        // seconds to a few tens of seconds depending on how long the break was.
        applySmartRewindIfNeeded()
        if (_playerState.value.isBuffering) return

        val mp = mediaPlayer
        if (mp != null) {
            try {
                // After an error/timeout the player sits in STATE_IDLE with a
                // stale error; re-prepare the current chapter instead of
                // issuing play() into a dead player.
                if (mp.playbackState == Player.STATE_READY || mp.playbackState == Player.STATE_ENDED) {
                    mp.play()
                } else if (currentChapter != null) {
                    prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error resume play", e)
                prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
            }
        } else {
            prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
        }
    }

    fun pause() {
        _playerState.value = _playerState.value.copy(isPlaying = false)
        // Record the pause moment (wayfinder #25): the resume rewind depends on
        // how long the listener has been away. Also persisted so a later app
        // restart can rewind too.
        pausedAtEpochMs = now()
        persistPausedAt(pausedAtEpochMs)
        if (_playerState.value.isBuffering) return

        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.pause()
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error pause", e)
            }
        }
        saveCurrentProgressToDb()
    }

    fun togglePlayPause() {
        if (_playerState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long, recordInHistory: Boolean = true) {
        val prevPos = _playerState.value.currentPositionMs
        val chapterIdx = _playerState.value.currentChapterIndex
        val targetMs = positionMs.coerceIn(0L, _playerState.value.durationMs)

        if (recordInHistory) {
            // Position history (wayfinder #25): a big seek is remembered so the
            // UI can offer "Повернутися" back to where the listener was.
            seekHistory.recordSeek(prevPos, targetMs, chapterIdx)
            val jump = seekHistory.lastJump
            _playerState.value = _playerState.value.copy(
                currentPositionMs = targetMs,
                canUndoSeek = jump != null,
                undoFromPositionMs = jump?.fromPositionMs ?: 0L
            )
        } else {
            _playerState.value = _playerState.value.copy(currentPositionMs = targetMs)
        }

        // Seek requested while the engine is still preparing/buffering: the
        // player cannot honour it now, so arm the one-shot READY seek instead.
        // (A target of 0 needs no arm: a fresh prepare starts at 0 anyway.)
        if (_playerState.value.isBuffering) {
            if (targetMs > 0) pendingResumeSeekMs = targetMs
            return
        }

        mediaPlayer?.let { mp ->
            try {
                mp.seekTo(targetMs)
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error seeking", e)
            }
        }
        saveCurrentProgressToDb()
    }

    fun skipForward(seconds: Int = 30) {
        seekTo(_playerState.value.currentPositionMs + (seconds * 1000L))
    }

    fun skipBackward(seconds: Int = 15) {
        seekTo(_playerState.value.currentPositionMs - (seconds * 1000L))
    }

    fun nextChapter() {
        val chapters = _playerState.value.chapters
        val nextIdx = _playerState.value.currentChapterIndex + 1
        if (nextIdx in chapters.indices) {
            prepareChapter(nextIdx, startPositionMs = 0L, autoPlay = _playerState.value.isPlaying)
        }
    }

    fun previousChapter() {
        val chapters = _playerState.value.chapters
        val prevIdx = _playerState.value.currentChapterIndex - 1
        if (prevIdx in chapters.indices) {
            prepareChapter(prevIdx, startPositionMs = 0L, autoPlay = _playerState.value.isPlaying)
        } else {
            seekTo(0L)
        }
    }

    fun selectChapter(index: Int) {
        val chapters = _playerState.value.chapters
        if (index in chapters.indices) {
            prepareChapter(index, startPositionMs = 0L, autoPlay = true)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playerState.value = _playerState.value.copy(playbackSpeed = speed)
        applyPlaybackSpeed(speed)
    }

    /**
     * Remembers [speed] for the current book (wayfinder #26) so the next time
     * this book loads it resumes at that speed. No-op when nothing is playing.
     */
    fun savePreferredSpeed(speed: Float) {
        val book = _playerState.value.currentBook ?: return
        scope.launch(Dispatchers.IO) {
            repository.setPreferredSpeed(book.id, speed)
        }
    }

    /** Sets the global default speed applied to books without a saved one (wayfinder #26). */
    fun setDefaultSpeed(speed: Float) {
        playbackSettings.defaultSpeed = speed
    }

    /**
     * The "Повернутися" action (wayfinder #25): jumps back to the position the
     * listener was at before the last big seek.
     */
    fun undoLastSeek() {
        val jump = seekHistory.consumeUndo() ?: return
        _playerState.value = _playerState.value.copy(
            canUndoSeek = false,
            undoFromPositionMs = 0L
        )
        if (jump.fromPositionMs == jump.toPositionMs) return
        if (jump.chapterIndex != _playerState.value.currentChapterIndex &&
            jump.chapterIndex in _playerState.value.chapters.indices
        ) {
            prepareChapter(jump.chapterIndex, jump.fromPositionMs, autoPlay = _playerState.value.isPlaying)
        } else {
            // recordInHistory = false: undoing must not record a new jump back.
            seekTo(jump.fromPositionMs, recordInHistory = false)
        }
    }

    /**
     * Applies the smart rewind once when resuming from a pause (wayfinder #25).
     * The rewind is written straight into the player state so it also takes
     * effect on a buffering resume (the READY listener seeks to the state
     * position); a ready engine is seeked directly.
     */
    private fun applySmartRewindIfNeeded() {
        val pausedAt = pausedAtEpochMs ?: return
        // The pause has been consumed either way — keep the in-memory marker and
        // the persisted one in lockstep so a restart never rewinds the same
        // pause twice (code-review: the two markers must not diverge).
        pausedAtEpochMs = null
        persistPausedAt(null)
        val currentPos = _playerState.value.currentPositionMs
        if (currentPos <= 0L) return
        val rewindSec = SmartRewind.computeRewindSeconds(now() - pausedAt)
        if (rewindSec <= 0L) return
        val targetMs = (currentPos - rewindSec * 1000L).coerceAtLeast(0L)
        _playerState.value = _playerState.value.copy(currentPositionMs = targetMs)
        mediaPlayer?.let { mp ->
            try {
                if (mp.playbackState == Player.STATE_READY) {
                    mp.seekTo(targetMs)
                } else {
                    // Engine not ready yet: arm the one-shot seek so the rewind
                    // position survives the pending READY (same consumed-once
                    // mechanism as [pendingResumeSeekMs]).
                    pendingResumeSeekMs = targetMs
                }
            } catch (_: Exception) {}
        }
    }

    private fun persistPausedAt(epochMs: Long?) {
        val book = _playerState.value.currentBook ?: return
        val bookId = book.id
        scope.launch(Dispatchers.IO) {
            repository.updatePausedAt(bookId, epochMs)
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        mediaPlayer?.let { mp ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mp.setPlaybackParameters(PlaybackParameters(speed))
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Speed change error", e)
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        if (minutes <= 0) {
            _playerState.value = _playerState.value.copy(sleepTimerMinutes = 0, sleepTimerRemainingSeconds = 0)
            try { mediaPlayer?.volume = 1.0f } catch (_: Exception) {}
            return
        }

        val totalMs = minutes * 60 * 1000L
        _playerState.value = _playerState.value.copy(
            sleepTimerMinutes = minutes,
            sleepTimerRemainingSeconds = minutes * 60
        )

        sleepTimer = object : CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingSec = (millisUntilFinished / 1000L).toInt()
                _playerState.value = _playerState.value.copy(
                    sleepTimerRemainingSeconds = remainingSec
                )
                // Smooth volume fade during the last 15 seconds
                if (remainingSec in 1..15) {
                    val vol = remainingSec / 15f
                    try { mediaPlayer?.volume = vol } catch (_: Exception) {}
                }
            }

            override fun onFinish() {
                val book = _playerState.value.currentBook
                val chapterIdx = _playerState.value.currentChapterIndex
                val chapters = _playerState.value.chapters
                val chapterTitle = chapters.getOrNull(chapterIdx)?.title ?: "Глава ${chapterIdx + 1}"
                val posSec = _playerState.value.currentPositionMs / 1000L

                if (book != null) {
                    scope.launch(Dispatchers.IO) {
                        repository.addBookmark(
                            BookmarkEntity(
                                bookId = book.id,
                                chapterIndex = chapterIdx,
                                chapterTitle = chapterTitle,
                                timestampSeconds = posSec,
                                note = "Авто-закладка (Таймер сну)"
                            )
                        )
                    }
                }

                pause()
                try { mediaPlayer?.volume = 1.0f } catch (_: Exception) {}
                _playerState.value = _playerState.value.copy(
                    sleepTimerMinutes = 0,
                    sleepTimerRemainingSeconds = 0
                )
            }
        }.start()
    }

    private fun onChapterCompleted() {
        val nextIdx = _playerState.value.currentChapterIndex + 1
        val chapters = _playerState.value.chapters
        if (nextIdx in chapters.indices) {
            prepareChapter(nextIdx, startPositionMs = 0L, autoPlay = true)
        } else {
            _playerState.value = _playerState.value.copy(isPlaying = false, currentPositionMs = _playerState.value.durationMs)
            saveCurrentProgressToDb()
        }
    }

    private fun startProgressTracker() {
        updateProgressJob?.cancel()
        updateProgressJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val state = _playerState.value
                if (state.isPlaying && !state.isBuffering) {
                    var newPos = state.currentPositionMs
                    val mp = mediaPlayer

                    if (mp != null) {
                        try {
                            if (mp.isPlaying) {
                                newPos = mp.currentPosition
                            }
                        } catch (e: Exception) {
                            // Ignore get position errors
                        }
                    }

                    if (newPos >= state.durationMs) {
                        onChapterCompleted()
                    } else {
                        _playerState.value = state.copy(currentPositionMs = newPos)
                    }

                    if ((newPos / 1000L) % 5 == 0L) {
                        saveCurrentProgressToDb()
                    }
                }
            }
        }
    }

    /**
     * Writes the player-reported duration back into the chapter row when the
     * engine actually knows it. The book's total duration is only recomputed
     * once EVERY chapter has a real duration — until then the site's own
     * "Триває:" value (stored via updateBookStats) stays authoritative, so a
     * partially-played book never shows a shrunken partial sum.
     */
    private fun persistRealDurationIfKnown(durationMs: Long) {
        val book = _playerState.value.currentBook ?: return
        val chapter = currentChapter ?: return
        if (durationMs <= 0L) return
        val seconds = durationMs / 1000L
        scope.launch(Dispatchers.IO) {
            repository.updateChapterDuration(chapter.id, seconds)
            val chapters = repository.getChaptersList(book.id)
            if (chapters.isNotEmpty() && chapters.all { it.durationSeconds > 0L }) {
                repository.updateBookStats(book.id, chapters.size, chapters.sumOf { it.durationSeconds })
            }
        }
    }

    private fun saveCurrentProgressToDb() {
        val book = _playerState.value.currentBook ?: return
        val currentChapter = _playerState.value.currentChapterIndex
        val posSec = _playerState.value.currentPositionMs / 1000L
        scope.launch(Dispatchers.IO) {
            repository.updateProgress(book.id, currentChapter, posSec)
            repository.recordListeningTime(5L)
        }
    }

    fun release() {
        sleepTimer?.cancel()
        updateProgressJob?.cancel()
        // Code-review HIGH #1 (post-Wave-1 review): without this, a prepare
        // timeout coroutine launched by `prepareChapter` can outlive the
        // manager and mutate `_playerState.value` after the test (or a
        // future onStop hook) releases the underlying player.
        prepareTimeoutJob?.cancel()
        pendingResumeSeekMs = -1L
        mediaController?.release()
        mediaController = null
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        mediaControllerFuture = null
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Stops playback and resets the manager to a pristine, empty state.
     *
     * Spec #8 ticket T3: deleting a book that is currently playing must stop
     * the player and clear the whole [PlayerState] (book, chapters, position)
     * so no "ghost" session survives. Unlike [release] the underlying player
     * instance is kept alive — the app-scoped manager stays usable for the
     * next book the user picks.
     */
    fun stopAndClear() {
        sleepTimer?.cancel()
        sleepTimer = null
        prepareTimeoutJob?.cancel()
        // Deliberately NOT persisting progress here: the caller (deleteBook)
        // removes the book's progress row right after, so a save would race it
        // and could re-insert an orphaned row for a deleted bookId (code-review
        // MEDIUM). The book is gone — its position goes with it.
        mediaPlayer?.let { mp ->
            try {
                mp.pause()
                mp.stop()
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error stopping player", e)
            }
        }
        currentChapter = null
        shouldAutoPlay = false
        pendingResumeSeekMs = -1L
        _playerState.value = PlayerState()
    }

    companion object {
        /** Prepare timeout for a chapter stream: 45 seconds. */
        const val PREPARE_TIMEOUT_MS: Long = 45_000L

        /** Speed presets (wayfinder #26): 0.5x–3.0x with a 0.25 step. */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

        const val SPEED_MIN: Float = 0.5f
        const val SPEED_MAX: Float = 3.0f

        /**
         * Production [PlayerFactory]: a real ExoPlayer wired to the hardened
         * HTTP data source.
         *
         * Phase 2.5 hotfix (SEC-004, SEC-018, SEC-019 in the audit report):
         * drop cross-protocol redirects so a cleartext downgrade via 4read is
         * impossible, drop the hardcoded "SM-S918B" User-Agent that leaks a
         * developer's device model, and remove the 4read.org Referer leak that
         * archive.org uses to correlate playback.
         */
        val DEFAULT_PLAYER_FACTORY = PlayerFactory { playerContext ->
            // HTTP factory for streamed 4read chapters…
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
            // …wrapped in DefaultDataSource so file:// and content:// URIs from
            // locally-imported books (spec #8 T7 / Block 4) are read via
            // FileDataSource/ContentDataSource instead of being forced through
            // HTTP (on-device crash: FileURLConnection cannot be cast to
            // HttpURLConnection).
            val dataSourceFactory = DefaultDataSource.Factory(playerContext, httpDataSourceFactory)
            val audioAttr = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build()
            val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(playerContext) {
                override fun buildVideoRenderers(
                    context: android.content.Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: android.os.Handler,
                    eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
                ) {
                    // Do not build video renderers for an audio-only app
                    // This prevents MediaCodec resource queries that fail on some device/emulator environments
                }
            }.setEnableDecoderFallback(true)

            ExoPlayer.Builder(playerContext, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttr, true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        }
    }
}
