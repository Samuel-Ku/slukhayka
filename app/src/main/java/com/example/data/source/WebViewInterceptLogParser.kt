package com.example.data.source

/**
 * Spec-13 T2 - the interception-layer parser seam.
 *
 * The #71/#78 prototype logs every WebView request in a stable line format,
 * one request per line:
 *
 *   [REQ] sub  https://cdn.redirectto.cc/s05/.../track-0.mp3 hdrs={Range=bytes=0-}
 *
 * The player issues several range requests per track (bytes=0-, 32768-, ...),
 * so the raw log has repeats. [audioUrlsInOrder] collapses them into the
 * ordered, distinct chapter URLs; [chaptersFromUrls] maps that to the
 * [SourceChapter] list the import path expects. Production interception
 * (T3's shouldInterceptRequest) hands URLs directly and reuses
 * [chaptersFromUrls]; the log functions exist so the #71/#78 captures are a
 * real fixture for the parser.
 */
object WebViewInterceptLogParser {

    /** One intercepted request: its URL and whether a Range header was present. */
    data class AudioRequest(val url: String, val rangeRequested: Boolean)

    /**
     * Parses the [REQ] lines of a prototype log into audio requests, in first-
     * appearance order. A request is audio when its URL ends in a known audio
     * extension (mp3/m4a/m4b/ogg/opus/aac) or its headers carry an
     * audio-MIME Accept type.
     */
    fun audioRequests(logText: String): List<AudioRequest> =
        REQ_LINE.findAll(logText)
            .map { m ->
                val url = m.groupValues[1].trim()
                val hdrs = m.groupValues[2]
                // containsMatchIn, NOT matches: the URL is a full https://…
                // string and the pattern only pins the trailing extension.
                val audioByExt = AUDIO_EXT.containsMatchIn(url)
                val audioByType = Regex("""Accept\s*=\s*audio/""").containsMatchIn(hdrs)
                val range = Regex("""Range\s*=\s*bytes=""", RegexOption.IGNORE_CASE).containsMatchIn(hdrs)
                Triple(url, audioByExt || audioByType, range)
            }
            .filter { it.second }
            .map { (url, _, range) -> AudioRequest(url, range) }
            .toList()

    /**
     * The distinct audio URLs in first-appearance order - one per chapter.
     * Repeats (range requests, retries) collapse to the first occurrence.
     */
    fun audioUrlsInOrder(logText: String): List<String> {
        val seen = LinkedHashSet<String>()
        audioRequests(logText).forEach { seen.add(it.url) }
        return seen.toList()
    }

    /** Whether any audio request carried a Range header (seekable streaming). */
    fun usedRangeRequests(logText: String): Boolean =
        audioRequests(logText).any { it.rangeRequested }

    /**
     * Maps ordered audio URLs to playable chapters. Distinct + order preserved;
     * the title falls back to «Глава N» (the app's existing convention) since
     * the intercept layer only sees URLs, not the site's chapter titles.
     */
    fun chaptersFromUrls(urls: List<String>): List<SourceChapter> {
        val seen = LinkedHashSet<String>()
        return urls
            .filter { seen.add(it) }
            .mapIndexed { index, url ->
                SourceChapter(title = "Глава ${index + 1}", streamUrl = url)
            }
    }

    private val REQ_LINE = Regex(
        """\[REQ\]\s+(?:MAIN|sub)\s+(\S+)\s+hdrs=\{(.*?)\}""",
        RegexOption.IGNORE_CASE
    )

    private val AUDIO_EXT = Regex("""\.(?:mp3|m4a|m4b|ogg|opus|aac)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
}
