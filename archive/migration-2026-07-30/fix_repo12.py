import re
with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

def insert_encode_helper(text):
    helper = """
    private fun encodeUrl(url: String): String {
        return android.net.Uri.encode(url, "@#&=*+-_.,:!?()/~'%")
    }
"""
    return text.replace("suspend fun searchAudiobooksOn4Read", helper + "\n    suspend fun searchAudiobooksOn4Read")

content = insert_encode_helper(content)

# We need to apply encodeUrl to URLs added to expandedStreams and resultList.
# Wait, for jsonFileRegex:
content = content.replace('expandedStreams.add(m.groupValues[1])', 'expandedStreams.add(encodeUrl(m.groupValues[1]))')
content = content.replace('expandedStreams.add(cleanLine)', 'expandedStreams.add(encodeUrl(cleanLine))')

# In extractAudioFromHtml:
content = content.replace('resultList.add(audioUrl)', 'resultList.add(encodeUrl(audioUrl))')
content = content.replace('resultList.add(full)', 'resultList.add(encodeUrl(full))')
content = content.replace('resultList.add(clean)', 'resultList.add(encodeUrl(clean))')
content = content.replace('resultList.add("https://4read.org$clean")', 'resultList.add(encodeUrl("https://4read.org$clean"))')

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
