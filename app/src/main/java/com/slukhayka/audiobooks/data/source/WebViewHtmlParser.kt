package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.CatalogParser
import java.net.URL

/**
 * Spec-14 T4 — the single 4read book-page parser, shared by every import door.
 *
 * Maps a 4read book-page DOM to [SourceBookDetail] — the enriched profile the
 * seam now provides (title/author, cover, narrator, chapters, duration,
 * rating, genres, series, related). Purely: no network, no Android — playlist
 * and iframe content is resolved through [resolveContent], so the caller owns
 * transport (server-fetch door: `HttpFetcher`; WebView door: the captured
 * session). One parser, two doors — no third parser variant.
 *
 * Fields the page does not carry are absent (null/empty), never fabricated.
 */
class WebViewHtmlParser {

    /**
     * Parses a 4read book page. [html] is the page DOM, [url] its address
     * (used to resolve relative audio paths and as the fallback title
     * source). [resolveContent] fetches a referenced resource (playerjs
     * playlist, iframe page); the default returns empty so pure DOM fixtures
     * need no transport. Never throws.
     */
    fun parse(
        html: String,
        url: String,
        resolveContent: (String) -> String = { "" }
    ): SourceBookDetail {
        if (html.isBlank()) {
            return SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
        }

        val totalDurationSeconds = parsePageDuration(html)
        val author = parsePmovieText(html, "Автор") ?: ""
        val narrator = parsePmovieText(html, "Читає") ?: ""
        // Spec-15 T5 + #265: the FULL annotation lives in the body container
        // (itemprop="description", ~800 chars of real blurbs); DLE crops
        // og:description to ~295. Fallback keeps the meta path for pages
        // without the container.
        val description = parseItempropDescription(html)
            .ifBlank { ogMeta(html, "og:description")?.trim().orEmpty() }
        val genres = parsePmovieGenres(html)
        val rating = parseRatingScore(html)
        val series = parsePmovieCycle(html)
        val related = CatalogParser.parseRelatedBooks(html).map { relatedBook ->
            RelatedBook(
                title = relatedBook.title,
                author = relatedBook.author,
                url = relatedBook.url,
                coverImageUrl = relatedBook.coverImageUrl
            )
        }

        val audioStreams = mutableListOf<String>()

        // Cover: og:image first, then the visible poster image.
        var coverUrl: String? = null
        val ogMatch = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"\s*>""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta\s+content="([^"]+)"\s+property="og:image"""", RegexOption.IGNORE_CASE).find(html)
        if (ogMatch != null) {
            coverUrl = ogMatch.groupValues[1]
        }
        if (coverUrl.isNullOrBlank()) {
            val imgMatch = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"\s*>""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<img[^>]+src="([^"]*4read\.org/[^"]+\.(?:jpg|png|webp|jpeg))"""", RegexOption.IGNORE_CASE).find(html)
            if (imgMatch != null) {
                coverUrl = imgMatch.groupValues[1]
            }
        }
        if (!coverUrl.isNullOrBlank() && !coverUrl.startsWith("http")) {
            coverUrl = if (coverUrl.startsWith("/")) "https://4read.org$coverUrl" else "https://4read.org/$coverUrl"
        }

        extractAudioFromHtml(html, url, audioStreams)

        // The 4read manifest can be read by its WebView session while a
        // separate OkHttp request receives 403. WebSourceBrowser appends the
        // session-fetched manifest in this bounded, base64-safe marker. When
        // present it is the authoritative ordered topology; ignore the
        // player's transient current-MP3 nodes and original .m3u reference.
        val capturedManifestTracks = capturedPlaylistStreams(html)
        if (capturedManifestTracks.isNotEmpty()) {
            audioStreams.clear()
            audioStreams.addAll(capturedManifestTracks)
        }

        // Player pages can live in an iframe (e.g. the playerjs embed).
        val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        iframeRegex.findAll(html).forEach { m ->
            val iframeSrc = m.groupValues[1]
            val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc
            else if (iframeSrc.startsWith("/")) "https://4read.org$iframeSrc"
            else "https://4read.org/$iframeSrc"
            if (!fullIframeUrl.contains("facebook") && !fullIframeUrl.contains("vk.com/widget")) {
                val iframeHtml = resolveContent(fullIframeUrl)
                if (iframeHtml.isNotEmpty()) {
                    extractAudioFromHtml(iframeHtml, fullIframeUrl, audioStreams)
                }
            }
        }

        // Expand playlist references (.m3u / .txt / playerjs JSON) into their
        // chapter stream URLs.
        // A declared playlist is the book's chapter topology. 4read also
        // leaves the currently playing MP3 in the DOM; it must not turn into
        // an extra Chapter beside the full playlist (#443).
        val declaredPlaylist = capturedManifestTracks.isEmpty() && audioStreams.any(::isPlaylistUrl)
        val expandedStreams = mutableListOf<String>()
        for (stream in audioStreams) {
            if (isPlaylistUrl(stream)) {
                val playlistContent = resolveContent(stream)
                if (playlistContent.isNotEmpty()) {
                    if (playlistContent.trim().startsWith("[{")) {
                        jsonFileRegex.findAll(playlistContent).forEach { m ->
                            expandedStreams.add(encodeUrl(m.groupValues[1]))
                        }
                    } else if (playlistContent.contains("\"file\"")) {
                        // #476 — the live manifest is not always a bare track
                        // array: an object-wrapped or pretty-printed JSON
                        // playlist carries the same "file" entries. The key
                        // pattern (colon + quoted URL) keeps challenge pages
                        // out — they never pair the key with a value.
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
                    // A playlist we could not read is never a substitute for
                    // its chapters. Refuse the partial import honestly.
                    expandedStreams.clear()
                    break
                }
            } else if (!declaredPlaylist) {
                expandedStreams.add(stream)
            }
        }
        audioStreams.clear()
        audioStreams.addAll(expandedStreams)

        // YouTube-embed fallback (spec 2026-08-26): a page with NO direct
        // audio at all — its narration lives on YouTube, embedded in the
        // page. One embed = one chapter; the persisted track locator stays
        // the watch URL and resolves to a real audio stream at play/download
        // time (signed stream URLs expire; they are never stored). A page
        // WITH playerjs audio never reaches this branch — direct audio wins,
        // the embed adds nothing.
        if (audioStreams.isEmpty() && !declaredPlaylist) {
            YOUTUBE_VIDEO_ID.findAll(html).forEach { m ->
                audioStreams.add("https://www.youtube.com/watch?v=${m.groupValues[1]}")
            }
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
            related = related,
            description = description
        )
    }

    /**
     * #265/#267 — the full book annotation from the body container
     * `<div ... itemprop="description">`: its paragraphs up to the curated
     * tail markers («Теги», «Телеграм канал», «Подякувати», «Ютуб канал»,
     * PayPal), tags and entities stripped, whitespace collapsed.
     *
     * #267 — two hardening rules proven on live pages:
     * - the scan stops at the container's MATCHING `</div>` (depth-counted,
     *   nested divs included): user comments and series lists that follow the
     *   container never leak into the description even when no marker
     *   paragraph exists;
     * - a marker-bearing paragraph is cut at the EARLIEST marker position,
     *   not by list order — «Телеграм канал автора t.me/… Подякувати…» must
     *   cut before «Телеграм», not after it.
     *
     * Absent/malformed container → empty (the caller falls back to
     * og:description). Never throws.
     */
    private fun parseItempropDescription(html: String): String {
        val (bodyStart, close) = itempropDescriptionContainer(html) ?: return ""
        val result = StringBuilder()
        for (match in paragraphRegex.findAll(html.substring(bodyStart, close))) {
            var text = stripTags(match.groupValues[1], " ")
                .let { decodeEntities(it) }
                .replace(WHITESPACE_REGEX, " ")
                .trim()
            if (text.isEmpty()) continue
            val kept = cutAtEarliestMarker(text, TAIL_MARKERS)
            if (kept != null) {
                if (kept.isNotEmpty()) {
                    if (result.isNotEmpty()) result.append("\n")
                    result.append(kept)
                }
                break
            }
            if (result.isNotEmpty()) result.append("\n")
            result.append(text)
            if (result.length >= MIN_FULL_ANNOTATION) break
        }
        return result.toString().trim()
    }

    /** Query parameters carry session tokens on live 4read playlist URLs. */
    private fun isPlaylistUrl(url: String): Boolean {
        val path = runCatching { URL(url).path }.getOrDefault(url.substringBefore('?'))
        return path.endsWith(".m3u", ignoreCase = true) || path.endsWith(".txt", ignoreCase = true)
    }

    private fun capturedPlaylistStreams(html: String): List<String> =
        CAPTURED_PLAYLIST.findAll(html).flatMap { match ->
            runCatching {
                String(java.util.Base64.getDecoder().decode(match.groupValues[1]), Charsets.UTF_8)
            }.getOrDefault("").lineSequence()
                .map(String::trim)
                .filter { it.startsWith("http") }
                .map(::encodeUrl)
        }.distinct().toList()

    private companion object {
        /** A full annotation is around this size — longer scanning is waste. */
        const val MIN_FULL_ANNOTATION = 1200

        /** Every playerjs JSON playlist shape carries chapter URLs under this key. */
        private val jsonFileRegex = Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        /**
         * Spec 2026-08-26 — every YouTube video locator a 4read page can
         * carry: embed iframes (incl. the nocookie variant), youtu.be short
         * links and plain watch URLs. The id is the single group; the
         * persisted track locator normalises to one watch-URL shape.
         */
        private val YOUTUBE_VIDEO_ID = Regex(
            """(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=)|youtu\.be/)([A-Za-z0-9_-]{8,})""",
            RegexOption.IGNORE_CASE
        )

        private val paragraphRegex =
            Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val CAPTURED_PLAYLIST = Regex("""data-slukhayka-playlist=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        /** 4read's captured DOM can encode `&` as either `&amp;` or `&amp%3B`. */
        private val HTML_QUERY_SEPARATOR = Regex("""&amp(?:;|%3b)""", RegexOption.IGNORE_CASE)
        private val TAIL_MARKERS = listOf("Теги#", "Теги", "Телеграм канал", "Подякувати", "Ютуб канал", "PayPal")

        /** Percent-encoding alphabet for [encodeUrl]. */
        const val HEX = "0123456789ABCDEF"
    }

    /** The book page's real total duration (formats `10:57:18` / `53:42`). */
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

    /** Visible text of a `pmovie__list` entry by label (e.g. «Автор:»). */
    private fun parsePmovieText(html: String, label: String): String? {
        val marker = Regex("""<span>\s*$label:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val clean = stripTags(marker)
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .trim()
        return clean.ifBlank { null }
    }

    /** Genres from the «Жанр:» entry, keeping the two most specific. */
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

    /** Real rating score from `pmovie__rating-score`; null when absent. */
    private fun parseRatingScore(html: String): Double? {
        return Regex("""pmovie__rating-score[^"]*">\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
    }

    /** Series (cycle) entry, e.g. «Цикл:» + volume number; null when absent. */
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
        if (!ogTitle.isNullOrBlank()) {
            // 4read's OpenGraph title is a page title, not a Work title:
            // it appends this stable site suffix. Keeping it would make a
            // captured browser recovery fail its exact Work identity guard.
            return ogTitle.removeSuffix(" - АудіоКниги Українською").trim()
        }
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
     * JVM-safe percent-encoding with the repository's historical allowed set.
     *
     * Only ASCII bytes (0x00-0x7F) may pass through as letters/digits/allowed
     * punctuation. Every byte >= 0x80 is percent-encoded unconditionally: a
     * Latin-1 re-mapping like `(0xD0).toChar()` = 'Ð' would otherwise pass
     * `isLetterOrDigit()` and leave Cyrillic raw in the URL (found on-device
     * in spec-14 T6: chapter files with Cyrillic names 403'd because the path
     * arrived mangled as `Ð%94...` instead of `%D0%94...`).
     */
    private fun encodeUrl(url: String): String {
        val normalizedUrl = HTML_QUERY_SEPARATOR.replace(url, "&")
        val allowed = "@#&=*+-_.,:!?()/~'%"
        val sb = StringBuilder(normalizedUrl.length)
        for (b in normalizedUrl.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80) {
                val ch = c.toChar()
                if (ch.isLetterOrDigit() || ch in allowed) {
                    sb.append(ch)
                } else {
                    sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
                }
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }
}
