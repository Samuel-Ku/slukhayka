with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if 'val jsonFileRegex = Regex(' in line:
        lines[i] = '                                    val jsonFileRegex = Regex("\\"file\\"\\\\s*:\\\\s*\\"([^\\"]+)\\"", RegexOption.IGNORE_CASE)\n'

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.writelines(lines)
