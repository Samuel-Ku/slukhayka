with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

content = content.replace('playlistContent.split("")', 'playlistContent.split("\\n")')

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
