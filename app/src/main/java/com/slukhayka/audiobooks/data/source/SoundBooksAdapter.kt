package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.squareup.moshi.Moshi
import kotlinx.coroutines.delay

/**
 * sound-books.net [SourceAdapter] (spec-10 T1 verdict: PASS, server-fetch).
 *
 * Book pages embed the player playlist server-side:
 * `file:"https://sound-books.net/uploads/public_files/<yyyy-mm>/<id>-<slug>.m3u"`;
 * the .m3u is a plain-text list of direct chapter mp3s on the arch CDN
 * (`https://arch.sound-books.net/<id>/<Title>-NN.mp3`). Parsing = page → m3u →
 * mp3 lines.
 *
 * Search (#1486): the DLE search endpoint is robots-discouraged (robots.txt
 * disallows the `do=search` route), so [search] goes through the site's OWN
 * allowed taxonomy instead: `tags_pages.xml` lists the author/narrator/genre
 * tags (1828 at the 2026-10-27 spike), and a tag page (`/tags/<name>/`,
 * paginated like a category) lists that author's books with the feed's
 * `short-item` tiles. The book page's «Переглянути всі книги цього автора»
 * link is the same listing.
 *
 * The budget is the point: one sitemap read (cached for the catalogue TTL),
 * at most [searchTagLimit] tags and [searchPageLimit] tag-page REQUESTS per
 * query — the first page is immediate, every continuation rides the human
 * rhythm ([PacingPolicy]) — for at most [searchResultLimit] books. That is
 * why the query «стівен кінг» reaches ~30 books instead of the ten of a
 * single page, still without touching the disallowed search route. Only the
 * listing TILES are parsed: the tag page also carries a «останні коментарі»
 * sidebar whose links are books the author never wrote (the 2026-10-27
 * review caught 18 such rows on the live page).
 *
 * ## True profile completeness (spec-35 T5, inventory #237)
 *
 * Verified live: the book page carries the cover (og:image), the narrator
 * («Читає:»), the full duration («Триває: HH:MM:SS»), the genres («Жанр:»
 * links with category prefixes stripped), the rating («Рейтинг: N») and a
 * real blurb (og:description). The listing cards carry the duration («Триває:»
 * in `short-meta`), the genre (breadcrumb links), and a description blurb
 * (`short-text`). Measured negative findings, never fabricated (ADR-0014):
 * - **No narrator** on listing cards («Читає» exists only on book pages).
 * - **No rating** on listing cards (the word «rating» on the homepage is
 *   only a sort button `dle_change_sort('rating','desc')`, not a card score).
 * - **No series/cycle** on either surface (no «Серія/Цикл» markers).
 * - **No related rail** — the only cross-link is a filtered author search
 *   («Переглянути всі книги цього автора/читача»), not a «схожі» rail.
 */
class SoundBooksAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(),
    /** Spec #462 ID5 (#468) — how many category pages one catalogue walk opens. */
    private val categoryPageLimit: Int = CATEGORY_PAGE_LIMIT,
    /** Spec #462 ID5 (#468) — the human-rhythm pause source between pages. */
    private val pacing: PacingPolicy = PacingPolicy(),
    /** Injectable pause so tests pin the rhythm without sleeping (spec-38). */
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) },
    /** #1486 — how many matched tags one query may use. */
    private val searchTagLimit: Int = SEARCH_TAG_LIMIT,
    /** #1486 — how many tag-page REQUESTS one query may spend (1 = first pages only). */
    private val searchPageLimit: Int = SEARCH_PAGE_LIMIT,
    /** #1486 — how many books one query returns at most. */
    private val searchResultLimit: Int = SEARCH_RESULT_LIMIT,
    /** Injectable clock so the tag-index freshness is testable (policy convention). */
    private val now: () -> Long = System::currentTimeMillis
) : SourceAdapter {
    // Page + playlist may each wait for a token; Player's eight seconds are
    // for starting resolved media, not for acquiring these signed URLs.
    override val resolutionBudgetMs: Long = 45_000L

    /** Spec-45 (#405) — the catalogue speaks Ukrainian. */
    override val contentLanguage = "uk"

    override val sourceId: String = "soundbooks"

    override suspend fun search(query: String): List<SourceBook> {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return emptyList()
        val matched = tagIndex()
            .mapNotNull { tag -> tagRank(tag.name, clean)?.let { rank -> tag to rank } }
            .sortedWith(compareBy({ it.second }, { it.first.name.length }))
            .map { it.first }
            .take(searchTagLimit)
        if (matched.isEmpty()) return emptyList()

        val books = LinkedHashMap<String, SourceBook>()
        // Breadth first: every matched tag gets its FIRST page before any tag
        // gets a second one. A global page budget must not starve the second
        // author when the first one alone could fill it.
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
                parseSearchTiles(html, searchResultLimit - books.size)
                    .forEach { books.putIfAbsent(it.url, it) }
                nextPage[tag] = nextTagPage(pageUrl, html)
            }
        }
        return books.values.toList()
    }

    /**
     * The listing TILES only. A tag page carries the book tiles plus a
     * «останні коментарі» sidebar of unrelated book links; [parseTiles]
     * happily reads both, but a search answer must not. One block = one book.
     */
    private fun parseSearchTiles(html: String, limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val books = mutableListOf<SourceBook>()
        // The tile carries «Триває:» and the genre links — the same extras the
        // catalogue tile parser reads; a search card must not lose them.
        val extras = cardExtras(html)
        val blocks = html.split(TILE_START).drop(1)
        for (block in blocks) {
            if (books.size >= limit) break
            // The cover anchor wraps an <img>; the title anchor carries the
            // text. Prefer the text one, fall back to the cover's img alt.
            val titleLink = BOOK_LINK.findAll(block)
                .firstOrNull { !it.groupValues[2].contains("<img", ignoreCase = true) }
            val coverMatch = COVER_TILE.find(block)
            val url = titleLink?.groupValues?.get(1) ?: coverMatch?.groupValues?.get(1) ?: continue
            if (isPromoUrl(url)) continue
            val cover = coverMatch?.groupValues?.get(2)?.let { raw ->
                if (raw.startsWith("http")) raw else "https://sound-books.net$raw"
            }
            val anchor = titleLink?.groupValues?.get(2)?.trim()?.takeIf { it.length >= 3 }
                ?: coverMatch?.groupValues?.get(3)?.trim()?.takeIf { it.length >= 3 }
            val (title, author) = titleAuthorOf(anchor, url)
            val extra = extras[url] ?: CardExtras()
            books += SourceBook(
                title = title,
                author = author,
                url = url,
                sourceId = sourceId,
                coverImageUrl = cover,
                totalDurationSeconds = extra.durationSeconds,
                genre = extra.genre
            )
        }
        return books
    }

    /** «Назва - Автор» → (title, author); no separator → the whole anchor is the title. */
    private fun titleAuthorOf(anchor: String?, url: String): Pair<String, String> {
        if (anchor == null) return slugTitle(url) to ""
        val sep = anchor.indexOf(" - ")
        return if (sep > 0) {
            anchor.substring(0, sep).trim() to anchor.substring(sep + 3).trim()
        } else {
            anchor to ""
        }
    }

    /**
     * The next page of the SAME tag listing, or null on the last page. Both
     * `href="/tags/<name>/page/N/"` and the absolute form occur on the site.
     */
    private fun nextTagPage(current: String, html: String): String? {
        val path = current.removePrefix("https://sound-books.net")
        // The sitemap url ends with '/', the pagination path does not: compare
        // both sides WITHOUT the trailing slash or every link is rejected.
        val base = path.substringBefore("/page/").trimEnd('/')
        if (base.isBlank()) return null
        val currentPage = PAGE_NUMBER.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val next = TAG_PAGE_LINK.findAll(html).mapNotNull { match ->
            val linkedBase = match.groupValues[1].substringBefore("/page/").trimEnd('/')
            val linkedPage = match.groupValues[2].toIntOrNull()
            if (linkedPage != null && linkedBase == base && linkedPage > currentPage) linkedPage else null
        }.minOrNull() ?: return null
        return "https://sound-books.net$base/page/$next/"
    }

    /**
     * The site's author/narrator/genre tags from `tags_pages.xml`. The sitemap
     * is metadata under the catalogue TTL; the parsed index is kept in memory
     * so a keystroke never re-reads 428 KB. Freshness is the repo's ONE decision
     * ([FeedSnapshotPolicy.isFresh] — never fresh when the stamp lies ahead).
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
            val name = tagName(url) ?: return@mapNotNull null
            Tag(name, url)
        }.distinctBy { it.url }.toList()
        if (tags.isNotEmpty()) tagIndexCache = nowMillis to tags
        return tags.ifEmpty { cached?.second.orEmpty() }
    }

    /** `…/tags/стівен%20кінг/` → «стівен кінг»; a malformed sequence is a miss, never a crash. */
    private fun tagName(url: String): String? {
        val segment = url.removeSuffix("/").substringAfterLast('/')
        if (segment.isBlank()) return null
        return runCatching {
            java.net.URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    /** Exact tag first, then a prefix, then a substring — the smallest set wins. */
    private fun tagRank(name: String, query: String): Int? = when {
        name == query -> 0
        name.startsWith(query) -> 1
        name.contains(query) -> 2
        else -> null
    }

    /** One book-list tag: its display name and the allowed page url. */
    private data class Tag(val name: String, val url: String)

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        // #558: a promo article stored by an older build fails hydration
        // honestly — the same empty detail as an unreachable page — instead of
        // posing as a playless book forever.
        if (isPromoUrl(url)) return SourceBookDetail("", "", url = url, chapters = emptyList())
        val html = fetcher.awaitListenerText(url, cacheTtlMillis = 0L)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())

        // Real author/narrator live in the «Автор: X. Читає: Y.» line (and the
        // page's JSON-LD "author": "…"); og:description is a blurb, not a name.
        val author = AUTHOR_MARK.find(html)?.groupValues?.get(1)?.trim()
            ?: JSONLD_AUTHOR.find(html)?.groupValues?.get(1)?.trim()
            ?: ""
        val narrator = NARRATOR_MARK.find(html)?.groupValues?.get(1)?.trim() ?: ""
        val title = bookNameFromJsonLd(html)
            ?: MetadataAssertions.normalizeTitle(ogMeta(html, "og:title") ?: slugTitle(url), author)

        // Spec-15 T5: og:description is a real blurb here (unlike the author
        // line, which lives in «Автор: X. Читає: Y.») — carry it for the
        // per-source detail blocks.
        val description = ogMeta(html, "og:description")?.trim().orEmpty()

        // Spec-24 T9 (#170): the page carries its own cover in og:image — the
        // only cover signal of the book page (the tile covers live on listing
        // pages only). Absent → null, never fabricated; relative paths are
        // resolved against the site origin like the tile covers.
        val coverImageUrl = ogMeta(html, "og:image")?.trim()?.let { cover ->
            if (cover.startsWith("http")) cover else "https://sound-books.net$cover"
        }

        // Spec-35 T5: the full duration from «Триває: HH:MM:SS», the genre
        // breadcrumb links (with the «Аудіокниги» category prefix stripped)
        // and the rating («Рейтинг: N») — all present on the book page;
        // absent stays null/empty (ADR-0014).
        val totalDurationSeconds = durationFrom(html)
        val genres = genresFrom(html)
        val rating = ratingFrom(html)

        val m3uUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1) ?: return SourceBookDetail(
            title = title,
            author = author,
            narrator = narrator,
            url = url,
            chapters = emptyList(),
            description = description,
            coverImageUrl = coverImageUrl,
            totalDurationSeconds = totalDurationSeconds,
            genres = genres,
            rating = rating
        )

        val playlist = fetcher.awaitListenerText(m3uUrl, cacheTtlMillis = 0L)
        val chapters = playlist.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .mapIndexed { index, stream ->
                SourceChapter(
                    title = chapterTitleFromStream(stream).ifBlank { "Глава ${index + 1}" },
                    streamUrl = stream
                )
            }

        return SourceBookDetail(
            title = title,
            author = author,
            narrator = narrator,
            url = url,
            chapters = chapters,
            description = description,
            coverImageUrl = coverImageUrl,
            totalDurationSeconds = totalDurationSeconds,
            genres = genres,
            rating = rating
        )
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val html = fetcher.getText("https://sound-books.net/", emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS)
        if (html.isEmpty()) return emptyList()
        return parseTiles(html, limit)
    }

    /**
     * Spec-15 T1 — catalogue enumeration: the homepage's category sections
     * (`https://sound-books.net/<category>/`) are the source's full catalogue;
     * each category page carries the same tile markup as the homepage feed, so
     * the union parses category pages and dedupes by url.
     *
     * Spec #462 ID5 (#468): the walk is no longer a hard-coded `take(6)` —
     * it opens up to [categoryPageLimit] pages, paced by [PacingPolicy]
     * pauses between consecutive requests (spec-38: bulk fetching never
     * looks like scraping). Requests ride the shared [HttpFetcher] — the
     * listener's privacy transport route (TransportPrivacy).
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        val home = fetcher.getText("https://sound-books.net/", emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS)
        if (home.isEmpty()) return emptyList()
        val categories = CATEGORY_LINK.findAll(home)
            .map { it.groupValues[1] }
            .distinct()
            // #558: the promo section (/reklama/) is a first-class link on the
            // homepage — never a catalogue category to walk into.
            .filterNot { isPromoUrl(it) }
            .take(categoryPageLimit)
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        var paced = false
        for (category in categories) {
            if (books.size >= limit) break
            if (paced) pauseMillis(pacing.nextPauseMillis())
            paced = true
            val html = fetcher.getText(category, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS)
            if (html.isEmpty()) continue
            for (book in parseTiles(html, limit - books.size)) {
                if (seen.add(book.url)) books += book
            }
        }
        return books
    }

    /**
     * #528 — ONE listener-opened category page and the cursor of the next.
     * Exactly one request: the category's first page, or — when the caller
     * passes the cursor from the previous action — the NEXT page, behind the
     * ordinary pacing pause. A foreign path, the promo section or a malformed
     * cursor costs no request at all. Discovery never touches the
     * robots-discouraged site search.
     */
    override suspend fun fetchGenrePage(genrePath: String, cursor: String?, limit: Int): GenrePage {
        val path = (cursor ?: genrePath).trim()
        if (!isAllowedCategoryPath(path)) return GenrePage(emptyList())
        // A continuation is a new action, but it still observes the rhythm.
        if (cursor != null) pauseMillis(pacing.nextPauseMillis())
        val html = fetcher.getText(
            "https://sound-books.net$path",
            emptyMap(),
            SourceRequestClass.LISTENER_ACTION,
            0L
        )
        if (html.isEmpty()) return GenrePage(emptyList())
        return GenrePage(parseTiles(html, limit), nextCategoryPage(path, html))
    }

    /** `/fantastyka/` or `/fantastyka/page/2/` — the ONLY shapes a category has. */
    private fun isAllowedCategoryPath(path: String): Boolean =
        CATEGORY_PATH.matches(path) && !path.startsWith("/reklama/")

    /** The category's NEXT page path, or null on the last page. */
    private fun nextCategoryPage(path: String, html: String): String? {
        val slug = path.trim('/').substringBefore("/page/")
        if (slug.isBlank()) return null
        val current = PAGE_NUMBER.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val next = PAGE_PATH.findAll(html).mapNotNull { match ->
            val linkedSlug = match.groupValues[1]
            val page = match.groupValues[2].toIntOrNull()
            if (linkedSlug == slug && page != null && page > current) page else null
        }.minOrNull() ?: return null
        return "/$slug/page/$next/"
    }

    /** The duration/genre a listing card carries, keyed by its .html url. */
    private data class CardExtras(
        val durationSeconds: Long = 0L,
        val genre: String = ""
    )

    /**
     * Spec-35 T5 — the listing cards carry the duration («Триває:» in
     * `short-meta-item`), the genre (breadcrumb links in `short-meta-item`)
     * and the description (the `short-text` div), keyed by the book's url.
     * Cards without these rows keep the fields empty (ADR-0014).
     *
     * The page's `short-item` nesting is deep (short-cols > short-desc >
     * short-text plus short-meta > meta-item). A single regex for the outer
     * div would be fragile, so we split on the delimiter instead.
     */
    private fun cardExtras(html: String): Map<String, CardExtras> {
        val extras = mutableMapOf<String, CardExtras>()
        val parts = html.split("""<div class="short-item">""")
        for (part in parts.drop(1)) {
            val block = part
            val cardUrl = CARD_URL.find(block)?.groupValues?.get(1) ?: continue
            val duration = durationFrom(block) ?: 0L
            val genre = CARD_GENRE_BLOCK.find(block)?.groupValues?.get(1)
                ?.let { links ->
                    GENRE_LINK.findAll(links)
                        .map {
                            val raw = decodeEntities(it.groupValues[1].trim())
                            if (raw.startsWith("Аудіокниги ")) raw.removePrefix("Аудіокниги ") else raw
                        }
                        .joinToString(", ")
                }
                .orEmpty()
            extras[cardUrl] = CardExtras(durationSeconds = duration, genre = genre)
        }
        return extras
    }

    /** Parses one page's cover tiles + text anchors into [SourceBook] rows. */
    private fun parseTiles(html: String, limit: Int): List<SourceBook> {
        // The cover tile carries the poster AND the book's title in the img
        // alt: <a class="short-img" href="…"><img data-src="/uploads/posts/…"
        // alt="Назва"></a> — the path is relative on this site.
        val covers = mutableMapOf<String, String>()
        val altTitles = mutableMapOf<String, String>() // url -> img-alt title
        COVER_TILE.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            if (!covers.containsKey(url)) {
                val img = m.groupValues[2]
                covers[url] = if (img.startsWith("http")) img else "https://sound-books.net$img"
            }
            val alt = m.groupValues[3].trim()
            if (alt.length >= 3 && !altTitles.containsKey(url)) {
                altTitles[url] = alt
            }
        }
        // Each entry renders twice on a listing page: a cover tile with a bare
        // title plus a «Назва - Автор» tile. Keep one row per url, preferring
        // the author-bearing anchor so the Work-level merge can form.
        val best = mutableMapOf<String, Pair<String, Boolean>>() // url -> (anchor, hasSeparator)
        BOOK_LINK.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val inner = m.groupValues[2]
            // The cover tiles (<a class="short-img">…<img …></a>) carry the same
            // .html href but their inner content is a raw <img> tag, never a
            // title — skip them so a lazy-loaded cover can't become the book's
            // title (2026-08-17: «Статут …» books came out titled
            // `<img data-src=…>`, winning over the real text anchor).
            if (inner.contains("<img", ignoreCase = true)) return@forEach
            val anchor = inner.trim().takeIf { it.length >= 3 } ?: return@forEach
            val hasSep = anchor.contains(" - ")
            val prev = best[url]
            if (prev == null || (hasSep && !prev.second)) {
                best[url] = anchor to hasSep
            }
        }
        // Text anchors are authoritative; the cover tile's img alt fills in
        // only the books a listing renders image-only (no text anchor at all),
        // so a lazy-loaded cover never outranks the real title.
        altTitles.forEach { (url, alt) -> if (!best.containsKey(url)) best[url] = alt to false }
        val extras = cardExtras(html)
        // #558: promo tiles ride the same markup as books; they are dropped
        // BEFORE the limit so an ad can never crowd out a real book.
        return best.entries.filter { !isPromoUrl(it.key) }.take(limit).map { entry ->
            val url = entry.key
            val anchor = entry.value.first
            val sep = if (entry.value.second) anchor.indexOf(" - ") else -1
            val extra = extras[url] ?: CardExtras()
            SourceBook(
                title = (if (sep >= 0) anchor.substring(0, sep).trim() else anchor)
                    .ifBlank { slugTitle(url) },
                author = if (sep >= 0) anchor.substring(sep + 3).trim() else "",
                url = url,
                sourceId = sourceId,
                coverImageUrl = covers[url],
                totalDurationSeconds = extra.durationSeconds,
                genre = extra.genre
            )
        }
    }

    /**
     * Full duration from «Триває: HH:MM:SS» or «Триває: HH:MM» on the page.
     * Null when absent (ADR-0014). Delegates to the shared [parseDurationSeconds]
     * so HH:MM:SS vs MM:SS handling cannot drift across adapters (spec-35 T1).
     *
     * The label may be wrapped as `<b>Триває:</b>` or `<b>Триває: HH:MM:SS</b>`
     * with optional `<strong>` around the value; the regex allows an optional
     * closing tag and any inline tags before the digits.
     */
    private fun durationFrom(html: String): Long? {
        val raw = Regex(
            """Триває:\s*(?:</b>\s*)?(?:<[^>]+>\s*)*(\d{1,2}:\d{2}(?::\d{2})?)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return null
        return parseDurationSeconds(raw)
    }

    /**
     * Genre names from the «Жанр:» links on the book page. The site prefixes
     * each link text with «Аудіокниги » (e.g. «Аудіокниги Зарубіжна література»);
     * the prefix is stripped to yield the real genre name.
     */
    private fun genresFrom(html: String): List<String> {
        val block = Regex(
            """Жанр:\s*(?:</b>\s*)?(.*?)</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.get(1) ?: return emptyList()
        return GENRE_LINK.findAll(block)
            .map {
                val raw = decodeEntities(it.groupValues[1].trim())
                if (raw.startsWith("Аудіокниги ")) raw.removePrefix("Аудіокниги ") else raw
            }
            .toList()
    }

    /**
     * Rating from «Рейтинг: N» on the book page; null when absent.
     * Like [durationFrom], the label may be `<b>Рейтинг:</b>` with optional
     * tags before the number.
     */
    private fun ratingFrom(html: String): Double? {
        val raw = Regex(
            """Рейтинг:\s*(?:</b>\s*)?(?:<[^>]+>\s*)*(\d+\.?\d*)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return null
        return raw.toDoubleOrNull()
    }

    /** WebSite/WebPage names are SEO copy; only the Book's own name is a title. */
    private fun bookNameFromJsonLd(html: String): String? {
        fun findBook(value: Any?, depth: Int = 0): String? {
            if (depth > 12) return null
            return when (value) {
                is Map<*, *> -> {
                    val types = value["@type"].let { if (it is List<*>) it else listOf(it) }
                    val name = (value["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    if ("Book" in types && name != null) name
                    else value.values.firstNotNullOfOrNull { findBook(it, depth + 1) }
                }
                is List<*> -> value.firstNotNullOfOrNull { findBook(it, depth + 1) }
                else -> null
            }
        }
        return JSONLD_SCRIPT.findAll(html).firstNotNullOfOrNull { script ->
            val value = runCatching { JSON_VALUE.fromJson(script.groupValues[1]) }.getOrNull()
            findBook(value)
        }
    }

    private fun slugTitle(url: String): String {
        // Book URLs are <category>/<id>-<slug>.html; the title is the slug.
        val slug = url.substringAfterLast('/').substringBeforeLast('.')
        return titleFromSlug(slug.substringAfter('-', slug))
    }

    /**
     * Playlist URLs are physical Source-track locators, so they stay exactly
     * as supplied. Only the filename displayed as a Chapter title is decoded.
     * A malformed percent sequence is a title fallback, never an import error.
     */
    private fun chapterTitleFromStream(streamUrl: String): String {
        val filename = streamUrl.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return runCatching {
            java.net.URLDecoder.decode(filename.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault("")
    }

    /** In-memory tag index: the sitemap moves slowly, a keystroke must stay cheap. */
    @Volatile
    private var tagIndexCache: Pair<Long, List<Tag>>? = null

    private companion object {
        /** #1486 — the site's own taxonomy sitemap (allowed by robots). */
        const val TAG_SITEMAP_URL: String = "https://sound-books.net/tags_pages.xml"
        const val TAG_URL_PREFIX: String = "https://sound-books.net/tags/"
        /** Keep the in-memory tag index for the same day the catalogue keeps. */
        const val TAG_INDEX_TTL_MS: Long = FeedSnapshotPolicy.CATALOG_TTL_MS
        /** #1486 — matched tags one query may use; the rest wait for the next query. */
        const val SEARCH_TAG_LIMIT: Int = 3
        /**
         * #1486 — tag-page REQUESTS per query. One page is ten books; three
         * requests reach ~30 for a single-author query. Every continuation is a
         * paced request, so this is the latency/breadth trade the listener pays.
         */
        const val SEARCH_PAGE_LIMIT: Int = 3
        /** #1486 — bounded result: at most [SEARCH_PAGE_LIMIT] pages of ten books. */
        const val SEARCH_RESULT_LIMIT: Int = 30
        /** One `<loc>` of the sitemap index. */
        val TAG_LOC = Regex("""<loc>\s*([^<\s]+)\s*</loc>""", RegexOption.IGNORE_CASE)
        /** A pagination link of one tag listing (relative or absolute). */
        private val TAG_PAGE_LINK = Regex("""href="(?:https://sound-books\.net)?(/tags/[^"]+/page/(\d+)/)""", RegexOption.IGNORE_CASE)
        /** The `.short-item` block start — one block is one listing card. */
        private const val TILE_START: String = """<div class="short-item">"""

        /**
         * Spec #462 ID5 (#468) — the named (and per-instance configurable,
         * via [categoryPageLimit]) category limit that replaced the old magic
         * `take(6)`: one user-initiated catalogue refresh walks this many
         * category pages, with [PacingPolicy] pauses between consecutive
         * requests so the wider walk keeps the human rhythm (spec-38).
         */
        const val CATEGORY_PAGE_LIMIT: Int = 20
        val JSON_VALUE = Moshi.Builder().build().adapter(Any::class.java)
        val JSONLD_SCRIPT = Regex(
            """<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val PLAYLIST_URL = Regex("""file\s*:\s*"(https?://[^"]+\.m3u)"""", RegexOption.IGNORE_CASE)
        // Real tiles carry attributes before href (`<a class="short-title" href=…>`),
        // so the anchor tag is matched loosely.
        val BOOK_LINK = Regex("""<a\s+[^>]*href="(https://sound-books\.net/[^"]+\.html)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        // alt is optional (and only captured when it follows the src/data-src
        // attribute) — an alt-less cover tile must never lose its cover over a
        // missing title signal (code-review hardening, 2026-08-17).
        val COVER_TILE = Regex("""<a\s+class="short-img[^"]*"\s+href="(https://sound-books\.net/[^"]+\.html)"[^>]*>\s*<img[^>]*(?:data-src|src)="([^"]+)"(?:[^>]*\s+alt="([^"]*)")?""", RegexOption.IGNORE_CASE)
        // Category sections of the full catalogue (`https://sound-books.net/<slug>/`).
        val CATEGORY_LINK = Regex("""href="(https://sound-books\.net/[a-z-]+/)"""", RegexOption.IGNORE_CASE)

        /** #528 — the only category path shapes a listener action may open. */
        private val CATEGORY_PATH = Regex("""^/[a-z0-9-]+/(?:page/\d+/)?$""")

        /** #528 — a pagination link of one category page. */
        private val PAGE_PATH = Regex("""href="/([a-z0-9-]+)/page/(\d+)/"""", RegexOption.IGNORE_CASE)

        /** #528 — the page number inside a continuation path. */
        private val PAGE_NUMBER = Regex("""/page/(\d+)/""")

        /**
         * #558 — site promo sections that render in the same tiles as books.
         * `reklama` articles leaked into the catalogue feeds and the on-device
         * recommendation row as non-audiobook cards that fail on open
         * («Couldn't open the book»). The first path segment decides; new
         * promo sections join this set when reported.
         */
        val PROMO_SEGMENTS = setOf("reklama")

        /** Whether a sound-books.net url is a promo article, not a book. */
        fun isPromoUrl(url: String): Boolean {
            val path = url.substringAfter("sound-books.net", "").removePrefix("/")
            return path.substringBefore('/') in PROMO_SEGMENTS
        }
        val AUTHOR_MARK = Regex("""Автор:\s*([^.<]{2,80})""", RegexOption.IGNORE_CASE)
        val NARRATOR_MARK = Regex("""Читає:\s*([^.<]{2,80})""", RegexOption.IGNORE_CASE)
        val JSONLD_AUTHOR = Regex(""""author"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        // Spec-35 T5 — listing card sub-elements (CARD_ITEM split is via
        // `html.split("<div class=\"short-item\">")`, not a regex).
        val CARD_URL = Regex("""href="(https://sound-books\.net/[^"]+\.html)"[^>]*>\s*<img""", RegexOption.IGNORE_CASE)
        val CARD_GENRE_BLOCK = Regex("""<span class="fal fa-folder"[^>]*></span>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val GENRE_LINK = Regex("""<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
    }
}
