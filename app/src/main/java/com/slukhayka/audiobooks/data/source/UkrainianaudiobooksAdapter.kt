package com.slukhayka.audiobooks.data.source

/**
 * ukrainianaudiobooks.com [SourceAdapter] (spec-47 T4; WebView-pattern, the
 * sluhay treatment of spec-13 T2).
 *
 * The site sits behind a Cloudflare challenge on the HTTP level (T1 spike
 * verdict **GATED** — 403 + cf-mitigated challenge on `/` and `/robots.txt`
 * even with a real Android WebView UA), so discovery and book pages exist
 * only inside the live in-app browser session:
 *
 * - [search] stays empty — the browser surface finds the book in the
 *   session; the honest seam refusal, never a fake page.
 * - [fetchNew] hydrates the homepage through the live session's cookies
 *   (T1: server-fetch 200 WITH the session, no DOM snapshot needed) — the
 *   «Новинки» rail CTA covers a missing/stale session.
 * - [fetchCatalog] walks a sample of the homepage nav's category sections
 *   through the same session cookies (spec-15 T3 pattern).
 * - [fetchBookPage] fetches through the session cookies and [parseCapturedPage]
 *   (ADR-0006) is the ONE name the import door uses for captured HTML.
 *
 * Markup (fixtures in `research/fixtures/ukrainianaudiobooks/`, trimmed from
 * REAL captures via the Wayback Machine — 2024-02-23 book page, 2025-04-09
 * homepage; the T4 on-device session re-verifies): DataLife Engine template,
 * the same family as sluhay.com:
 *
 * - **Book page**: `<h1 class="short-title">Аудіокнига "Назва - Автор"</h1>`,
 *   the `short-list` rows (Жанр/Автор/Читає/Час — the site DOES name its
 *   narrators, a first for the Ukrainian direct family), the cover in the
 *   lazy `data-src` (`fimg` block; og:image is the absolute twin), the real
 *   annotation in `shortstory-text`, and the playlist URL inline in a
 *   `Playerjs({id:"playerjs1",file:"https://<hash>.frontroute.org/s05/<id>.pl.txt"})`
 *   init.
 * - **Homepage / category pages**: `short-item` cards with the
 *   «Назва - Автор» title link, `data-src` cover, `short-label` genre, the
 *   fa-play duration, and explicit Автор/Читає rows — the same DLE template,
 *   so one card parser serves the feed and the catalog walk.
 * - **Playlist**: the playerjs `.pl.txt` JSON of `{title, file}` pairs — the
 *   SAME standard format the repo's other playerjs sources parse (sluhay,
 *   sluhayknigi, audiobookmp3); shape to be re-verified from the device
 *   session (the CDN itself is not archived).
 *
 * Measured absences (ADR-0014 — never fabricated):
 * - **Direct audio proof**: no stream URL was ever observed outside the
 *   session (T1 GATED) → `streamOnlyFor` (T5) and no Referer in [headersFor]
 *   (SEC-004: no evidence, no header — if the device session shows the
 *   `frontroute.org` CDN needs the source Referer, the entry joins like
 *   sluhay's).
 * - The Cloudflare challenge page itself yields an empty detail — nothing
 *   playable, honestly.
 */
class UkrainianaudiobooksAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://ukrainianaudiobooks.com/"),
    /**
     * Spec-42 #427 — the shared host-aware Cookie provider, read just-in-time
     * for the concrete request host (never copying a cookie from one host to
     * another). Default empty keeps the adapter pure-JVM; production wires
     * [AndroidSourceCookieProvider] once in the composition root.
     */
    private val cookieProvider: SourceCookieProvider = object : SourceCookieProvider {
        override fun cookieFor(url: String): String = ""
    }
) : SourceAdapter {

    /** Spec-45 (#405) — the catalogue speaks Ukrainian. */
    override val contentLanguage = "uk"

    override val sourceId: String = "ukrainianaudiobooks"
    override val accessMode: SourceAccessMode = SourceAccessMode.BROWSER

    /** Spec-47 T4: discovery is session-bound — the feed pipeline shows a CTA, never dead data. */
    override val sessionBound: Boolean = true

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    /**
     * The «Новинки» feed: the homepage `short-item` grid, hydrated through the
     * live WebView session's cookies (T1 verdict: server-fetch 200 with the
     * session, no DOM snapshot needed). Empty without a session or on a
     * blocked/stale fetch — the repository surfaces the CTA row then.
     */
    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        // Spec-42 #427 — host-aware: read the Cookie header just-in-time for
        // HOME_URL's host, never reusing another host's cookie.
        val cookies = cookieProvider.cookieFor(HOME_URL).trim()
        // No live session: Cloudflare would 403, so there is nothing to parse.
        if (cookies.isBlank()) return emptyList()
        val html = fetcher.getText(HOME_URL, mapOf("Cookie" to cookies))
        if (html.isEmpty()) return emptyList()
        return parseShortItems(html, limit)
    }

    /**
     * Pure `short-item` card parse — the feed/catalog fixture seam. A card
     * block carries the book url (title link), a «Назва - Автор» title
     * (split on the LAST separator so a title containing \" - \" keeps its
     * real author), the cover in `data-src`, the `short-label` genre, the
     * fa-play duration, and the explicit Автор/Читає rows. Never throws;
     * absent stays absent.
     */
    internal fun parseShortItems(html: String, limit: Int): List<SourceBook> {
        if (html.isBlank()) return emptyList()
        val books = mutableListOf<SourceBook>()
        val starts = SHORT_ITEM.findAll(html).map { it.range.first }.toList()
        for (i in starts.indices) {
            if (books.size >= limit) break
            val from = starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else html.length
            val block = html.substring(from, to)

            val url = CARD_TITLE_LINK.find(block)?.groupValues?.get(1) ?: continue
            val rawTitle = CARD_TITLE_TEXT.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (rawTitle.length < 3) continue
            val title = rawTitle.substringBeforeLast(" - ").trim().ifBlank { rawTitle }
            val splitAuthor = rawTitle.substringAfterLast(" - ").trim()
            val author = CARD_AUTHOR.find(block)?.groupValues?.get(1)?.trim()
                ?.ifBlank { null } ?: splitAuthor
            val cover = CARD_COVER.find(block)?.groupValues?.get(1)?.let(::absoluteUrl)
            val narrator = CARD_NARRATOR.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val duration = CARD_DURATION.find(block)?.groupValues?.get(1)?.trim().orEmpty()

            books.add(
                SourceBook(
                    title = title,
                    author = author,
                    narrator = narrator,
                    url = url,
                    coverImageUrl = cover,
                    genre = CARD_GENRE.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
                    totalDurationSeconds = parseDurationSeconds(duration) ?: 0L,
                    sourceId = sourceId
                )
            )
        }
        return books
    }

    /**
     * Spec-15 T3 — catalogue enumeration for the hydration tool: a breadth
     * sample of the source's full catalogue through the live session. The
     * homepage nav's category sections (`/roman/`, `/fantastika/`, …) reuse
     * the same `short-item` markup, so the union walks a few of them and
     * dedupes by url. Pagination (`/page/N/`) and `xfsearch/` links are not
     * categories and never entered the walk. Without a live session
     * (Cloudflare 403) there is nothing to crawl — empty, as [fetchNew].
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        // Spec-42 #427 — host-aware cookies: each request reads its own host's
        // cookie just-in-time, never copying one host's cookie onto another.
        val homeCookies = cookieProvider.cookieFor(HOME_URL).trim()
        if (homeCookies.isBlank()) return emptyList()
        val home = fetcher.getText(HOME_URL, mapOf("Cookie" to homeCookies))
        if (home.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        // Seed the crawl with the homepage rows themselves.
        for (book in parseShortItems(home, limit)) {
            if (seen.add(book.url)) books += book
        }
        // Category sections from the nav: single-segment `https://…/<cat>/`
        // links, skipping the pagination (`page`) and search (`xfsearch`)
        // pseudo-sections.
        val categories = NAV_CATEGORY.findAll(home)
            .mapNotNull { m -> m.groupValues[1].takeIf { it != "page" && it != "xfsearch" && it != "tags" } }
            .distinct()
            .take(MAX_CATEGORIES)
        for (category in categories) {
            if (books.size >= limit) break
            val categoryUrl = "https://ukrainianaudiobooks.com/$category/"
            val catCookies = cookieProvider.cookieFor(categoryUrl).trim()
            val headers = if (catCookies.isBlank()) emptyMap() else mapOf("Cookie" to catCookies)
            val html = fetcher.getText(categoryUrl, headers)
            if (html.isEmpty()) continue
            for (book in parseShortItems(html, limit - books.size)) {
                if (seen.add(book.url)) books += book
            }
        }
        return books
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        // The book page HTML sits behind Cloudflare like the homepage — send
        // the live session cookies just-in-time for the concrete [url] host
        // (spec-42 #427); without a session the fetch 403s and the parse stays
        // absent (empty detail), which the import doors treat as "nothing
        // playable". The playlist fetch inside [parseCapturedPage] only needs
        // the source Referer (the fetcher always sends it) — no Cookie header.
        val cookies = cookieProvider.cookieFor(url).trim()
        val html = if (cookies.isBlank()) {
            fetcher.getText(url)
        } else {
            fetcher.getText(url, mapOf("Cookie" to cookies))
        }
        return parseCapturedPage(html, url)
    }

    /**
     * ADR-0006 — the T4 interception seam under the ONE captured-page name:
     * builds the detail from HTML captured in the live WebView session (the
     * page HTML needs the session cookies past Cloudflare; the playlist/track
     * fetch only needs the Referer). Never null here — an unparseable page
     * (the Cloudflare challenge itself included) yields an empty detail,
     * which the import doors surface as "nothing playable".
     */
    override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail {
        val page = parsePage(html, url)
        val playlistUrl = page.playlistUrl
            ?: return SourceBookDetail(
                title = page.title,
                author = page.author,
                narrator = page.narrator,
                url = url,
                coverImageUrl = page.coverImageUrl,
                genres = page.genre.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                totalDurationSeconds = page.totalDurationSeconds,
                chapters = emptyList(),
                description = page.description
            )
        val chapters = parsePlaylist(fetcher.getText(playlistUrl))
        return SourceBookDetail(
            title = page.title,
            author = page.author,
            narrator = page.narrator,
            url = url,
            coverImageUrl = page.coverImageUrl,
            genres = page.genre.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
            totalDurationSeconds = page.totalDurationSeconds,
            chapters = chapters,
            description = page.description
        )
    }

    /** Pure book-page parse — the fixture seam. Never throws; absent stays absent. */
    internal fun parsePage(html: String, url: String): UkrainianaudiobooksBookPage {
        if (html.isBlank()) return UkrainianaudiobooksBookPage("", "", url = url, playlistUrl = null)

        // The h1 is «Аудіокнига "Назва - Автор"» — strip the site's wrapper
        // and split on the LAST separator (a title may itself contain " - ").
        val h1Inner = H1_TITLE.find(html)?.groupValues?.get(1)
            ?.removePrefix("Аудіокнига")
            ?.trim()
            ?.removePrefix("\"")
            ?.trim()
            ?.removeSuffix("\"")
            ?.trim()
            .orEmpty()
        val h1Title = h1Inner.substringBeforeLast(" - ").trim().ifBlank { h1Inner }
        val h1Author = h1Inner.substringAfterLast(" - ").trim()

        // og:title «Назва - Автор » Site» is the fallback when the h1 is absent.
        val ogTitle = ogMeta(html, "og:title")
            ?.substringBefore(" »")
            ?.trim()
            .orEmpty()
        val ogFallbackTitle = ogTitle.substringBeforeLast(" - ").trim().ifBlank { ogTitle }
        val ogFallbackAuthor = ogTitle.substringAfterLast(" - ").trim()

        // The «Автор» row is authoritative (the site's own claim); the h1/og
        // splits only fall back. «Читає» names the narrator — real, never
        // guessed.
        val author = shortListRow(html, "Автор").ifBlank { h1Author.ifBlank { ogFallbackAuthor } }
        val title = h1Title.ifBlank { ogFallbackTitle }

        val cover = FIMG_COVER.find(html)?.groupValues?.get(1)?.let(::absoluteUrl)
            ?: ogMeta(html, "og:image")?.let(::absoluteUrl)

        return UkrainianaudiobooksBookPage(
            title = title,
            author = author,
            narrator = shortListRow(html, "Читає"),
            url = url,
            coverImageUrl = cover,
            genre = shortListRow(html, "Жанр"),
            playlistUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1),
            totalDurationSeconds = shortListRow(html, "Час").let(::parseDurationSeconds),
            description = DESCRIPTION_BLOCK.find(html)?.groupValues?.get(1)
                ?.let { decodeEntities(stripTags(it)) }
                ?.trim()
                .orEmpty()
        )
    }

    /** Pure playlist parse: the ordered [SourceChapter]s of one book (playerjs `.pl.txt`). */
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

    /** Value of a `<ul class="short-list">` row: `<span …> Label:</span> <a…>value</a>` or plain text. */
    private fun shortListRow(html: String, label: String): String =
        Regex(
            """<span[^>]*>\s*$label:\s*</span>\s*(?:<a[^>]*>\s*)?([^<]+)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim().orEmpty()

    private fun absoluteUrl(url: String): String =
        if (url.startsWith("http")) url else "https://ukrainianaudiobooks.com$url"

    private companion object {
        const val HOME_URL = "https://ukrainianaudiobooks.com/"

        // Spec-15 T3: how many category pages the hydration crawl samples.
        const val MAX_CATEGORIES = 6

        // One card of the feed/category grid (T4 fixtures): starts at the
        // `short-item` div; the title link carries the book url and the
        // «Назва - Автор» text, the cover lives in the lazy `data-src`.
        val SHORT_ITEM = Regex("""<div class="short-item">""")
        val CARD_TITLE_LINK = Regex("""<a class="short-title fx-1" href="(https://ukrainianaudiobooks\.com/[^"]+\.html)"""")
        val CARD_TITLE_TEXT = Regex("""<a class="short-title fx-1"[^>]*>\s*([^<]+?)\s*</a>""")
        val CARD_COVER = Regex("""<img[^>]+data-src="([^"]+)"[^>]*>""")
        val CARD_GENRE = Regex("""short-label"[^>]*>\s*([^<]+?)\s*</div>""")
        val CARD_DURATION = Regex("""<span class="fa fa-play"[^>]*></span>\s*([0-9:]+)""")
        val CARD_AUTHOR = Regex("""<span class="fa fa-pencil"[^>]*>[^<]*</span><a[^>]*>([^<]+)</a>""")
        val CARD_NARRATOR = Regex("""<span class="fa fa-microphone"[^>]*></span><a[^>]*>([^<]+)</a>""")

        // Category nav links of the homepage: single-segment `<cat>/` hrefs.
        val NAV_CATEGORY = Regex("""href="https://ukrainianaudiobooks\.com/([a-z0-9-]+)/"""")
        val PLAYLIST_URL = Regex(
            """(https://[a-z0-9]+\.frontroute\.org/[^"'<>\s]+\.pl\.txt)""",
            RegexOption.IGNORE_CASE
        )
        val FILE = Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        val TITLE = Regex(""""title"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

        val H1_TITLE = Regex("""<h1 class="short-title fx-1">([^<]+)</h1>""")
        val FIMG_COVER = Regex("""<img[^>]*data-src="([^"]+)"[^>]*>""")
        val DESCRIPTION_BLOCK = Regex("""<div class="shortstory-text">([\s\S]*?)</div>""")
    }
}

/** Pure shape of a parsed ukrainianaudiobooks book page (spec-47 T4). */
internal data class UkrainianaudiobooksBookPage(
    val title: String,
    val author: String,
    val narrator: String = "",
    val url: String,
    val coverImageUrl: String? = null,
    val genre: String = "",
    val playlistUrl: String? = null,
    val totalDurationSeconds: Long? = null,
    val description: String = ""
)