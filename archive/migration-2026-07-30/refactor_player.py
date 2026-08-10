import re

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

# Add imports
imports = """
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import android.media.MediaPlayer
"""
content = re.sub(r'import android.media.MediaPlayer', imports, content)

# Change variable
content = content.replace("var mediaPlayer: MediaPlayer? = null", "var mediaPlayer: ExoPlayer? = null")

# Replace prepareChapter MediaPlayer logic
# It's better to just replace the whole body of the three functions

def replace_mp(content):
    pass # Wait, doing regex replacements on Kotlin is hard.
