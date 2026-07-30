package com.example.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
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
    val lastErrorMsg: String = ""
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
 * AudioPlayerManager wraps Media3 ExoPlayer. The class opts into Media3's
 * `UnstableApi` surface because we intentionally call HttpDataSource.Factory
 * accessors (`setUserAgent`, `setDefaultRequestProperties`, etc.) that are
 * not part of the stable API yet.
 *
 * Historical note: before Phase 2.5 hotfix these calls were unguarded, which
 * caused `./gradlew lintDebug` to fail with 11 `UnsafeOptInUsageError`s and
 * was flagged as CRITICAL finding CR-004 in
 * docs/audits/2026-07-30-static-and-agents.md.
 */
@OptIn(UnstableApi::class)
class AudioPlayerManager(
    private val context: Context,
    private val repository: AudiobookRepository,
    private val playerFactory: PlayerFactory = DEFAULT_PLAYER_FACTORY
) {

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var mediaPlayer: Player? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateProgressJob: Job? = null
    private var sleepTimer: CountDownTimer? = null
    private var prepareTimeoutJob: Job? = null

    private fun getPlayerContext(): android.content.Context {
        return context.applicationContext
    }


    init {
        startProgressTracker()
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
            isOfflineMode = book.isDownloaded
        )

        prepareChapter(chapterIdx, initialPositionSeconds * 1000L, autoPlay)
    }

    fun prepareChapter(chapterIndex: Int, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        mediaPlayer?.release()
        mediaPlayer = null

        val chapters = _playerState.value.chapters
        if (chapters.isEmpty() || chapterIndex !in chapters.indices) return

        val chapter = chapters[chapterIndex]
        val durationMs = chapter.durationSeconds * 1000L

        _playerState.value = _playerState.value.copy(
            currentChapterIndex = chapterIndex,
            currentPositionMs = startPositionMs,
            durationMs = durationMs,
            isBuffering = true,
            currentStreamUrl = chapter.streamUrl,
            lastErrorMsg = ""
        )

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://4read.org/"
        )

        try {
            val mp = playerFactory.create(getPlayerContext()).apply {

                val localPath = chapter.localFilePath
                val localFile = localPath?.let { java.io.File(it) }
                if (localFile != null && localFile.exists() && localFile.length() > 100) {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(localFile)))
                } else {
                    setMediaItem(MediaItem.fromUri(chapter.streamUrl.toUri()))
                }

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            prepareTimeoutJob?.cancel()
                            val mode = if (localFile != null && localFile.exists() && localFile.length() > 100) "Offline Local File" else "4read Direct Stream"
                            _playerState.value = _playerState.value.copy(
                                isBuffering = false,
                                durationMs = if (duration > 0) duration else durationMs,
                                audioEngineMode = mode
                            )
                            applyPlaybackSpeed(_playerState.value.playbackSpeed)
                            val validDur = if (duration > 0) duration else durationMs
                            if (_playerState.value.currentPositionMs > 0 && _playerState.value.currentPositionMs < validDur) {
                                try { seekTo(_playerState.value.currentPositionMs) } catch (_: Exception) {}
                            }
                            if (autoPlay || _playerState.value.isPlaying) {
                                try {
                                    play()
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
                        Log.w("AudioPlayer", "Stream playback error (${error.errorCodeName}) for URL: ${chapter.streamUrl}")
                        _playerState.value = _playerState.value.copy(
                            lastErrorMsg = "Primary stream error (${error.errorCodeName})"
                        )
                        tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, 0)
                    }
                })

                prepare()
                
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = scope.launch {
                    delay(45000L) // 45 second prepare timeout (was commented as 15s, see HI-003)
                    if (_playerState.value.isBuffering) {
                        Log.w("AudioPlayer", "Primary stream timeout")
                        _playerState.value = _playerState.value.copy(
                            lastErrorMsg = "Primary stream timeout (45s)"
                        )
                        tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, 0)
                    }
                }
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            prepareTimeoutJob?.cancel()
            Log.e("AudioPlayer", "Exception in prepareChapter", e)
            tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, 0)
        }
    }

    /**
     * Called by [prepareChapter] when the primary stream surfaces a
     * PlaybackException OR when the 45s preparation timeout elapses.
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
     * The new contract: report the failure to PlayerState and let the UI
     * decide what to render. We do NOT synthesize audio from unrelated
     * sources.
     */
    private fun tryFallbackPlayback(
        chapter: ChapterEntity,
        @Suppress("UNUSED_PARAMETER") startPositionMs: Long,
        @Suppress("UNUSED_PARAMETER") autoPlay: Boolean,
        @Suppress("UNUSED_PARAMETER") durationMs: Long,
        @Suppress("UNUSED_PARAMETER") fallbackIndex: Int
    ) {
        prepareTimeoutJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        _playerState.value = _playerState.value.copy(
            isBuffering = false,
            isPlaying = false,
            currentStreamUrl = "",
            audioEngineMode = "Playback error",
            lastErrorMsg = "Цю главу зараз не вдалося відтворити. Спробуйте пізніше або інший розділ."
        )
    }

    fun play() {
        _playerState.value = _playerState.value.copy(isPlaying = true)
        if (_playerState.value.isBuffering) return

        val mp = mediaPlayer
        if (mp != null) {
            try {
                mp.play()
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

    fun seekTo(positionMs: Long) {
        val targetMs = positionMs.coerceIn(0L, _playerState.value.durationMs)
        _playerState.value = _playerState.value.copy(currentPositionMs = targetMs)
        
        if (_playerState.value.isBuffering) return
        
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
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
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
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
            val audioAttr = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build()
            ExoPlayer.Builder(playerContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttr, true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        }
    }
}

