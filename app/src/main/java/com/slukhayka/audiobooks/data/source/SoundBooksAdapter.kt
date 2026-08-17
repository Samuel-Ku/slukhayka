package com.slukhayka.audiobooks.data.source

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
 */
class SoundBooksAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

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

        val m3uUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1) ?: return SourceBookDetail(
            title = title,
            author = author,
            narrator = narrator,
            url = url,
            chapters = emptyList(),
            description = description,
            coverImageUrl = coverImageUrl
        )

        val playlist = fetcher.getText(m3uUrl)
        val chapters = playlist.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .mapIndexed { index, stream ->
                SourceChapter(
                    title = stream.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Глава ${index + 1}" },
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
            coverImageUrl = coverImageUrl
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
     * the union parses a few category pages and dedupes by url.
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        val home = fetcher.getText("https://sound-books.net/")
        if (home.isEmpty()) return emptyList()
        val categories = CATEGORY_LINK.findAll(home)
            .map { it.groupValues[1] }
            .distinct()
            .take(6)
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        for (category in categories) {
            if (books.size >= limit) break
            val html = fetcher.getText(category)
            if (html.isEmpty()) continue
            for (book in parseTiles(html, limit - books.size)) {
                if (seen.add(book.url)) books += book
            }
        }
        return books
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
        return best.entries.take(limit).map { entry ->
            val url = entry.key
            val anchor = entry.value.first
            val sep = if (entry.value.second) anchor.indexOf(" - ") else -1
            SourceBook(
                title = (if (sep >= 0) anchor.substring(0, sep).trim() else anchor)
                    .ifBlank { slugTitle(url) },
                author = if (sep >= 0) anchor.substring(sep + 3).trim() else "",
                url = url,
                sourceId = sourceId,
                coverImageUrl = covers[url]
            )
        }
    }

    private fun ogMeta(html: String, property: String): String? =
        Regex("""<meta\s+property="$property"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content="([^"]+)"\s+property="$property"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun slugTitle(url: String): String {
        // Book URLs are <category>/<id>-<slug>.html; the title is the slug.
        val slug = url.substringAfterLast('/').substringBeforeLast('.')
        val title = slug.substringAfter('-', slug)
        return title.replace("-", " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
    }

    private companion object {
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
    }
}
