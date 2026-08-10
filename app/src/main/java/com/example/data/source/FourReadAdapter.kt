package com.example.data.source

import com.example.data.catalog.CatalogParser
import java.net.URL

/**
 * The 4read.org [SourceAdapter] (spec-10 T3). This is a behavior-neutral port
 * of the parsing that used to live in `AudiobookRepository` (fetch4ReadPageDetails,
 * extractAudioFromHtml, and the search-result link regex) — same regexes, same
 * `{v1}` obfuscation handling, same m3u/txt playlist expansion, same iframe
 * recursion. The repository now delegates to this adapter, so the existing
 * fixture/Room tests pin the behavior.
 */
class FourReadAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://4read.org/")
) : SourceAdapter {

    override val sourceId: String = "4read"

    override suspend fun search(query: String): List<SourceBook> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "https://4read.org/index.php?do=search&subaction=search&story=$encodedQuery"
        val html = fetcher.getText(searchUrl)
        if (html.isEmpty()) return emptyList()

        val books = mutableListOf<SourceBook>()
        val addedSlugs = mutableSetOf<String>()
        val linkRegex = Regex("""<a\s+href="(https?://4read\.org/([^"]+)\.html)"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
        for (match in linkRegex.findAll(html)) {
            val fullUrl = match.groupValues[1]
            val slug = match.groupValues[2]
            val rawTitle = match.groupValues[3].trim()
            if (slug.contains("index") || slug.contains("page") || rawTitle.length < 3 || !addedSlugs.add(slug)) {
                continue
            }
            val cleanTitle = rawTitle
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
            books.add(
                SourceBook(
                    title = cleanTitle,
                    author = "4read.org",
                    url = fullUrl,
                    sourceId = sourceId
                )
            )
        }
        return books
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        var coverUrl: String? = null
        val audioStreams = mutableListOf<String>()
        val html = fetcher.getText(url)
        if (html.isNotEmpty()) {
            val ogMatch = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"\s*>""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta\s+content="([^"]+)"\s+property="og:image"""", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                coverUrl = ogMatch.groupValues[1]
            }
            if (coverUrl.isNullOrBlank()) {
                val imgMatch = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"\s*>""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<img[^>]+src="([^"]*4read\.org/[^"]+\.(?:jpg|png|webp|jpeg))""", RegexOption.IGNORE_CASE).find(html)
                if (imgMatch != null) {
                    coverUrl = imgMatch.groupValues[1]
                }
            }
            if (!coverUrl.isNullOrBlank() && !coverUrl.startsWith("http")) {
                coverUrl = if (coverUrl.startsWith("/")) "https://4read.org$coverUrl" else "https://4read.org/$coverUrl"
            }

            extractAudioFromHtml(html, url, audioStreams)

            // Player pages can live in an iframe (e.g. the playerjs embed).
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            iframeRegex.findAll(html).forEach { m ->
                val iframeSrc = m.groupValues[1]
                val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc
                else if (iframeSrc.startsWith("/")) "https://4read.org$iframeSrc"
                else "https://4read.org/$iframeSrc"
                if (!fullIframeUrl.contains("facebook") && !fullIframeUrl.contains("vk.com/widget")) {
                    val iframeHtml = fetcher.getText(fullIframeUrl)
                    if (iframeHtml.isNotEmpty()) {
                        extractAudioFromHtml(iframeHtml, fullIframeUrl, audioStreams)
                    }
                }
            }

            // Expand playlist references (.m3u / .txt / playerjs JSON) into their
            // chapter stream URLs.
            val expandedStreams = mutableListOf<String>()
            for (stream in audioStreams) {
                if (stream.endsWith(".m3u") || stream.endsWith(".txt")) {
                    val playlistContent = fetcher.getText(stream)
                    if (playlistContent.isNotEmpty()) {
                        if (playlistContent.trim().startsWith("[{")) {
                            val jsonFileRegex = Regex("""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                            jsonFileRegex.findAll(playlistContent).forEach { m ->
                                expandedStreams.add(encodeUrl(m.groupValues[1]))
                            }
                        } else {
                            playlistContent.split("\n").forEach { line ->
                                val cleanLine = line.trim()
                                if (cleanLine.startsWith("http")) {
                                    expandedStreams.add(encodeUrl(cleanLine))
                                }
                            }
                        }
                    } else {
                        expandedStreams.add(stream)
                    }
                } else {
                    expandedStreams.add(stream)
                }
            }
            audioStreams.clear()
            audioStreams.addAll(expandedStreams)
        }

        val chapters = audioStreams.distinct().mapIndexed { index, audioUrl ->
            SourceChapter(
                title = "Глава ${index + 1}",
                streamUrl = audioUrl
            )
        }
        return SourceBookDetail(
            title = titleFromPage(html, url),
            author = "4read.org",
            narrator = "4read Voice Narrator",
            url = url,
            coverImageUrl = coverUrl,
            chapters = chapters
        )
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val html = fetcher.getText("https://4read.org/")
        if (html.isEmpty()) return emptyList()
        return CatalogParser.parseHomepage(html)
            .flatMap { it.books }
            .take(limit)
            .map { book ->
                SourceBook(
                    title = book.title,
                    author = book.author,
                    url = book.url,
                    coverImageUrl = book.coverImageUrl,
                    seriesTitle = book.seriesTitle,
                    seriesIndex = book.seriesIndex,
                    sourceId = sourceId
                )
            }
    }

    private fun titleFromPage(html: String, url: String): String {
        val ogTitle = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.trim()
        if (!ogTitle.isNullOrBlank()) return ogTitle
        val slug = url.substringAfterLast('/').removeSuffix(".html")
        return slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            }
            .ifBlank { "Аудиокнига 4read" }
    }

    /**
     * Collects audio URLs from raw page HTML: direct audio URLs, relative
     * /uploads paths, playerjs `file:` variables (with the `{v1}` obfuscation
     * 4read uses), and HTML5 `<audio>/<source>` tags.
     */
    private fun extractAudioFromHtml(html: String, baseUrl: String, resultList: MutableList<String>) {
        // A. Direct mp3, m4a, ogg, aac, m3u8 URLs
        val mp3Regex = Regex("""(https?://[^"'\s\n<>]+\.(?:mp3|m4a|ogg|aac|m3u8)(?:\?[^"'\s\n<>]*)?)""", RegexOption.IGNORE_CASE)
        mp3Regex.findAll(html).forEach { m ->
            val audioUrl = m.groupValues[1]
            if (!audioUrl.contains("favicon") && !audioUrl.contains("logo")) {
                resultList.add(encodeUrl(audioUrl))
            }
        }

        // B. Relative audio paths like /uploads/files/...mp3 or /uploads/audio/...
        val relativeAudioRegex = Regex("""["'](/uploads/[^"'\s\n<>]+\.(?:mp3|m4a|ogg|aac|m3u8)(?:\?[^"'\s\n<>]*)?)["']""", RegexOption.IGNORE_CASE)
        relativeAudioRegex.findAll(html).forEach { m ->
            val rel = m.groupValues[1]
            val full = if (baseUrl.startsWith("http")) {
                try {
                    val u = URL(baseUrl)
                    "${u.protocol}://${u.host}$rel"
                } catch (_: Exception) {
                    "https://4read.org$rel"
                }
            } else {
                "https://4read.org$rel"
            }
            resultList.add(encodeUrl(full))
        }

        // C. PlayerJS / Uppod JS variables: file: "..."
        val fileJsRegex = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        fileJsRegex.findAll(html).forEach { m ->
            var rawFile = m.groupValues[1]
            // Decode {v1} obfuscation pattern used by 4read
            rawFile = rawFile.replace("{v1}", "https://4read.org/m3u/")
            if (rawFile.contains(".mp3") || rawFile.contains(".m4a") || rawFile.contains(".m3u8") ||
                rawFile.contains(".m3u") || rawFile.contains(".txt") || rawFile.contains("/audio/")
            ) {
                rawFile.split(",", ";").forEach { piece ->
                    val clean = piece.trim()
                    if (clean.startsWith("http")) {
                        resultList.add(encodeUrl(clean))
                    } else if (clean.startsWith("/")) {
                        resultList.add(encodeUrl("https://4read.org$clean"))
                    }
                }
            }
        }

        // D. HTML5 <audio> / <source> tags
        val sourceRegex = Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        sourceRegex.findAll(html).forEach { m ->
            val src = m.groupValues[1]
            val full = if (src.startsWith("http")) src
            else if (src.startsWith("/")) "https://4read.org$src"
            else "https://4read.org/$src"
            resultList.add(encodeUrl(full))
        }
    }

    /**
     * JVM-safe equivalent of the repository's `android.net.Uri.encode` with the
     * same allowed set, so stream URLs keep their percent-encoding of spaces
     * and non-ASCII characters.
     */
    private fun encodeUrl(url: String): String {
        val allowed = "@#&=*+-_.,:!?()/~'%"
        val sb = StringBuilder(url.length)
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch in allowed) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }
}
