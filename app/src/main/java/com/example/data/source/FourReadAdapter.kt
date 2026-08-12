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

        // 4read renders each hit as a .poster block: the real Cyrillic title
        // in poster__title and the real author in the first poster__subtitle
        // (the second one carries the duration clock). Real authors matter for
        // the cross-source merge — a "4read.org" placeholder could never match
        // the same Work on sound-books.net.
        val books = mutableListOf<SourceBook>()
        val addedSlugs = mutableSetOf<String>()
        val posterStarts = POSTER_START.findAll(html).map { it.range.first }.toList()
        for (i in posterStarts.indices) {
            val from = posterStarts[i]
            val to = if (i + 1 < posterStarts.size) posterStarts[i + 1] else html.length
            val block = html.substring(from, to)

            val linkMatch = POSTER_LINK.find(block) ?: continue
            val fullUrl = linkMatch.groupValues[1]
            val slug = linkMatch.groupValues[2]
            val rawTitle = POSTER_TITLE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (slug.contains("index") || slug.contains("page") || rawTitle.length < 3 || !addedSlugs.add(slug)) {
                continue
            }
            val author = POSTER_SUBTITLE.findAll(block)
                .map { stripTags(it.groupValues[1]).trim() }
                .firstOrNull { it.isNotBlank() }
                ?: ""
            val cover = POSTER_IMG.find(block)?.groupValues?.get(1)
                ?.takeIf { it.contains("/uploads/posts/") }
            books.add(
                SourceBook(
                    title = decodeEntities(rawTitle),
                    author = author,
                    url = fullUrl,
                    coverImageUrl = cover?.let { if (it.startsWith("http")) it else "https://4read.org$it" },
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
        // Spec-14 T1: the full book profile is parsed HERE — the only module
        // that knows 4read markup. Fields the page does not carry are absent
        // (null/empty), never fabricated.
        val totalDurationSeconds = if (html.isNotEmpty()) parsePageDuration(html) else null
        val author = if (html.isNotEmpty()) parsePmovieText(html, "Автор") ?: "" else ""
        val narrator = if (html.isNotEmpty()) parsePmovieText(html, "Читає") ?: "" else ""
        val genres = if (html.isNotEmpty()) parsePmovieGenres(html) else emptyList()
        val rating = if (html.isNotEmpty()) parseRatingScore(html) else null
        val series = if (html.isNotEmpty()) parsePmovieCycle(html) else null
        val related = if (html.isNotEmpty()) CatalogParser.parseRelatedBooks(html).map { relatedBook ->
            RelatedBook(
                title = relatedBook.title,
                author = relatedBook.author,
                url = relatedBook.url,
                coverImageUrl = relatedBook.coverImageUrl
            )
        } else emptyList()
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
            author = author,
            narrator = narrator,
            url = url,
            coverImageUrl = coverUrl,
            chapters = chapters,
            totalDurationSeconds = totalDurationSeconds,
            rating = rating,
            genres = genres,
            series = series,
            related = related
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

    /**
     * The book page's real total duration from either the visible "Триває:"
     * field or the schema.org meta tag (formats `10:57:18` and `53:42`).
     * Null when the page does not carry one — never a fabricated default.
     */
    private fun parsePageDuration(html: String): Long? {
        val raw = Regex("""(?:itemprop="duration"\s+content="|Триває:</span>\s*)(\d{1,2}:\d{2}(?::\d{2})?)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val parts = raw.split(":").map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            else -> null
        }
    }

    /**
     * Visible text of a `pmovie__list` entry by label, e.g.
     * `<li><span>Автор:</span> … <a>Роберт Сальваторе</a></li>` →
     * "Роберт Сальваторе". Strips HTML and the label itself; unescapes
     * entities. Null when the entry or a readable value is missing.
     */
    private fun parsePmovieText(html: String, label: String): String? {
        val marker = Regex("""<span>\s*$label:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val clean = Regex("""<[^>]+>""").replace(marker, "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .trim()
        return clean.ifBlank { null }
    }

    /**
     * Genres from the "Жанр:" entry — a chain of links separated by " / "
     * (e.g. "Світова література / Пригоди / Фентезі"). Keeps the two most
     * specific categories (the first is usually the broad "Світова
     * література"), matching the repository's historical rendering; empty
     * when absent.
     */
    private fun parsePmovieGenres(html: String): List<String> {
        val marker = Regex("""<span>\s*Жанр:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        val genres = Regex(""">([^<]+)</a>""").findAll(marker)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals("Жанр", ignoreCase = true) }
            .toList()
        return genres.drop(1).ifEmpty { genres.take(1) }
    }

    /** Real rating score from `pmovie__rating-score` (e.g. 4.9); null when absent. */
    private fun parseRatingScore(html: String): Double? {
        return Regex("""pmovie__rating-score[^"]*\">\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
    }

    /**
     * Series (cycle) entry, e.g.
     * `<li …><span>Цикл:</span> <a href="https://4read.org/xfsearch/cikl/slug/">Сага про Дріззта До'Урдена</a>
     * (<span itemprop="volumeNumber">7</span>)</li>` → SeriesRef("Сага про
     * Дріззта До'Урдена", 7, url). Null when there is no cycle.
     */
    private fun parsePmovieCycle(html: String): SeriesRef? {
        val block = Regex("""<span>\s*Цикл:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val anchor = Regex("""<a\s+href="([^"]+)"[^>]*>([^<]+)</a>""").find(block) ?: return null
        val name = anchor.groupValues[2]
            .replace("&#039;", "'")
            .trim()
            .ifBlank { return null }
        val href = anchor.groupValues[1]
        val index = Regex("""volumeNumber">\s*([0-9]+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
        return SeriesRef(name = name, position = index, url = href)
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

    private fun stripTags(html: String): String = html.replace(Regex("""<[^>]+>"""), "")

    private fun decodeEntities(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&#039;", "'")

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
        val POSTER_START = Regex("""<div class="poster\b""", RegexOption.IGNORE_CASE)
        val POSTER_LINK = Regex("""href="(https?://4read\.org/([^"]+)\.html)"""", RegexOption.IGNORE_CASE)
        val POSTER_TITLE = Regex("""poster__title[^>]*>\s*([^<]+?)\s*<""", RegexOption.IGNORE_CASE)
        val POSTER_SUBTITLE = Regex(
            """poster__subtitle[^>]*>\s*(.*?)\s*</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val POSTER_IMG = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
    }
}
