with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

import re

# Insert the attribution context helper
helper = """
    private fun getPlayerContext(): android.content.Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.createAttributionContext("audioPlayer")
        } else {
            context
        }
    }
"""

if "getPlayerContext()" not in content:
    content = content.replace("private var prepareTimeoutJob: Job? = null", "private var prepareTimeoutJob: Job? = null\n" + helper)

# Replace ExoPlayer.Builder(context) with ExoPlayer.Builder(getPlayerContext())
content = content.replace("ExoPlayer.Builder(context)", "ExoPlayer.Builder(getPlayerContext())")

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
