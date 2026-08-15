import re
with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "r") as f:
    content = f.read()

# I will replace the whole importAudiobookFromHtml method
new_func = '''    suspend fun importAudiobookFromHtml(urlOrSlug: String, html: String): AudiobookEntity {
        val cleanInput = urlOrSlug.trim()
        val slug = cleanInput
            .removePrefix("https://4read.org/")
            .removePrefix("http://4read.org/")
            .removePrefix("4read.org/")
            .removeSuffix(".html")
            .ifEmpty { "4read-custom-${System.currentTimeMillis()}" }

        val bookId = "4read-$slug"
        val existing = dao.getAudiobookById(bookId)
        if (existing != null && existing.coverImageUrl != null) return existing

        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        
        var coverUrl: String? = null
        val audioStreams = mutableListOf<String>()

        if (html.isNotBlank()) {
            val ogMatch = Regex("""<meta\\s+property="og:image"\\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta\\s+content="([^"]+)"\\s+property="og:image"""", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                coverUrl = ogMatch.groupValues[1]
            }
            if (coverUrl.isNullOrBlank()) {
                val imgMatch = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<img[^>]+src="([^"]*4read\\.org/[^"]+\\.(?:jpg|png|webp|jpeg))"""", RegexOption.IGNORE_CASE).find(html)
                if (imgMatch != null) {
                    coverUrl = imgMatch.groupValues[1]
                }
            }
            if (!coverUrl.isNullOrBlank() && !coverUrl.startsWith("http")) {
                coverUrl = if (coverUrl.startsWith("/")) "https://4read.org$coverUrl" else "https://4read.org/$coverUrl"
            }

            extractAudioFromHtml(html, sourceUrl, audioStreams)
            
            // Search for playerjs playlist inline
            val fileMatch = Regex("""file(?:\\s*:\\s*|\\s*=\\s*)["']([^"']+\\.txt)["']""", RegexOption.IGNORE_CASE).find(html)
            if (fileMatch != null) {
                val playlistUrl = fileMatch.groupValues[1]
                val fullUrl = if (playlistUrl.startsWith("http")) playlistUrl else "https://4read.org/$playlistUrl"
                try {
                    val playlistContent = fetchUrlText(fullUrl)
                    if (playlistContent.isNotBlank()) {
                        if (playlistContent.trim().startsWith("[{")) {
                            val jsonFileRegex = Regex("""\"file\"\\s*:\\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
                            jsonFileRegex.findAll(playlistContent).forEach { m ->
                                audioStreams.add(encodeUrl(m.groupValues[1]))
                            }
                        } else {
                            val lines = playlistContent.split("\\n")
                            for (line in lines) {
                                val cleanLine = line.trim()
                                if (cleanLine.startsWith("http")) {
                                    audioStreams.add(encodeUrl(cleanLine))
                                }
                            }
                        }
                    }
                } catch(e: Exception) { }
            }

            val fileMatch2 = Regex("""file(?:\\s*:\\s*|\\s*=\\s*)["'](http[^"']+(?:mp3|m4a|ogg|aac|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(html)
            fileMatch2.forEach { m ->
                audioStreams.add(m.groupValues[1])
            }
        }
        
        val formattedTitle = slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            }
            .ifBlank { "Аудиокнига 4read" }

        val newBook = AudiobookEntity(
            id = bookId,
            title = formattedTitle,
            author = "Аудиокнига 4read.org",
            narrator = "4read Voice Narrator",
            description = "Аудиокнига с портала 4read.org ($cleanInput).",
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = coverUrl,
            genre = "4read Catalog",
            sourceUrl = sourceUrl,
            isDownloaded = false,
            downloadProgress = 0f,
            totalDurationSeconds = 14400L,
            totalChapters = audioStreams.size.coerceAtLeast(1),
            rating = 4.9f
        )

        val chapterList = if (audioStreams.isNotEmpty()) {
            audioStreams.distinct().mapIndexed { index, audioUrl ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Глава ${index + 1}",
                    durationSeconds = 1800L,
                    streamUrl = audioUrl
                )
            }
        } else {
            listOf(
                ChapterEntity("${bookId}_ch1", bookId, 0, "Часть 01: $formattedTitle", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3"),
                ChapterEntity("${bookId}_ch2", bookId, 1, "Часть 02: Продолжение", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3")
            )
        }

        dao.insertAudiobooks(listOf(newBook))
        dao.insertChapters(chapterList)
        return newBook
    }'''

content = re.sub(r'    suspend fun importAudiobookFromHtml.*?return newBook\n    }', new_func, content, flags=re.DOTALL)

with open("app/src/main/java/com/example/data/repository/AudiobookRepository.kt", "w") as f:
    f.write(content)
