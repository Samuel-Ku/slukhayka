with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

import re

# We will create a wrapper
wrapper = """
    private fun getPlayerContext(): android.content.Context {
        val attrContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.createAttributionContext("audioPlayer")
        } else {
            context
        }
        return object : android.content.ContextWrapper(attrContext) {
            override fun getApplicationContext(): android.content.Context {
                return attrContext
            }
        }
    }
"""

content = re.sub(r'    private fun getPlayerContext\(\): android\.content\.Context \{.*?\n    \}', wrapper.strip("\n"), content, flags=re.DOTALL)

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
