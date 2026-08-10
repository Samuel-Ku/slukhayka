import re
with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

wrapper = """
    private fun getPlayerContext(): android.content.Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.createAttributionContext("audioPlayer")
        } else {
            context
        }
    }
"""

content = re.sub(r'    private fun getPlayerContext\(\): android\.content\.Context \{.*?\n    \}', wrapper.strip("\n"), content, flags=re.DOTALL)

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
