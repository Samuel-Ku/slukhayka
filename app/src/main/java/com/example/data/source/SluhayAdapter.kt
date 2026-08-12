package com.example.data.source

/**
 * sluhay.com [SourceAdapter] (spec-13 T2; format verified live in the spec-13
 * T1 spike, 2026-08-12).
 *
 * Discovery on this source is WebView-only: sluhay.com sits behind Cloudflare,
 * so [search] and [fetchNew] stay empty — the browser surface (T3) finds the
 * book in the session, and the «Нове з Sluhay» row (T4) hydrates through the
 * session. What IS usable server-side is the audio pipeline:
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
 */
class SluhayAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://sluhay.com/")
) : SourceAdapter {

    override val sourceId: String = "sluhay"

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url)
        return detailFromCapturedHtml(html, url)
    }

    /**
     * The T3 interception seam: builds the detail from HTML captured in the
     * live WebView session (the page HTML needs the session cookies past
     * Cloudflare; the playlist/track fetch only needs the Referer).
     */
    suspend fun detailFromCapturedHtml(html: String, url: String): SourceBookDetail {
        val page = parsePage(html, url)
        val playlistUrl = page.playlistUrl
            ?: return SourceBookDetail(
                title = page.title,
                author = page.author,
                url = url,
                coverImageUrl = page.coverImageUrl,
                totalDurationSeconds = page.totalDurationSeconds,
                chapters = emptyList()
            )
        val chapters = parsePlaylist(fetcher.getText(playlistUrl))
        return SourceBookDetail(
            title = page.title,
            author = page.author,
            url = url,
            coverImageUrl = page.coverImageUrl,
            totalDurationSeconds = page.totalDurationSeconds,
            chapters = chapters
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
                .let(::parseDurationSeconds)
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
    val totalDurationSeconds: Long? = null
)
