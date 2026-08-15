import re

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

# Replace MediaPlayer imports
content = content.replace("import android.media.MediaPlayer", 
"""import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException""")

# Replace variable
content = content.replace("var mediaPlayer: MediaPlayer? = null", "var mediaPlayer: ExoPlayer? = null")
content = content.replace("import android.media.PlaybackParams", "")

# We will just write a python script that replaces the 3 playback methods manually because it's easier to do it via regex or just write the whole Kotlin file.
