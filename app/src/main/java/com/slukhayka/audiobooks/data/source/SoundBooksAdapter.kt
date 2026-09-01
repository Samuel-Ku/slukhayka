package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.privacy.PacingPolicy
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
 * Search: the DLE search endpoint is robots-discouraged (robots.txt disallows
 * the `do=search` route), so [search] returns empty and discovery goes through
 * [fetchNew] (homepage recent) and category pages (T4's job).
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
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) }
) : SourceAdapter {

    /** Spec-45 (#405) — the catalogue speaks Ukrainian. */
    override val contentLanguage = "uk"

    override val sourceId: String = "soundbooks"

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())

        val title = ogMeta(html, "og:title") ?: slugTitle(url)
        // Real author/narrator live in the «Автор: X. Читає: Y.» line (and the
        // page's JSON-LD "author": "…"); og:description is a blurb, not a name.
        val author = AUTHOR_MARK.find(html)?.groupValues?.get(1)?.trim()
            ?: JSONLD_AUTHOR.find(html)?.groupValues?.get(1)?.trim()
            ?: ""
        val narrator = NARRATOR_MARK.find(html)?.groupValues?.get(1)?.trim() ?: ""

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

        val playlist = fetcher.getText(m3uUrl)
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
        val html = fetcher.getText("https://sound-books.net/")
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
        val home = fetcher.getText("https://sound-books.net/")
        if (home.isEmpty()) return emptyList()
        val categories = CATEGORY_LINK.findAll(home)
            .map { it.groupValues[1] }
            .distinct()
            .take(categoryPageLimit)
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        var paced = false
        for (category in categories) {
            if (books.size >= limit) break
            if (paced) pauseMillis(pacing.nextPauseMillis())
            paced = true
            val html = fetcher.getText(category)
            if (html.isEmpty()) continue
            for (book in parseTiles(html, limit - books.size)) {
                if (seen.add(book.url)) books += book
            }
        }
        return books
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
        return best.entries.take(limit).map { entry ->
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

    private companion object {
        /**
         * Spec #462 ID5 (#468) — the named (and per-instance configurable,
         * via [categoryPageLimit]) category limit that replaced the old magic
         * `take(6)`: one user-initiated catalogue refresh walks this many
         * category pages, with [PacingPolicy] pauses between consecutive
         * requests so the wider walk keeps the human rhythm (spec-38).
         */
        const val CATEGORY_PAGE_LIMIT: Int = 20
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
