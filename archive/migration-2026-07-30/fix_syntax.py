with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

content = content.replace("if (_playerState.value.currentPositionMs > 0 && _playerState.value.currentPositionMs < (if (duration > 0) duration else durationMs)) {", 
"val validDur = if (duration > 0) duration else durationMs\n                            if (_playerState.value.currentPositionMs > 0 && _playerState.value.currentPositionMs < validDur) {")

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
