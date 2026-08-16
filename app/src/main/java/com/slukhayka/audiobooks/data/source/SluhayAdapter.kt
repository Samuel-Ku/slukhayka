package com.slukhayka.audiobooks.data.source

/**
 * sluhay.com [SourceAdapter] (spec-13 T2; format verified live in the spec-13
 * T1 spike, 2026-08-12).
 *
 * Discovery on this source is WebView-only: sluhay.com sits behind Cloudflare,
 * so [search] stays empty — the browser surface (T3) finds the book in the
 * session, and the «Нове з Sluhay» row (T4) hydrates the homepage through the
 * session cookies (see [fetchNew]). What IS usable server-side is the audio
 * pipeline:
 *
 * - the book page HTML carries the playlist URL **inline** —
 *   `Playerjs({id:"playerjs1",file:"https://<hash>.redirectto.cc/s05/<id>.pl.txt"})`
 *   (no playlist-XHR reverse-engineering, unlike sluhayua);
 * - the playlist JSON yields the ordered track mp3s;
 * - the track CDN (`*.redirectto.cc`) 403s without `Referer: https://sluhay.com/`
 *   (plain nginx, NOT Cloudflare — a curl from another TLS stack with no
 *   cookies got 206 with the Referer and 403 without). The fetcher always
 *   sends it.
 *
 * Metadata: `og:title` = «Назва - Автор » <Site>» (suffix stripped), the
 * page's Назва/Автор/Тривалість rows carry the authoritative values, the
 * cover lives in the lazy-loaded `data-src` (there is NO og:image), and —
 * measured negative finding — **no narrator anywhere** (only a «Ютуб канал
 * диктора» link), so narrator stays empty.
 *
 * The «Нове з Sluhay» feed (T4) hydrates the homepage **through the live
 * WebView session**: [fetchNew] server-fetches the homepage WITH the session
 * cookies (T1 verdict: 200, cfChallenge=false — the user's session does the
 * Cloudflare gate, no bypass). Without a live session the fetch would 403, so
 * [fetchNew] stays empty and the feed pipeline shows the stale-session CTA.
 */
class SluhayAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://sluhay.com/"),
    /**
     * The live WebView session's cookies for the source domain (`cf_clearance`
     * etc. from the WebView jar). Default empty keeps the adapter pure-JVM;
     * production wires the [android.webkit.CookieManager].
     */
    private val cookieProvider: () -> String = { "" }
) : SourceAdapter {

    override val sourceId: String = "sluhay"

    /** Spec-13 T4: discovery is session-bound — the feed pipeline shows a CTA, never dead data. */
    override val sessionBound: Boolean = true

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    /**
     * The «Нове з Sluhay» feed: the homepage poster rows, hydrated through the
     * live WebView session's cookies (T1 verdict: server-fetch 200 with the
     * session, no DOM snapshot needed). Empty without a session or on a
     * blocked/stale fetch — the repository surfaces the CTA row then.
     */
    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val cookies = cookieProvider().trim()
        // No live session: Cloudflare would 403, so there is nothing to parse.
        if (cookies.isBlank()) return emptyList()
        val html = fetcher.getText(HOME_URL, mapOf("Cookie" to cookies))
        if (html.isEmpty()) return emptyList()
        return parsePosterRows(html, limit)
    }

    /**
     * Pure homepage poster-row parse — the T4 fixture seam. `poster-item
     * grid-item` blocks carry the book url, a `Назва - Автор` title, the cover
     * in `data-src` and slash-separated genres (T1 spike markup). Never
     * throws; absent stays absent.
     */
    internal fun parsePosterRows(html: String, limit: Int): List<SourceBook> {
        if (html.isBlank()) return emptyList()
        val books = mutableListOf<SourceBook>()
        val starts = POSTER_START.findAll(html).map { it.range.first }.toList()
        for (i in starts.indices) {
            if (books.size >= limit) break
            val from = starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else html.length
            val block = html.substring(from, to)

            val url = POSTER_HREF.find(block)?.groupValues?.get(1) ?: continue
            val rawTitle = POSTER_TITLE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (rawTitle.length < 3) continue
            // Title is «Назва - Автор»; split on the LAST separator so a title
            // that itself contains " - " keeps its real author.
            val author = rawTitle.substringAfterLast(" - ").trim()
            val title = rawTitle.substringBeforeLast(" - ").trim().ifBlank { rawTitle }
            val cover = POSTER_COVER.find(block)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("/uploads/") }
                ?.let { "https://sluhay.com$it" }
            val genres = POSTER_META.find(block)?.groupValues?.get(1)?.trim().orEmpty()

            books.add(
                SourceBook(
                    title = title,
                    author = author,
                    url = url,
                    coverImageUrl = cover,
                    genre = genres,
                    sourceId = sourceId
                )
            )
        }
        return books
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        // The book page HTML sits behind Cloudflare like the homepage — send
        // the live session cookies when present (server-fetch 200 with the
        // session, per the T1 verdict); without a session the fetch 403s and
        // the parse stays absent (empty detail), which the import doors treat
        // as "nothing playable". The playlist fetch inside [detailFromCapturedHtml]
        // only needs the source Referer (the fetcher always sends it).
        val cookies = cookieProvider().trim()
        val html = if (cookies.isBlank()) {
            fetcher.getText(url)
        } else {
            fetcher.getText(url, mapOf("Cookie" to cookies))
        }
        return parseCapturedPage(html, url) ?: SourceBookDetail("", "", url = url, chapters = emptyList())
    }

    /**
     * Spec-15 T3 — catalogue enumeration for the hydration tool: a breadth
     * sample of the source's full catalogue through the live session. The
     * homepage's category sections (`/fantastyka/`, `/roman/`, … — the first
     * path segment of the poster book URLs) are the catalogue; each category
     * page reuses the same poster-row markup as the homepage, so the union
     * walks a few of them and dedupes by url. Without a live session
     * (Cloudflare 403) there is nothing to crawl — empty, as [fetchNew].
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        val cookies = cookieProvider().trim()
        if (cookies.isBlank()) return emptyList()
        val home = fetcher.getText(HOME_URL, mapOf("Cookie" to cookies))
        if (home.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        // Seed the crawl with the homepage rows themselves.
        for (book in parsePosterRows(home, limit)) {
            if (seen.add(book.url)) books += book
        }
        // Category sections: the first path segment of the poster book URLs
        // (`https://sluhay.com/<category>/<id>-<slug>.html`). Each category
        // page reuses the same poster-row markup, so walk a few and dedupe.
        val categories = POSTER_HREF.findAll(home)
            .mapNotNull { m ->
                m.groupValues[1]
                    .substringAfter("https://sluhay.com/")
                    .substringBefore('/')
                    .takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(MAX_CATEGORIES)
        for (category in categories) {
            if (books.size >= limit) break
            val html = fetcher.getText("https://sluhay.com/$category/", mapOf("Cookie" to cookies))
            if (html.isEmpty()) continue
            for (book in parsePosterRows(html, limit - books.size)) {
                if (seen.add(book.url)) books += book
            }
        }
        return books
    }

    /**
     * ADR-0006 — the T3 interception seam under the ONE captured-page name:
     * builds the detail from HTML captured in the live WebView session (the
     * page HTML needs the session cookies past Cloudflare; the playlist/track
     * fetch only needs the Referer). Never null here — an unparseable page
     * yields an empty detail, which the import doors surface as "nothing
     * playable".
     */
    override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail {
        val page = parsePage(html, url)
        val playlistUrl = page.playlistUrl
            ?: return SourceBookDetail(
                title = page.title,
                author = page.author,
                url = url,
                coverImageUrl = page.coverImageUrl,
                totalDurationSeconds = page.totalDurationSeconds,
                chapters = emptyList(),
                description = page.description
            )
        val chapters = parsePlaylist(fetcher.getText(playlistUrl))
        return SourceBookDetail(
            title = page.title,
            author = page.author,
            url = url,
            coverImageUrl = page.coverImageUrl,
            totalDurationSeconds = page.totalDurationSeconds,
            chapters = chapters,
            description = page.description
        )
    }

    /** Pure page parse — the fixture seam. Never throws; absent stays absent. */
    internal fun parsePage(html: String, url: String): SluhayBookPage {
        if (html.isBlank()) return SluhayBookPage("", "", url = url, playlistUrl = null)

        val ogTitle = ogMeta(html, "og:title")?.let { og ->
            // og:title = «Назва - Автор » Site»; the site suffix after " » " is
            // boilerplate, never part of the book identity.
            og.substringBefore(" »").trim()
        } ?: ""

        // The Назва/Автор rows are authoritative (a title may itself contain
        // " - "), og:title split is only the fallback.
        val metaTitle = metaRow(html, "Назва")
        val metaAuthor = metaRow(html, "Автор")
        val author = metaAuthor.ifBlank { ogTitle.substringAfterLast(" - ").trim() }
        val title = metaTitle.ifBlank { ogTitle.substringBeforeLast(" - ").trim() }.ifBlank { ogTitle }

        val cover = coverPath(html)
            ?.let { if (it.startsWith("http")) it else "https://sluhay.com$it" }

        return SluhayBookPage(
            title = title,
            author = author,
            narrator = "",
            url = url,
            coverImageUrl = cover,
            playlistUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1),
            totalDurationSeconds = metaRow(html, "Тривалість")
                .let(::parseDurationSeconds),
            description = ogMeta(html, "og:description")?.trim().orEmpty()
        )
    }

    /** Pure playlist parse: the ordered [SourceChapter]s of one book. */
    internal fun parsePlaylist(json: String): List<SourceChapter> {
        if (!json.trim().startsWith("[{")) return emptyList()
        val files = FILE.findAll(json).map { it.groupValues[1] }.toList()
        val titles = TITLE.findAll(json).map { it.groupValues[1] }.toList()
        return files.mapIndexed { index, file ->
            val name = titles.getOrNull(index)
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Глава ${index + 1}"
            SourceChapter(title = name, streamUrl = file)
        }
    }

    private fun ogMeta(html: String, property: String): String? =
        Regex("""<meta\s+property="$property"\s+content="([^"]+)"\s*/?>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content="([^"]+)"\s+property="$property"\s*/?>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    /** Value of the page's `<li><span>Key</span> <span>value</span></li>` row. */
    private fun metaRow(html: String, key: String): String =
        Regex(
            """<li[^>]*>\s*<span>$key</span>\s*<span>(.*?)</span>\s*</li>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
            ?.let { Regex("""<[^>]+>""").replace(it, "") }
            ?.trim()
            ?: ""

    /** Cover path from the lazy-loaded cover img / poster. */
    private fun coverPath(html: String): String? =
        Regex("""<img[^>]*data-src="([^"]*(?:uploads|books)[^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""data-poster="([^"]*(?:uploads|books)[^"]*)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun parseDurationSeconds(hhmmss: String): Long? {
        val parts = hhmmss.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private companion object {
        const val HOME_URL = "https://sluhay.com/"

        // Spec-15 T3: how many category pages the hydration crawl samples.
        const val MAX_CATEGORIES = 6

        // The homepage poster rows (T1 spike): each block starts at
        // `<a class="poster-item grid-item" href=…>`, the title/meta live in
        // poster-item__* divs and the cover in the first lazy-loaded data-src.
        val POSTER_START = Regex("""<a class="poster-item grid-item"""", RegexOption.IGNORE_CASE)
        val POSTER_HREF = Regex("""href="(https://sluhay\.com/[^"]+\.html)"""", RegexOption.IGNORE_CASE)
        val POSTER_TITLE = Regex("""poster-item__title[^>]*>\s*([^<]+?)\s*<""", RegexOption.IGNORE_CASE)
        val POSTER_META = Regex("""poster-item__meta[^>]*>\s*([^<]+?)\s*<""", RegexOption.IGNORE_CASE)
        val POSTER_COVER = Regex("""<img[^>]+data-src="(/uploads/[^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)

        val PLAYLIST_URL = Regex(
            """(https://[a-z0-9]+\.redirectto\.cc/[^"'<>\s]+\.pl\.txt)""",
            RegexOption.IGNORE_CASE
        )
        val FILE = Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        val TITLE = Regex(""""title"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    }
}

/** Pure shape of a parsed sluhay book page (spec-13 T2). */
internal data class SluhayBookPage(
    val title: String,
    val author: String,
    val narrator: String = "",
    val url: String,
    val coverImageUrl: String? = null,
    val playlistUrl: String? = null,
    val totalDurationSeconds: Long? = null,
    val description: String = ""
)
