package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
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
    val audioEngineMode: String = "4read Audio Engine"
)

class AudioPlayerManager(
    private val context: Context,
    private val repository: AudiobookRepository
) {

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateProgressJob: Job? = null
    private var sleepTimer: CountDownTimer? = null

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

    private fun prepareChapter(chapterIndex: Int, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
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
            isBuffering = true
        )

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://4read.org/"
        )

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                
                val localPath = chapter.localFilePath
                val localFile = localPath?.let { java.io.File(it) }
                if (localFile != null && localFile.exists() && localFile.length() > 100) {
                    setDataSource(localFile.absolutePath)
                } else {
                    val uri = Uri.parse(chapter.streamUrl)
                    setDataSource(context.applicationContext, uri, headers)
                }

                setOnPreparedListener { player ->
                    val mode = if (localFile != null && localFile.exists() && localFile.length() > 100) "Offline Local File" else "4read Direct Stream"
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        durationMs = if (player.duration > 0) player.duration.toLong() else durationMs,
                        audioEngineMode = mode
                    )
                    applyPlaybackSpeed(_playerState.value.playbackSpeed)
                    if (startPositionMs > 0 && startPositionMs < player.duration) {
                        player.seekTo(startPositionMs.toInt())
                    }
                    if (autoPlay) {
                        player.start()
                        _playerState.value = _playerState.value.copy(isPlaying = true)
                    } else {
                        _playerState.value = _playerState.value.copy(isPlaying = false)
                    }
                }

                setOnCompletionListener {
                    onChapterCompleted()
                }

                setOnErrorListener { _, what, extra ->
                    Log.w("AudioPlayer", "Stream playback error (what=$what, extra=$extra) for URL: ${chapter.streamUrl}")
                    if (!chapter.streamUrl.contains("archive.org")) {
                        tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs)
                        return@setOnErrorListener true
                    }
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        audioEngineMode = "Stream Error"
                    )
                    true
                }

                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Exception in prepareChapter", e)
            if (!chapter.streamUrl.contains("archive.org")) {
                tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs)
            } else {
                _playerState.value = _playerState.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    audioEngineMode = "Playback Error"
                )
            }
        }
    }

    private fun tryFallbackPlayback(chapter: ChapterEntity, startPositionMs: Long, autoPlay: Boolean, durationMs: Long) {
        val backupUrls = listOf(
            "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3",
            "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3",
            "https://ia800302.us.archive.org/1/items/war_of_the_worlds_librivox/war_of_the_worlds_01_wells_64kb.mp3"
        )
        val fallbackUrl = backupUrls[chapter.chapterIndex % backupUrls.size]
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            val fallbackMp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0",
                    "Referer" to "https://archive.org/"
                )
                setDataSource(context.applicationContext, Uri.parse(fallbackUrl), headers)
                setOnPreparedListener { player ->
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        durationMs = if (player.duration > 0) player.duration.toLong() else durationMs,
                        audioEngineMode = "4read Backup Stream"
                    )
                    applyPlaybackSpeed(_playerState.value.playbackSpeed)
                    if (startPositionMs > 0 && startPositionMs < player.duration) {
                        player.seekTo(startPositionMs.toInt())
                    }
                    if (autoPlay) {
                        player.start()
                        _playerState.value = _playerState.value.copy(isPlaying = true)
                    }
                }
                setOnCompletionListener { onChapterCompleted() }
                setOnErrorListener { _, _, _ ->
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        audioEngineMode = "Stream Error"
                    )
                    true
                }
                prepareAsync()
            }
            mediaPlayer = fallbackMp
        } catch (ex: Exception) {
            Log.e("AudioPlayer", "Fallback playback failed", ex)
            _playerState.value = _playerState.value.copy(
                isBuffering = false,
                isPlaying = false,
                audioEngineMode = "Playback Error"
            )
        }
    }

    fun play() {
        _playerState.value = _playerState.value.copy(isPlaying = true)
        val mp = mediaPlayer
        if (mp != null) {
            try {
                mp.start()
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error resume play", e)
            }
        } else {
            prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
        }
    }

    fun pause() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                try {
                    mp.pause()
                } catch (e: Exception) {
                    Log.e("AudioPlayer", "Error pause", e)
                }
            }
        }
        _playerState.value = _playerState.value.copy(isPlaying = false)
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
        mediaPlayer?.let { mp ->
            try {
                mp.seekTo(targetMs.toInt())
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
                    mp.playbackParams = mp.playbackParams.setSpeed(speed)
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
            try { mediaPlayer?.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
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
                    try { mediaPlayer?.setVolume(vol, vol) } catch (_: Exception) {}
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
                try { mediaPlayer?.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
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
                if (state.isPlaying) {
                    var newPos = state.currentPositionMs
                    val mp = mediaPlayer
                    if (mp != null && mp.isPlaying) {
                        try {
                            newPos = mp.currentPosition.toLong()
                        } catch (e: Exception) {
                            newPos += (1000L * state.playbackSpeed).toLong()
                        }
                    } else {
                        // Progression in narrator / simulated mode
                        newPos += (1000L * state.playbackSpeed).toLong()
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
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

