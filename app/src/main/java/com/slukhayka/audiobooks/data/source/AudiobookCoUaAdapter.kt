package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.merge.SlugMatch
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import kotlinx.coroutines.delay

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
 *   (T1 spike: 18 items on plain and `?keyword=` pages alike) AND robots.txt
 *   disallows `*?s=`, `/search/` and `/?`. Since #1486 [search] therefore
 *   rides the ALLOWED taxonomy — `post_tag-sitemap.xml` + `/tag/<slug>/`
 *   pages, transliterated slugs matched through [SlugMatch] — while a
 *   title-only lookup still falls back to [fetchNew]/[fetchCatalog].
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
    private val fetcher: HttpFetcher = HttpFetcher(),
    /** Injectable pause so tests pin the rhythm without sleeping (spec-38). */
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) },
    /** Human rhythm between continuation requests (spec-38). */
    private val pacing: PacingPolicy = PacingPolicy(),
    /** #1486 — matched tags one query may use. */
    private val searchTagLimit: Int = SEARCH_TAG_LIMIT,
    /** #1486 — tag-page requests one query may spend. */
    private val searchPageLimit: Int = SEARCH_PAGE_LIMIT,
    /** #1486 — books one query returns at most. */
    private val searchResultLimit: Int = SEARCH_RESULT_LIMIT,
    /** Injectable clock so the tag-index freshness is testable (policy convention). */
    private val now: () -> Long = System::currentTimeMillis
) : SourceAdapter {

    /** In-memory tag index: the sitemap moves slowly, a keystroke must stay cheap. */
    @Volatile
    private var tagIndexCache: Pair<Long, List<Tag>>? = null

    override val sourceId: String = "audiobookcoua"

    /** Spec-47 — the catalogue speaks Ukrainian (site chrome is Russian; irrelevant). */
    override val contentLanguage = "uk"

    /**
     * #1486 — the site's own keyword search is robots-disallowed (robots.txt:
     * `*?s=`, `/search/`, `/?`) and does not filter server-side (T1 spike), so
     * [search] turns to the ALLOWED taxonomy instead: `post_tag-sitemap.xml`
     * lists the author/series/genre tags (952 at the 2026-10-27 spike, slugs
     * transliterated — `tag/king-stiven/`), and a tag page lists that author's
     * books.
     *
     * Cyrillic queries meet transliterated slugs through the repository's
     * [SlugMatch] variant set («кінг» → `king`), never through a new
     * transliteration table. The budget is bounded like sound-books': one
     * sitemap read (catalogue TTL), at most [searchTagLimit] tags and
     * [searchPageLimit] tag-page requests, every page getting its first turn
     * before any gets a second; at most [searchResultLimit] books, each
     * continuation paced ([PacingPolicy]).
     */
    override suspend fun search(query: String): List<SourceBook> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val matched = tagIndex()
            .mapNotNull { tag -> tagScore(tag.slug, clean)?.let { tag to it } }
            .sortedWith(compareByDescending<Pair<Tag, Int>> { it.second }.thenBy { it.first.slug.length })
            .map { it.first }
            .take(searchTagLimit)
        if (matched.isEmpty()) return emptyList()

        val books = LinkedHashMap<String, SourceBook>()
        val nextPage = mutableMapOf<Tag, String?>()
        matched.forEach { nextPage[it] = it.url }
        var pagesOpened = 0
        var requests = 0
        var pending = true
        while (pending && pagesOpened < searchPageLimit && books.size < searchResultLimit) {
            pending = false
            for (tag in matched) {
                if (pagesOpened >= searchPageLimit || books.size >= searchResultLimit) break
                val pageUrl = nextPage[tag] ?: continue
                pending = true
                if (requests > 0) pauseMillis(pacing.nextPauseMillis())
                requests++
                val html = fetcher.getText(
                    pageUrl,
                    emptyMap(),
                    SourceRequestClass.LISTENER_ACTION,
                    FeedSnapshotPolicy.CATALOG_TTL_MS
                )
                pagesOpened++
                if (html.isEmpty()) {
                    nextPage[tag] = null
                    continue
                }
                tagCards(html, searchResultLimit - books.size).forEach { books.putIfAbsent(it.url, it) }
                nextPage[tag] = nextTagPage(pageUrl, html)
            }
        }
        return books.values.toList()
    }

    /**
     * How well one transliterated tag slug answers the Cyrillic query: null
     * when it does not. Every query TOKEN must find a slug token among its
     * transliteration variants ([SlugMatch.variants]) — one chance match is not
     * an author — with a full, same-length match ranked first.
     */
    private fun tagScore(slug: String, query: String): Int? {
        val slugTokens = SlugMatch.tokens(slug)
        val queryTokens = SlugMatch.tokens(query)
        if (slugTokens.isEmpty() || queryTokens.isEmpty()) return null
        val forms = queryTokens.flatMap { SlugMatch.variants(it) }.toSet()
        val matched = slugTokens.count { it in forms }
        // Half a name is not an author: EVERY query token must land on a slug
        // token, otherwise «кінг овен» would open `king-stiven`.
        if (matched < queryTokens.size) return null
        return if (slugTokens.size == queryTokens.size) 100 + matched else 50 + matched
    }

    /**
     * The allowed author/series/genre tags from `post_tag-sitemap.xml`, cached
     * in memory for the catalogue TTL. Freshness is the repo's one decision
     * ([FeedSnapshotPolicy.isFresh] — a future stamp never freezes the index).
     */
    private suspend fun tagIndex(): List<Tag> {
        val cached = tagIndexCache
        val nowMillis = now()
        if (cached != null && FeedSnapshotPolicy.isFresh(cached.first, nowMillis, TAG_INDEX_TTL_MS)) {
            return cached.second
        }
        val xml = fetcher.getText(
            TAG_SITEMAP_URL,
            emptyMap(),
            SourceRequestClass.TTL_REFRESH,
            FeedSnapshotPolicy.CATALOG_TTL_MS
        )
        if (xml.isEmpty()) return cached?.second.orEmpty()
        val tags = TAG_LOC.findAll(xml).mapNotNull { match ->
            val url = match.groupValues[1].trim()
            if (!url.startsWith(TAG_URL_PREFIX)) return@mapNotNull null
            val slug = url.removeSuffix("/").substringAfterLast('/')
            slug.takeIf { it.isNotBlank() }?.let { Tag(it, url) }
        }.distinctBy { it.slug }.toList()
        if (tags.isNotEmpty()) tagIndexCache = nowMillis to tags
        return tags.ifEmpty { cached?.second.orEmpty() }
    }

    /**
     * The listing cards of one tag page. The theme's list layout carries one
     * `<article id="post-N">` per book, its title anchor «Назва — Автор» and
     * the WordPress cover — a DIFFERENT markup than the grid the news feed
     * uses, so it is parsed here, never through [gridCards].
     */
    private fun tagCards(html: String, limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val books = mutableListOf<SourceBook>()
        for (block in html.split(ARTICLE_START).drop(1)) {
            if (books.size >= limit) break
            val link = TITLE_LINK.find(block) ?: continue
            val url = link.groupValues[1]
            if (!url.startsWith("https://audiobook.co.ua/")) continue
            val label = decodeEntities(link.groupValues[2]).trim()
            if (label.isBlank()) continue
            val cover = COVER.find(block)?.groupValues?.get(1)
            val (title, author) = splitDisplayTitle(label)
            books += SourceBook(
                title = title,
                author = author,
                url = url,
                sourceId = sourceId,
                coverImageUrl = cover
            )
        }
        return books
    }

    /** «Назва — Автор» (em dash on the list theme) or «Назва - Автор». */
    private fun splitDisplayTitle(label: String): Pair<String, String> {
        for (separator in listOf(" \u2014 ", " - ")) {
            val index = label.indexOf(separator)
            if (index > 0) {
                return label.substring(0, index).trim() to label.substring(index + separator.length).trim()
            }
        }
        return label to ""
    }

    /** The next page of the SAME tag listing, or null on the last page. */
    private fun nextTagPage(current: String, html: String): String? {
        val path = current.removePrefix("https://audiobook.co.ua")
        val base = path.substringBefore("/page/").trimEnd('/')
        if (base.isBlank()) return null
        val currentPage = PAGE_NUMBER.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val next = TAG_PAGE_LINK.findAll(html).mapNotNull { match ->
            val linkedBase = match.groupValues[1].substringBefore("/page/").trimEnd('/')
            val linkedPage = match.groupValues[2].toIntOrNull()
            if (linkedPage != null && linkedBase == base && linkedPage > currentPage) linkedPage else null
        }.minOrNull() ?: return null
        return "https://audiobook.co.ua$base/page/$next/"
    }

    /** One allowed book-list tag: its transliterated slug and page url. */
    private data class Tag(val slug: String, val url: String)

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
        // G (spec `2026-09-10-remove-4read-source`) — the gate returns "" both
        // for a dead page and for a budget deferral, so a whole-sitemap walk
        // (2192 locs) must stop after a bounded run of misses instead of one
        // gated request per loc. A real card resets the run.
        val maxMisses = 10
        var misses = 0
        for (sitemapUrl in POST_SITEMAPS) {
            for (loc in sitemapLocs(fetcher.getText(sitemapUrl, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS))) {
                if (books.size >= limit) return books
                val page = fetcher.getText(loc, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS)
                if (page.isEmpty()) {
                    if (++misses >= maxMisses) return books
                    continue
                }
                val card = bookFromPage(page, loc)
                if (card == null) {
                    if (++misses >= maxMisses) return books
                    continue
                }
                misses = 0
                books += card
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
        s.replace(NUMERIC_HEX_ENTITY) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
            .replace(NUMERIC_ENTITY) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
            }
            .replace("&lt;", "<")
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

        /** #1486 — the site's own tag taxonomy sitemap (allowed by robots). */
        const val TAG_SITEMAP_URL: String = "https://audiobook.co.ua/post_tag-sitemap.xml"
        const val TAG_URL_PREFIX: String = "https://audiobook.co.ua/tag/"
        /** Keep the in-memory tag index for the same day the catalogue keeps. */
        const val TAG_INDEX_TTL_MS: Long = FeedSnapshotPolicy.CATALOG_TTL_MS
        /** #1486 — matched tags one query may use; the rest wait for the next query. */
        const val SEARCH_TAG_LIMIT: Int = 3
        /** #1486 — tag-page requests per query (first pages of every tag before any second). */
        const val SEARCH_PAGE_LIMIT: Int = 3
        /** #1486 — bounded result: at most three tag pages of ten books. */
        const val SEARCH_RESULT_LIMIT: Int = 30
        /** One `<loc>` of a WordPress sitemap. */
        val TAG_LOC = Regex("""<loc>\s*([^<\s]+)\s*</loc>""", RegexOption.IGNORE_CASE)
        /** A pagination link of one tag listing (relative or absolute). */
        private val TAG_PAGE_LINK = Regex("""href="(?:https://audiobook\.co\.ua)?(/tag/[^"]+/page/(\d+)/)""", RegexOption.IGNORE_CASE)
        private val PAGE_NUMBER = Regex("""/page/(\d+)/""")
        /** One book article of the list theme. */
        private const val ARTICLE_START: String = """<article id="post-"""
        /** The list card's title anchor («Назва — Автор»). */
        private val TITLE_LINK = Regex("""<h2[^>]*class="entry-title[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        /** The WordPress cover of one card. */
        private val COVER = Regex("""<img[^>]*(?:src|data-src)="(https://audiobook\.co\.ua/wp-content/uploads/[^"]+)""", RegexOption.IGNORE_CASE)
        /** Numeric HTML entities the live titles carry (e.g. `&#8212;` — the em dash). */
        private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
        private val NUMERIC_HEX_ENTITY = Regex("""&#x([0-9a-fA-F]+);""")

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
