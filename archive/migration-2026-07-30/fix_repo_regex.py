with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

import re

# Fix imgMatch
content = content.replace(
    'Regex("<img[^>]+src=\\"([^\\"]*uploads/posts/[^\\"]+)\\"", RegexOption.IGNORE_CASE)',
    'Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"""", RegexOption.IGNORE_CASE)'
)
content = content.replace(
    'Regex("<img[^>]+src=\\"([^\\"]*4read\\\\.org/[^\\"]+\\\\\\.(?:jpg|png|webp|jpeg))\\"", RegexOption.IGNORE_CASE)',
    'Regex("""<img[^>]+src="([^"]*4read\\.org/[^"]+\\.(?:jpg|png|webp|jpeg))"""", RegexOption.IGNORE_CASE)'
)
# Fix ogMatch
content = content.replace(
    'Regex("<meta\\\\s+property=\\"og:image\\"\\\\s+content=\\"([^\\"]+)\\"", RegexOption.IGNORE_CASE)',
    'Regex("""<meta\\s+property="og:image"\\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)'
)
content = content.replace(
    'Regex("<meta\\\\s+content=\\"([^\\"]+)\\"\\\\s+property=\\"og:image\\"", RegexOption.IGNORE_CASE)',
    'Regex("""<meta\\s+content="([^"]+)"\\s+property="og:image"""", RegexOption.IGNORE_CASE)'
)
# Fix fileMatch
content = content.replace(
    'Regex("file(?:\\\\s*:\s*|\\\\s*=\\\\s*)[\\"\\\']([^\\"\\\']+\\\\.txt)[\\"\\\']", RegexOption.IGNORE_CASE)',
    'Regex("""file(?:\\s*:\\s*|\\s*=\\s*)["\']([^"\']+\\.txt)["\']""", RegexOption.IGNORE_CASE)'
)
content = content.replace(
    'Regex("\\"file\\"\\\\s*:\s*\\"([^\\"]+)\\"", RegexOption.IGNORE_CASE)',
    'Regex("""file"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)'
)
content = content.replace(
    'Regex("file(?:\\\\s*:\s*|\\\\s*=\\\\s*)[\\"\\\'](http[^\\"\\\']+(?:mp3|m4a|ogg|aac|m3u8)[^\\"\\\']*)[\\"\\\']", RegexOption.IGNORE_CASE)',
    'Regex("""file(?:\\s*:\\s*|\\s*=\\s*)["\'](http[^"\']+(?:mp3|m4a|ogg|aac|m3u8)[^"\']*)["\']""", RegexOption.IGNORE_CASE)'
)

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
