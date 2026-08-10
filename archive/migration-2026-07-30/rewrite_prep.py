with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

old_prep = """        try {
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
                    if (chapter.streamUrl.contains("archive.org")) {
                        setDataSource(chapter.streamUrl)
                    } else {
                        setDataSource(context.applicationContext, uri, headers)
                    }
                }

                setOnPreparedListener { player ->
                    prepareTimeoutJob?.cancel()
                    val mode = if (localFile != null && localFile.exists() && localFile.length() > 100) "Offline Local File" else "4read Direct Stream"
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        durationMs = if (player.duration > 0) player.duration.toLong() else durationMs,
                        audioEngineMode = mode
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
                            Log.e("AudioPlayer", "Error starting after prepare", e)
                        }
                    } else {
                        _playerState.value = _playerState.value.copy(isPlaying = false)
                    }
                }

                setOnCompletionListener {
                    onChapterCompleted()
                }

                setOnErrorListener { _, what, extra ->
                    prepareTimeoutJob?.cancel()
                    Log.w("AudioPlayer", "Stream playback error (what=$what, extra=$extra) for URL: ${chapter.streamUrl}")
                    _playerState.value = _playerState.value.copy(
                        lastErrorMsg = "Primary stream error (what=$what, extra=$extra)"
                    )
                    tryFallbackPlayback(chapter, startPositionMs, autoPlay, durationMs, 0)
                    true
                }

                prepareAsync()
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = scope.launch {
                    delay(15000L) // 15 second timeout
                    if (_playerState.value.isBuffering) {
                        Log.w("AudioPlayer", "Primary stream timeout")
                        _playerState.value = _playerState.value.copy(
                            lastErrorMsg = "Primary stream timeout (15s)"
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
        }"""

new_prep = """        try {
            val mp = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true
                )
                
                val localPath = chapter.localFilePath
                val localFile = localPath?.let { java.io.File(it) }
                if (localFile != null && localFile.exists() && localFile.length() > 100) {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(localFile)))
                } else {
                    setMediaItem(MediaItem.fromUri(Uri.parse(chapter.streamUrl)))
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
                            if (_playerState.value.currentPositionMs > 0 && _playerState.value.currentPositionMs < (if (duration > 0) duration else durationMs)) {
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
                    delay(15000L) // 15 second timeout
                    if (_playerState.value.isBuffering) {
                        Log.w("AudioPlayer", "Primary stream timeout")
                        _playerState.value = _playerState.value.copy(
                            lastErrorMsg = "Primary stream timeout (15s)"
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
        }"""

if old_prep in content:
    content = content.replace(old_prep, new_prep)
    with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
        f.write(content)
    print("Replaced prepareChapter successfully.")
else:
    print("Could not find old_prep in content.")
