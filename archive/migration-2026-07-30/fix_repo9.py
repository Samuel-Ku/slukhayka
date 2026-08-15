with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

import re

# replace everything from "Raw text playlist" to "}" 
content = re.sub(r'// Raw text playlist.*?\}', r'// Raw text playlist\n                                    val lines = playlistContent.split("\\n")\n                                    for (line in lines) {\n                                        val cleanLine = line.trim()\n                                        if (cleanLine.startsWith("http")) {\n                                            expandedStreams.add(cleanLine)\n                                        }\n                                    }\n                                }', content, flags=re.DOTALL)

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
