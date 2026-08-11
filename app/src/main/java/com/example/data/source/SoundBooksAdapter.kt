package com.example.data.source

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

        val m3uUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1) ?: return SourceBookDetail(
            title = title,
            author = author,
            narrator = narrator,
            url = url,
            chapters = emptyList()
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
            chapters = chapters
        )
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val html = fetcher.getText("https://sound-books.net/")
        if (html.isEmpty()) return emptyList()
        // Each entry renders twice on the homepage: a cover tile with a bare
        // title plus a «Назва - Автор» tile. Keep one row per url, preferring
        // the author-bearing anchor so the Work-level merge can form.
        val best = mutableMapOf<String, Pair<String, Boolean>>() // url -> (anchor, hasSeparator)
        BOOK_LINK.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val anchor = m.groupValues[2].trim().takeIf { it.length >= 3 } ?: return@forEach
            val hasSep = anchor.contains(" - ")
            val prev = best[url]
            if (prev == null || (hasSep && !prev.second)) {
                best[url] = anchor to hasSep
            }
        }
        return best.entries.take(limit).map { entry ->
            val url = entry.key
            val anchor = entry.value.first
            val sep = if (entry.value.second) anchor.indexOf(" - ") else -1
            SourceBook(
                title = (if (sep >= 0) anchor.substring(0, sep).trim() else anchor)
                    .ifBlank { slugTitle(url) },
                author = if (sep >= 0) anchor.substring(sep + 3).trim() else "",
                url = url,
                sourceId = sourceId
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
        val AUTHOR_MARK = Regex("""Автор:\s*([^.<]{2,80})""", RegexOption.IGNORE_CASE)
        val NARRATOR_MARK = Regex("""Читає:\s*([^.<]{2,80})""", RegexOption.IGNORE_CASE)
        val JSONLD_AUTHOR = Regex(""""author"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    }
}
