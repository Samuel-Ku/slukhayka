with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

old_fallback = """        try {
            mediaPlayer?.release()
            mediaPlayer = null
            val fallbackMp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context.applicationContext, Uri.parse(fallbackUrl))
                setOnPreparedListener { player ->
                    prepareTimeoutJob?.cancel()
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        durationMs = if (player.duration > 0) player.duration.toLong() else durationMs,
                        audioEngineMode = "4read Audio Engine (Backup Stream)"
                    )
                    try { player.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
                    applyPlaybackSpeed(_playerState.value.playbackSpeed)
                    if (_playerState.value.currentPositionMs > 0 && _playerState.value.currentPositionMs < player.duration) {
                        try { player.seekTo(_playerState.value.currentPositionMs.toInt()) } catch (_: Exception) {}
                    }
                    if (autoPlay || _playerState.value.isPlaying) {
                        try {
                            player.start()
                            _playerState.value = _playerState.value.copy(isPlaying = true)
                        } catch (e: Exception) {
                            Log.e("AudioPlayer", "Error starting after fallback prepare", e)
                        }
                    } else {
                        _playerState.value = _playerState.value.copy(isPlaying = false)
                    }
                }
                setOnCompletionListener { onChapterCompleted() }
                setOnErrorListener { _, _, _ ->
                    prepareTimeoutJob?.cancel()
                    tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, fallbackIndex + 1)
                    true
                }
                prepareAsync()
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = scope.launch {
                    delay(15000L)
                    if (_playerState.value.isBuffering) {
                        Log.w("AudioPlayer", "Fallback stream timeout")
                        _playerState.value = _playerState.value.copy(
                            lastErrorMsg = "Fallback stream timeout (15s)"
                        )
                        tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, fallbackIndex + 1)
                    }
                }
            }
            mediaPlayer = fallbackMp
        } catch (ex: Exception) {
            prepareTimeoutJob?.cancel()
            Log.e("AudioPlayer", "Fallback playback failed for index $fallbackIndex", ex)
            tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, fallbackIndex + 1)
        }"""

new_fallback = """        try {
            mediaPlayer?.release()
            mediaPlayer = null
            val fallbackMp = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true
                )
                setMediaItem(MediaItem.fromUri(Uri.parse(fallbackUrl)))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            prepareTimeoutJob?.cancel()
                            _playerState.value = _playerState.value.copy(
                                isBuffering = false,
                                durationMs = if (duration > 0) duration else durationMs,
                                audioEngineMode = "4read Audio Engine (Backup Stream)"
                            )
                            applyPlaybackSpeed(_playerState.value.playbackSpeed)
                            if (_playerState.value.currentPositionMs > 0 && _playerState.value.currentPositionMs < (if (duration > 0) duration else durationMs)) {
                                try { seekTo(_playerState.value.currentPositionMs) } catch (_: Exception) {}
                            }
                            if (autoPlay || _playerState.value.isPlaying) {
                                try {
                                    play()
                                    _playerState.value = _playerState.value.copy(isPlaying = true)
                                } catch (e: Exception) {
                                    Log.e("AudioPlayer", "Error starting after fallback prepare", e)
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
                        tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, fallbackIndex + 1)
                    }
                })
                prepare()
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = scope.launch {
                    delay(15000L)
                    if (_playerState.value.isBuffering) {
                        Log.w("AudioPlayer", "Fallback stream timeout")
                        _playerState.value = _playerState.value.copy(
                            lastErrorMsg = "Fallback stream timeout (15s)"
                        )
                        tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, fallbackIndex + 1)
                    }
                }
            }
            mediaPlayer = fallbackMp
        } catch (ex: Exception) {
            prepareTimeoutJob?.cancel()
            Log.e("AudioPlayer", "Fallback playback failed for index $fallbackIndex", ex)
            tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, fallbackIndex + 1)
        }"""

if old_fallback in content:
    content = content.replace(old_fallback, new_fallback)
    with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
        f.write(content)
    print("Replaced tryFallbackPlayback successfully.")
else:
    print("Could not find old_fallback in content.")
