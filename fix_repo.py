with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

import re

# We will modify extractAudioFromHtml to correctly parse the file JS variable
# And also we will fetch the m3u content if it's a playlist.

# In fetch4ReadPageDetails, after extractAudioFromHtml returns a list of streams, we can check if they end with .m3u or .txt and fetch them to expand.

expansion_code = """
                // 3. Inspect standard iframes embedded on 4read pages
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
                
                // 4. Expand m3u/txt playlists
                val expandedStreams = mutableListOf<String>()
                for (stream in audioStreams) {
                    if (stream.endsWith(".m3u") || stream.endsWith(".txt")) {
                        try {
                            val playlistContent = fetchUrlText(stream)
                            if (playlistContent.isNotBlank()) {
                                if (playlistContent.trim().startsWith("[{")) {
                                    // It's a JSON playlist
                                    val jsonFileRegex = Regex(\"\"\""file"\s*:\s*"([^"]+)"\"\"\", RegexOption.IGNORE_CASE)
                                    jsonFileRegex.findAll(playlistContent).forEach { m ->
                                        expandedStreams.add(m.groupValues[1])
                                    }
                                } else {
                                    // Raw text playlist
                                    val lines = playlistContent.split("\n")
                                    for (line in lines) {
                                        val cleanLine = line.trim()
                                        if (cleanLine.startsWith("http")) {
                                            expandedStreams.add(cleanLine)
                                        }
                                    }
                                }
                            }
                        } catch(e: Exception) {
                             expandedStreams.add(stream) // fallback
                        }
                    } else {
                        expandedStreams.add(stream)
                    }
                }
                audioStreams.clear()
                audioStreams.addAll(expandedStreams)
"""

if "// 3. Inspect standard iframes" in content:
    # Let's replace the whole block from 3. to the end of the if(html.isNotBlank()) block
    import re
    block_start = content.find("// 3. Inspect standard iframes")
    block_end = content.find("}\n        } catch (e: Exception) {", block_start)
    if block_start != -1 and block_end != -1:
        content = content[:block_start] + expansion_code.strip() + "\n            " + content[block_end:]


# We also need to fix extractAudioFromHtml to handle {v1}
old_extract = """        // C. PlayerJS / Uppod JS variables: file: "..."
        val fileJsRegex = Regex(\"\"\"file\s*:\s*["']([^"']+)["']\"\"\", RegexOption.IGNORE_CASE)
        fileJsRegex.findAll(html).forEach { m ->
            val rawFile = m.groupValues[1]
            if (rawFile.contains(".mp3") || rawFile.contains(".m4a") || rawFile.contains(".m3u8") || rawFile.contains("/audio/")) {
                rawFile.split(",", ";").forEach { piece ->
                    val clean = piece.trim()
                    if (clean.startsWith("http")) {
                        resultList.add(clean)
                    } else if (clean.startsWith("/")) {
                        resultList.add("https://4read.org$clean")
                    }
                }
            }
        }"""

new_extract = """        // C. PlayerJS / Uppod JS variables: file: "..."
        val fileJsRegex = Regex(\"\"\"file\s*:\s*["']([^"']+)["']\"\"\", RegexOption.IGNORE_CASE)
        fileJsRegex.findAll(html).forEach { m ->
            var rawFile = m.groupValues[1]
            // Decode {v1} obfuscation pattern used by 4read
            rawFile = rawFile.replace("{v1}", "https://4read.org/m3u/")
            
            if (rawFile.contains(".mp3") || rawFile.contains(".m4a") || rawFile.contains(".m3u8") || rawFile.contains(".m3u") || rawFile.contains(".txt") || rawFile.contains("/audio/")) {
                rawFile.split(",", ";").forEach { piece ->
                    val clean = piece.trim()
                    if (clean.startsWith("http")) {
                        resultList.add(clean)
                    } else if (clean.startsWith("/")) {
                        resultList.add("https://4read.org$clean")
                    }
                }
            }
        }"""

content = content.replace(old_extract, new_extract)

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)

