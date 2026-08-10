import re

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

content = content.replace("mp.start()", "mp.play()")
content = content.replace("mp.seekTo(targetMs.toInt())", "mp.seekTo(targetMs)")
content = content.replace("mp.playbackParams = mp.playbackParams.setSpeed(speed)", "mp.setPlaybackParameters(PlaybackParameters(speed))")
content = content.replace("mediaPlayer?.setVolume(1.0f, 1.0f)", "mediaPlayer?.volume = 1.0f")
content = content.replace("mediaPlayer?.setVolume(vol, vol)", "mediaPlayer?.volume = vol")
content = content.replace("mp.currentPosition.toLong()", "mp.currentPosition")
content = content.replace("import android.media.PlaybackParams", "")

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)

