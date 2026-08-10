with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

content = content.replace('val jsonFileRegex = Regex(""""file"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)',
                          'val jsonFileRegex = Regex("\"file\"\\\\s*:\\\\s*\\\"([^\"]+)\\\"", RegexOption.IGNORE_CASE)')

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
