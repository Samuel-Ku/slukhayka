with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

import re
pattern = r"private fun playLocalSyntheticAudio.*?^    }"
new_func = """private fun playLocalSyntheticAudio(chapter: ChapterEntity, startPositionMs: Long, autoPlay: Boolean, durationMs: Long) {
        prepareTimeoutJob?.cancel()
        _playerState.value = _playerState.value.copy(
            isBuffering = false,
            isPlaying = false,
            audioEngineMode = "Помилка відтворення (Немає доступу до аудіо)"
        )
        mediaPlayer?.release()
        mediaPlayer = null
    }"""

content = re.sub(pattern, new_func, content, flags=re.MULTILINE | re.DOTALL)

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
