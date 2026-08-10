with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

import re

# find fetch4ReadPageDetails
start = content.find("private suspend fun fetch4ReadPageDetails")
end = content.find("private fun extractAudioFromHtml", start)

new_func = """private suspend fun fetch4ReadPageDetails(pageUrl: String): Parsed4ReadData = withContext(Dispatchers.IO) {
        var coverUrl: String? = null
        val audioStreams = mutableListOf<String>()
        try {
            val html = fetchUrlText(pageUrl)
            if (html.isNotBlank()) {
                val ogMatch = Regex(\"\"\"<meta\\s+property="og:image"\\s+content="([^"]+)"\"\"\", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex(\"\"\"<meta\\s+content="([^"]+)"\\s+property="og:image\"\"\", RegexOption.IGNORE_CASE).find(html)
                if (ogMatch != null) {
                    coverUrl = ogMatch.groupValues[1]
                }
                if (coverUrl.isNullOrBlank()) {
                    val imgMatch = Regex(\"\"\"<img[^>]+src="([^"]*uploads/posts/[^"]+)"\"\"\", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex(\"\"\"<img[^>]+src="([^"]*4read\\.org/[^"]+\\.(?:jpg|png|webp|jpeg))"\"\"\", RegexOption.IGNORE_CASE).find(html)
                    if (imgMatch != null) {
                        coverUrl = imgMatch.groupValues[1]
                    }
                }
                if (!coverUrl.isNullOrBlank() && !coverUrl.startsWith("http")) {
                    coverUrl = if (coverUrl.startsWith("/")) "https://4read.org$coverUrl" else "https://4read.org/$coverUrl"
                }

                extractAudioFromHtml(html, pageUrl, audioStreams)

                val iframeRegex = Regex(\"\"\"<iframe[^>]+src=["']([^"']+)["']\"\"\", RegexOption.IGNORE_CASE)
                iframeRegex.findAll(html).forEach { m ->
                    val iframeSrc = m.groupValues[1]
                    val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else if (iframeSrc.startsWith("/")) "https://4read.org$iframeSrc" else "https://4read.org/$iframeSrc"
                    if (!fullIframeUrl.contains("facebook") && !fullIframeUrl.contains("vk.com/widget")) {
                        val iframeHtml = fetchUrlText(fullIframeUrl)
                        if (iframeHtml.isNotBlank()) {
                            extractAudioFromHtml(iframeHtml, fullIframeUrl, audioStreams)
                        }
                    }
                }

                val expandedStreams = mutableListOf<String>()
                for (stream in audioStreams) {
                    if (stream.endsWith(".m3u") || stream.endsWith(".txt")) {
                        try {
                            val playlistContent = fetchUrlText(stream)
                            if (playlistContent.isNotBlank()) {
                                if (playlistContent.trim().startsWith("[{")) {
                                    val jsonFileRegex = Regex("\"file\"\\\\s*:\\\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                                    jsonFileRegex.findAll(playlistContent).forEach { m ->
                                        expandedStreams.add(m.groupValues[1])
                                    }
                                } else {
                                    val lines = playlistContent.split("\\n")
                                    for (line in lines) {
                                        val cleanLine = line.trim()
                                        if (cleanLine.startsWith("http")) {
                                            expandedStreams.add(cleanLine)
                                        }
                                    }
                                }
                            }
                        } catch(e: Exception) {
                             expandedStreams.add(stream)
                        }
                    } else {
                        expandedStreams.add(stream)
                    }
                }
                audioStreams.clear()
                audioStreams.addAll(expandedStreams)
            }
        } catch (e: Exception) {
            Log.e("AudiobookRepo", "Error parsing 4read page $pageUrl", e)
        }
        Parsed4ReadData(
            coverImageUrl = coverUrl,
            audioUrls = audioStreams.distinct()
        )
    }

    """

content = content[:start] + new_func + content[end:]

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
