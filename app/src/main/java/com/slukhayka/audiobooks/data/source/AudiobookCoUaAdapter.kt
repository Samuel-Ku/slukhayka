package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy

/**
 * audiobook.co.ua [SourceAdapter] (spec-47 T2; endpoints verified live in the
 * T1 spike — `docs/wayfinder/research/sources-wave-47-spike.md`, fixtures in
 * `research/fixtures/audiobookcoua/`).
 *
 * WordPress, server-fetch, no WebView. Three mechanics:
 * - **«Новинки озвучивания» feed:** `GET /novinki-ozvuchivaniya/` —
 *   server-rendered post-grid cards; the title anchor is «Назва - Автор» and
 *   the cover img repeats the pair, so titles and authors never need guessing
 *   (the no-fabricated-author rule holds by markup shape).
 * - **Book page:** og:title = «Аудиокнига <Назва> - <Автор> …», og:image =
 *   the cover; the `new Playerjs({...})` init carries the playlist URL —
 *   `https://audiobook.co.ua/playlist/<slug>.txt` — a JSON array of
 *   `{title, file}` (same Playerjs family as audiobook-mp3).
 * - **Audio:** playlist files are absolute archive.org URLs (node redirect →
 *   `206 audio/mpeg` with ranges, verified in T1); the shared transport
 *   follows them, no Referer tricks needed.
 *
 * Measured absences (ADR-0014 — never fabricated):
 * - **Search:** the post-grid keyword form does not filter server-side
 *   (T1 spike: 18 items on plain and `?keyword=` pages alike) → [search] is
 *   an honest empty list; discovery rides [fetchNew]/[fetchCatalog].
 * - **Series, duration, rating, blurb:** the book page carries none of them
 *   in the captured shape — series pages (`/seria/…`) are taxonomy-level,
 *   not per-book links, and og:description is site boilerplate («…скачать,
 *   слушать онлайн…»), not an annotation. Kept absent; per-source review
 *   surfaces remain an M2 consideration (spec Out of Scope).
 * - **Chapter titles:** the playlist repeats «<N> <Назва> - <Автор>» — kept
 *   trimmed as-is; a display-name normalization is a later decision, not a
 *   parser rewrite.
 */
class AudiobookCoUaAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "audiobookcoua"

    /** Spec-47 — the catalogue speaks Ukrainian (site chrome is Russian; irrelevant). */
    override val contentLanguage = "uk"

    /**
     * The site's keyword search does not filter server-side (T1 spike) — the
     * honest refusal the seam contract allows; unified search covers the
     * source through the other sources' results and the catalogue union.
     */
    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val html = fetcher.getText(NOVELTIES_URL, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS)
        if (html.isEmpty()) return emptyList()
        return noveltiesFrom(html).take(limit)
    }

    /**
     * Spec-15 T1 — the depth of the catalogue: the WordPress post-sitemaps
     * enumerate the book list (2192 locs over three sitemaps at T1). Locs
     * carry URLs only, so the sample is filled with real page parses up to
     * [limit] — every card keeps a real title/author pair, never a slug.
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val books = mutableListOf<SourceBook>()
        for (sitemapUrl in POST_SITEMAPS) {
            for (loc in sitemapLocs(fetcher.getText(sitemapUrl, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS))) {
                if (books.size >= limit) return books
                val page = fetcher.getText(loc, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS)
                if (page.isEmpty()) continue
                bookFromPage(page, loc)?.let { books += it }
            }
        }
        return books
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url, emptyMap(), SourceRequestClass.LISTENER_ACTION, 0L)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())
        val (title, author) = titleAndAuthorFrom(html)
        val cover = ogMeta(html, "og:image")
        val playlistUrl = playlistUrlFrom(html)
            ?: return SourceBookDetail(
                title = title,
                author = author,
                url = url,
                coverImageUrl = cover,
                chapters = emptyList()
            )
        val tracks = tracksFrom(fetcher.getText(playlistUrl, emptyMap(), SourceRequestClass.LISTENER_ACTION, 0L))
        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            coverImageUrl = cover,
            chapters = tracks.mapIndexed { index, track ->
                SourceChapter(
                    title = track.title.ifBlank { "Розділ ${index + 1}" },
                    streamUrl = track.file
                )
            }
        )
    }

    /**
     * Sitemap and card URLs end with `/`, which would break the seam default
     * (`substringAfterLast('/')` → blank → a timestamped id). The slug is the
     * catalogued identity of the page — one stable id per book.
     */
    override fun bookId(url: String): String {
        val slug = url.substringBefore('?').removeSuffix("/").substringAfterLast('/')
            .ifBlank { "book" }
        return "$sourceId-$slug"
    }

    // --- parsing helpers -----------------------------------------------------

    /** One `{title, file}` entry of the Playerjs playlist JSON. */
    private data class PlaylistTrack(val title: String, val file: String)

    private fun tracksFrom(json: String): List<PlaylistTrack> {
        if (json.isBlank()) return emptyList()
        val tracks = mutableListOf<PlaylistTrack>()
        var pos = 0
        while (true) {
            val objStart = json.indexOf('{', pos)
            if (objStart < 0) break
            val obj = balancedContent(json, objStart, '{', '}') ?: break
            val file = quotedField(obj, "file").orEmpty().trim()
            if (file.startsWith("http")) {
                tracks += PlaylistTrack(quotedField(obj, "title").orEmpty().trim(), file)
            }
            pos = objStart + obj.length
        }
        return tracks
    }

    /** The playlist URL from the `new Playerjs({...})` init call, unescaped. */
    private fun playlistUrlFrom(html: String): String? {
        val init = Regex("""new\s+Playerjs\(""").find(html) ?: return null
        val window = html.substring(init.range.first, minOf(html.length, init.range.first + 4096))
        return Regex(""""file"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(window)
            ?.groupValues?.get(1)?.let(::jsonUnescape)
    }

    /**
     * og:title shape: «Аудиокнига <Назва> - <Автор> скачать, слушать онлайн -
     * Аудиокниги на украинском языке». The known prefix, boilerplate chunk
     * and site tail are stripped; what remains is the card's own «Назва -
     * Автор» pair, split at the FIRST separator. A pair without the
     * separator keeps the author empty (never invented).
     */
    private fun titleAndAuthorFrom(html: String): Pair<String, String> {
        val clean = ogMeta(html, "og:title").orEmpty()
            .removePrefix("Аудиокнига ")
            .removeSuffix(SITE_TAIL)
            .replace(Regex("""\s+скачать, слушать онлайн\s*$"""), "")
            .trim()
        val sep = clean.indexOf(" - ")
        if (sep <= 0) return clean to ""
        return clean.substring(0, sep).trim() to clean.substring(sep + 3).trim()
    }

    private fun bookFromPage(html: String, url: String): SourceBook? {
        val (title, author) = titleAndAuthorFrom(html)
        if (title.isBlank()) return null
        return SourceBook(
            title = title,
            author = author,
            url = url,
            coverImageUrl = ogMeta(html, "og:image"),
            sourceId = sourceId
        )
    }

    /** One post-grid card of the novinki grid: URL, «Назва - Автор» label, cover. */
    private data class GridCard(val url: String, val label: String, val cover: String?)

    private fun noveltiesFrom(html: String): List<SourceBook> =
        gridCards(html).mapNotNull { card ->
            val (title, author) = splitTitleAuthor(card.label)
            if (title.isBlank()) return@mapNotNull null
            SourceBook(
                title = title,
                author = author,
                url = card.url,
                coverImageUrl = card.cover,
                sourceId = sourceId
            )
        }

    private fun gridCards(html: String): List<GridCard> {
        val starts = Regex("""<div class="item item-\d+""").findAll(html)
            .map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val cards = mutableListOf<GridCard>()
        for (i in starts.indices) {
            val block = html.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] else html.length)
            val url = Regex("""href="(https://audiobook\.co\.ua/[^"]+)"""").find(block)
                ?.groupValues?.get(1) ?: continue
            val label = Regex(
                """class="element[^"]*\btitle\b[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(block)?.groupValues?.get(1)?.let(::decodeEntities)?.trim() ?: continue
            val cover = Regex("""src="(https://audiobook\.co\.ua/wp-content/uploads/[^"]+)"""")
                .find(block)?.groupValues?.get(1)
            cards += GridCard(url, label, cover)
        }
        return cards
    }

    /** «Назва - Автор» → (title, author); no separator → empty author, never invented. */
    private fun splitTitleAuthor(label: String): Pair<String, String> {
        val sep = label.indexOf(" - ")
        if (sep <= 0) return label.trim() to ""
        return label.substring(0, sep).trim() to label.substring(sep + 3).trim()
    }

    /** `<loc>` entries of a WordPress sitemap, filtered to the site's own URLs. */
    private fun sitemapLocs(xml: String): List<String> =
        Regex("""<loc>\s*([^<\s]+)\s*</loc>""", RegexOption.IGNORE_CASE).findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("https://audiobook.co.ua/") }
            .toList()

    /**
     * An og meta value, extracted with a tag-window: from the property
     * attribute to the next `<` (the next tag's start), so attribute wildcards
     * can never bridge two meta tags — live pages terminate their tags, but
     * trimmed captures may not, and a bridging `[^>]*` would let greedy
     * backtracking grab a LATER meta's content (og:image:width's «300"
     * instead of the title — found by the fixture tests). Both attribute
     * orders are accepted.
     */
    private fun ogMeta(html: String, property: String): String? {
        val prop = Regex("""property\s*=\s*["']$property["']""", RegexOption.IGNORE_CASE)
        val content = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        fun windowAfter(end: Int): String {
            val stop = html.indexOf('<', end).let { if (it < 0) html.length else it }
            return html.substring(end, stop)
        }
        // property="…" … content="…" within one tag
        prop.findAll(html).forEach { m ->
            content.find(windowAfter(m.range.last + 1))?.let { return it.groupValues[1].trim() }
        }
        // content="…" … property="…" within one tag
        content.findAll(html).forEach { m ->
            if (prop.containsMatchIn(windowAfter(m.range.last + 1))) return m.groupValues[1].trim()
        }
        return null
    }

    private fun decodeEntities(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    /**
     * Unescapes a JSON string value (`\"`, `\\`, `\/`, `\uXXXX`) — the same
     * shape the Playerjs init and the live pages carry.
     */
    private fun jsonUnescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = s[i + 1]) {
                'u' -> {
                    val hex = if (i + 5 < s.length) s.substring(i + 2, i + 6) else ""
                    if (hex.length == 4 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                        out.append(hex.toInt(16).toChar())
                        i += 6
                    } else {
                        out.append(c)
                        i++
                    }
                }
                '\\' -> { out.append('\\'); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '/' -> { out.append('/'); i += 2 }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /**
     * Returns the substring from [start] (an [open] bracket) through its
     * matching [close] bracket, respecting JSON strings and escapes.
     */
    private fun balancedContent(s: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') {
                    i++
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** A `"name": "value"` string field with JSON escapes unescaped. */
    private fun quotedField(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(obj)
            ?.groupValues?.get(1)?.let(::jsonUnescape)

    private companion object {
        const val NOVELTIES_URL = "https://audiobook.co.ua/novinki-ozvuchivaniya/"

        /** T1 spike: the three post-sitemaps enumerate the book catalogue. */
        val POST_SITEMAPS = listOf(
            "https://audiobook.co.ua/post-sitemap.xml",
            "https://audiobook.co.ua/post-sitemap2.xml",
            "https://audiobook.co.ua/post-sitemap3.xml"
        )

        /** The og:title site tail («… - Аудиокниги на украинском языке»). */
        const val SITE_TAIL = " - Аудиокниги на украинском языке"
    }
}
