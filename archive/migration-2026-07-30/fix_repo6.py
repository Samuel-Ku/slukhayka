with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

import re
content = re.sub(r'playlistContent\.split\(.*?\)', 'playlistContent.split("\\n")', content)

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
