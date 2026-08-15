package com.example.data.source

import com.example.data.catalog.CatalogParser

/**
 * The 4read.org [SourceAdapter] (spec-10 T3). Transport + search/fetchNew;
 * the book page itself is parsed by the shared [WebViewHtmlParser] module
 * (spec-14 T4) — one parser for the server-fetch door here and the WebView
 * door, no third parser variant. The search-result poster parse lives here
 * (same regexes as before); fixture/Room tests pin the behavior.
 */
class FourReadAdapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://4read.org/")
) : SourceAdapter {

    override val sourceId: String = "4read"

    /** The "4read-slug" id scheme — in exactly this one place (spec-14 T5). */
    override fun bookId(url: String): String = CatalogParser.bookId(url)

    override suspend fun search(query: String): List<SourceBook> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "https://4read.org/index.php?do=search&subaction=search&story=$encodedQuery"
        val html = fetcher.getText(searchUrl)
        if (html.isEmpty()) return emptyList()

        // 4read renders each hit as a .poster block: the real Cyrillic title
        // in poster__title and the real author in the first poster__subtitle
        // (the second one carries the duration clock). Real authors matter for
        // the cross-source merge — a "4read.org" placeholder could never match
        // the same Work on sound-books.net.
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
            val author = POSTER_SUBTITLE.findAll(block)
                .map { stripTags(it.groupValues[1]).trim() }
                .firstOrNull { it.isNotBlank() }
                ?: ""
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
        val html = fetcher.getText(url)
        return WebViewHtmlParser().parse(html, url, resolveContent = { fetcher.getText(it) })
    }

    /**
     * Spec-14 T5 / ADR-0006 — the WebView door's captured page, parsed by
     * the same shared [WebViewHtmlParser] with this adapter's transport
     * resolving playlist/iframe content. The repository performs no 4read
     * parsing or transport — it hands the captured DOM straight to the seam.
     */
    override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail =
        WebViewHtmlParser().parse(html, url, resolveContent = { fetcher.getText(it) })

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

    private fun stripTags(html: String): String = html.replace(Regex("""<[^>]+>"""), "")

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
    }
}
