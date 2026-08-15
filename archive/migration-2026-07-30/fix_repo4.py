import re
with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

# Replace Regex(""file"... with a properly escaped string
content = re.sub(r'val jsonFileRegex = Regex\(".*?", RegexOption\.IGNORE_CASE\)',
                 r'val jsonFileRegex = Regex("\"file\"\\\\s*:\\\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)', content)

# Also fix the empty split!
content = content.replace('playlistContent.split("")', 'playlistContent.split("\\n")')

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
