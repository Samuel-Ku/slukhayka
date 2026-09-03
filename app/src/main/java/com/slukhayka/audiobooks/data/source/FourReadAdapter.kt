package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.CatalogParser

/**
 * The 4read.org [SourceAdapter] (spec-10 T3). Transport + search/fetchNew;
 * the book page itself is parsed by the shared [WebViewHtmlParser] module
 * (spec-14 T4) — one parser for the server-fetch door here and the WebView
 * door, no third parser variant. The search-result poster parse lives here
 * (same regexes as before); fixture/Room tests pin the behavior.
 */
class FourReadAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://4read.org/"),
    /**
     * Spec-42 #427 — host-aware Cookie provider. FourRead's main host is
     * `4read.org` and its allowed audio hosts are `reasd.org` subdomains;
     * each host's cookie stays isolated (no copying `4read.org` cookie onto a
     * `reasd.org` request). Default empty keeps the adapter pure-JVM; prod
     * wires [AndroidSourceCookieProvider] once in the composition root.
     */
    private val cookieProvider: SourceCookieProvider = object : SourceCookieProvider {
        override fun cookieFor(url: String): String = ""
    }
) : SourceAdapter {

    override val sourceId: String = "4read"
    override val accessMode: SourceAccessMode = SourceAccessMode.BROWSER

    /** The "4read-slug" id scheme — in exactly this one place (spec-14 T5). */
    override fun bookId(url: String): String = CatalogParser.bookId(url)

    override suspend fun search(query: String): List<SourceBook> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "https://4read.org/index.php?do=search&subaction=search&story=$encodedQuery"
        val cookie = cookieProvider.cookieFor(searchUrl).trim()
        val html = if (cookie.isBlank()) fetcher.getText(searchUrl) else fetcher.getText(searchUrl, mapOf("Cookie" to cookie))
        if (html.isEmpty()) return emptyList()

        // 4read renders each hit as a .poster block. In the current search
        // layout the first subtitle is a genre trail and the next non-empty
        // subtitle is the author. A title-only genre row must never become a
        // fabricated author, or the result cannot merge with another Source.
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
            // Live 4read search results put genres in the FIRST poster__subtitle
            // and the author in the SECOND. Taking the first would store genres
            // as the author and break the cross-source "title|author" merge.
            // When only one subtitle is present (genres), leave the author empty
            // rather than claiming genres as the author.
            val author = POSTER_SUBTITLE.findAll(block)
                .map { stripTags(it.groupValues[1]).replace(Regex("""\s+"""), " ").trim() }
                .filter { it.isNotBlank() }
                .let { subs -> if (subs.count() >= 2) subs.elementAt(1) else "" }
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
        // Spec-14 T4: the single 4read page parser lives in the pure
        // WebViewHtmlParser module, shared by every import door. The adapter
        // only owns transport (HttpFetcher, with the source Referer) — the
        // page profile, cover and playlist expansion are pure DOM work.
        // Spec-42 #427 — host-aware cookie: read just-in-time for the concrete
        // [url] host, never copied to another host (e.g. reasd.org audio).
        val cookie = cookieProvider.cookieFor(url).trim()
        val html = if (cookie.isBlank()) fetcher.getText(url) else fetcher.getText(url, mapOf("Cookie" to cookie))
        // Spec-40 #282 — visitors' comments ride the SAME response: parsed
        // once from the html this detail was built from, never a second fetch.
        return WebViewHtmlParser().parse(html, url, resolveContent = { urlToResolve ->
            // Playlist / iframe resolves also stay host-aware: only attach cookie
            // when the resolved host is the same as the page host's allowlist.
            val c = cookieProvider.cookieFor(urlToResolve).trim()
            if (c.isBlank()) fetcher.getText(urlToResolve) else fetcher.getText(urlToResolve, mapOf("Cookie" to c))
        })
            .copy(visitorComments = parseComments(html))
    }

    /**
     * Spec-14 T5 / ADR-0006 — the WebView door's captured page, parsed by
     * the same shared [WebViewHtmlParser] with this adapter's transport
     * resolving playlist/iframe content. The repository performs no 4read
     * parsing or transport — it hands the captured DOM straight to the seam.
     * Spec-42 #427 — playlist resolves are host-aware (no cross-host cookie).
     */
    override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail =
        WebViewHtmlParser().parse(html, url, resolveContent = { toResolve ->
            // 4read serves its M3U manifests publicly. Passing a WebView
            // session to this endpoint can turn a valid playlist into a
            // challenge document that itself mentions `.mp3`; therefore no
            // content heuristic can safely distinguish it from the manifest.
            // Fetch the public manifest without a session, while all other
            // page/iframe requests retain the host-scoped cookie policy.
            //
            // #476 — under a hardened Cloudflare posture the session-less
            // fetch itself returns the challenge (no playlist evidence at
            // all). Only then retry once WITH the host cookie: the captured
            // page already passed the browser gate, and a challenge document
            // mentioning `.mp3` still takes the first branch, exactly as in
            // #443 — the fallback cannot promote a poisoned read.
            if (isPublicPlaylistUrl(toResolve)) {
                val open = fetcher.getText(toResolve)
                if (open.hasPlaylistEvidence()) open
                else {
                    val c = cookieProvider.cookieFor(toResolve).trim()
                    if (c.isBlank()) open
                    else runCatching { fetcher.getText(toResolve, mapOf("Cookie" to c)) }.getOrDefault(open)
                }
            } else {
                val c = cookieProvider.cookieFor(toResolve).trim()
                val withSession = if (c.isBlank()) fetcher.getText(toResolve)
                else fetcher.getText(toResolve, mapOf("Cookie" to c))
                // A playlist is public on 4read, while a stale browser cookie can
                // make its CDN reply with an empty/challenge page. The captured
                // page already passed the browser gate; retrying this read without
                // a cookie restores a public nested resource without sending a
                // cookie anywhere else (#443).
                if (c.isBlank() || withSession.contains(".mp3", ignoreCase = true)) {
                    withSession
                } else {
                    fetcher.getText(toResolve)
                }
            }
        })

    /** A playlist resolve carries chapter topology when it names tracks. */
    private fun String.hasPlaylistEvidence(): Boolean =
        contains(".mp3", ignoreCase = true) || contains("\"file\"")

    private fun isPublicPlaylistUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u", ignoreCase = true) &&
            runCatching { java.net.URI(url).host?.equals("4read.org", ignoreCase = true) == true }.getOrDefault(false)

    /**
     * Spec-40 #282 — коментарі відвідувачів 4read. The page's DLE comment
     * tree (the `page__comments-list` container; replies nest inside their
     * parent `<li>`) carries every comment's text in a dedicated
     * `<div id='comm-id-N'>…</div>` block. The scan walks those blocks in
     * document order (parents before their replies), strips tags and
     * entities, collapses whitespace, drops blanks, truncates each text to
     * [MAX_COMMENT_LENGTH] and stops at [MAX_COMMENTS] — bounded output for
     * an unbounded page. No container / no comment blocks → empty (absent,
     * never fabricated). Same pure parse helpers as the rest of the adapter;
     * never throws.
     */
    override suspend fun parseComments(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val scope = commentsScope(html)
        val result = mutableListOf<String>()
        for (open in COMMENT_TEXT_DIV.findAll(scope)) {
            if (result.size >= MAX_COMMENTS) break
            val bodyStart = open.range.last + 1
            var depth = 1
            var close = -1
            for (boundary in DIV_BOUNDARY.findAll(scope, bodyStart)) {
                if (boundary.value[1] == '/') {
                    depth--
                    if (depth == 0) {
                        close = boundary.range.first
                        break
                    }
                } else {
                    depth++
                }
            }
            if (close < 0) continue
            val text = stripTags(scope.substring(bodyStart, close), " ")
                .let { decodeEntities(it) }
                .replace(WHITESPACE_REGEX, " ")
                .trim()
            if (text.isEmpty()) continue
            result += if (text.length > MAX_COMMENT_LENGTH) text.take(MAX_COMMENT_LENGTH).trim() else text
        }
        return result
    }

    /**
     * The comment-tree interval to scan: the `page__comments-list`
     * container's full body (depth-counted close, so nested reply lists stay
     * inside), or the whole page when the container is absent — the
     * `comm-id-N` marker is specific enough to scan safely either way.
     */
    private fun commentsScope(html: String): String {
        val open = COMMENTS_CONTAINER_OPEN.find(html) ?: return html
        val bodyStart = open.range.last + 1
        var depth = 1
        for (boundary in DIV_BOUNDARY.findAll(html, bodyStart)) {
            if (boundary.value[1] == '/') {
                depth--
                if (depth == 0) return html.substring(bodyStart, boundary.range.first)
            } else {
                depth++
            }
        }
        return html.substring(bodyStart)
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

    private fun decodeEntities(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&#039;", "'")

    private companion object {
        val POSTER_START = Regex("""<div class="poster\b""", RegexOption.IGNORE_CASE)
        val POSTER_LINK = Regex("""href="(https?://4read\.org/([^"]+)\.html)"""", RegexOption.IGNORE_CASE)
        val POSTER_TITLE = Regex("""poster__title[^>]*>\s*([^<]+?)\s*<""", RegexOption.IGNORE_CASE)
        val POSTER_SUBTITLE = Regex(
            """poster__subtitle[^>]*>\s*(.*?)\s*</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val POSTER_IMG = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)

        /** Spec-40 #282 — the comment tree container and its per-comment text divs. */
        const val MAX_COMMENTS = 100
        const val MAX_COMMENT_LENGTH = 500
        val COMMENTS_CONTAINER_OPEN =
            Regex("""<div\b[^>]*id="page__comments-list"[^>]*>""", RegexOption.IGNORE_CASE)
        val COMMENT_TEXT_DIV =
            Regex("""<div\s+id=['"]comm-id-\d+['"]\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DIV_BOUNDARY = Regex("""<div\b|</div""", RegexOption.IGNORE_CASE)
        val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
