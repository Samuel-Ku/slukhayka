with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

old_synth = """            mediaPlayer?.release()
            mediaPlayer = null

            val localMp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                isLooping = true
                setDataSource(cacheFile.absolutePath)
                setOnPreparedListener { player ->
                    prepareTimeoutJob?.cancel()
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        durationMs = if (durationMs > 0) durationMs else 1800000L,
                        audioEngineMode = "Автономний Диктор (Локальне аудіо)"
                    )
                    try { player.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
                    applyPlaybackSpeed(_playerState.value.playbackSpeed)
                    if (_playerState.value.currentPositionMs > 0) {
                        try { player.seekTo(_playerState.value.currentPositionMs.toInt()) } catch (_: Exception) {}
                    }
                    if (autoPlay || _playerState.value.isPlaying) {
                        try {
                            player.start()
                            _playerState.value = _playerState.value.copy(isPlaying = true)
                        } catch (e: Exception) {
                            Log.e("AudioPlayer", "Error starting after synthetic prepare", e)
                        }
                    } else {
                        _playerState.value = _playerState.value.copy(isPlaying = false)
                    }
                }
                setOnCompletionListener { onChapterCompleted() }
                prepareAsync()
            }
            mediaPlayer = localMp
        } catch (e: Exception) {"""

new_synth = """            mediaPlayer?.release()
            mediaPlayer = null

            val localMp = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true
                )
                repeatMode = Player.REPEAT_MODE_ALL
                setMediaItem(MediaItem.fromUri(Uri.fromFile(cacheFile)))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            prepareTimeoutJob?.cancel()
                            _playerState.value = _playerState.value.copy(
                                isBuffering = false,
                                durationMs = if (durationMs > 0) durationMs else 1800000L,
                                audioEngineMode = "Автономний Диктор (Локальне аудіо)"
                            )
                            applyPlaybackSpeed(_playerState.value.playbackSpeed)
                            if (_playerState.value.currentPositionMs > 0) {
                                try { seekTo(_playerState.value.currentPositionMs) } catch (_: Exception) {}
                            }
                            if (autoPlay || _playerState.value.isPlaying) {
                                try {
                                    play()
                                    _playerState.value = _playerState.value.copy(isPlaying = true)
                                } catch (e: Exception) {
                                    Log.e("AudioPlayer", "Error starting after synthetic prepare", e)
                                }
                            } else {
                                _playerState.value = _playerState.value.copy(isPlaying = false)
                            }
                        }
                    }
                })
                prepare()
            }
            mediaPlayer = localMp
        } catch (e: Exception) {"""

if old_synth in content:
    content = content.replace(old_synth, new_synth)
    with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
        f.write(content)
    print("Replaced playLocalSyntheticAudio successfully.")
else:
    print("Could not find old_synth in content.")
