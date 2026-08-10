with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

import re
content = re.sub(r'    private fun getPlayerContext\(\): android\.content\.Context \{.*?\n    \}', '    private fun getPlayerContext(): android.content.Context {\n        return context\n    }', content, flags=re.DOTALL)

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
