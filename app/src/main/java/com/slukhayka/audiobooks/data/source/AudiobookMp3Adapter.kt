package com.slukhayka.audiobooks.data.source

/**
 * audiobook-mp3.com/uk [SourceAdapter] (spec-10 T1 verdict: PASS, server-fetch).
 *
 * The /uk section is a playerjs library (the same player framework 4read
 * uses): book pages reference a playlist JSON on the `redirectto.cc` CDN
 * (`https://<hash>.redirectto.cc/s05/<nested>/<id>.pl.txt`), which yields
 * `[{"title":"001.mp3","file":"https://…/track-N.mp3"}]`. The CDN 403s without
 * a Referer, so this adapter's fetcher always sends one.
 *
 * Search: genre pages (`/uk-genre-*`) are the site's discovery; a dedicated
 * /uk search endpoint was not verified in the spike, so [search] stays empty
 * and [fetchNew] parses the homepage's recent additions.
 */
class AudiobookMp3Adapter(
    private val fetcher: HttpFetcher = HttpFetcher(referer = "https://audiobook-mp3.com/uk")
) : SourceAdapter {

    override val sourceId: String = "audiobookmp3"

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())

        val title = ogMeta(html, "og:title") ?: slugTitle(url)
        // The real author lives in the «Автор:» breadcrumb link (and in the
        // page's JSON-LD as "author": "…"). The slug carries a transliterated
        // author-title blob with no delimiter, so it is never usable.
        val author = AUTHOR_LINK.find(html)?.groupValues?.get(1)?.trim()
            ?: JSONLD_AUTHOR.find(html)?.groupValues?.get(1)?.trim()
            ?: ""

        // Spec-15 T5: og:description is the book's own blurb — carry it for
        // the per-source detail blocks.
        val description = ogMeta(html, "og:description")?.trim().orEmpty()

        val playlistUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1)
            ?: return SourceBookDetail(title = title, author = author, url = url, chapters = emptyList(), description = description)

        val playlistJson = fetcher.getText(playlistUrl)
        val chapters = mutableListOf<SourceChapter>()
        // The playerjs playlist is a small JSON array of {title, file} objects;
        // a regex parse (same approach as the 4read playlist expansion) keeps
        // the adapter pure JVM and free of org.json stubs in unit tests.
        if (playlistJson.trim().startsWith("[{")) {
            val fileRegex = Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            val titleRegex = Regex(""""title"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            val files = fileRegex.findAll(playlistJson).map { it.groupValues[1] }.toList()
            val titles = titleRegex.findAll(playlistJson).map { it.groupValues[1] }.toList()
            files.forEachIndexed { index, file ->
                chapters.add(
                    SourceChapter(
                        title = titles.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Глава ${index + 1}",
                        streamUrl = file
                    )
                )
            }
        }

        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            chapters = chapters,
            description = description
        )
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val html = fetcher.getText("https://audiobook-mp3.com/uk")
        if (html.isEmpty()) return emptyList()
        return parseTiles(html, limit)
    }

    /**
     * Spec-15 T1 — catalogue enumeration: the /uk genre pages
     * (`/uk-genre-<id>-<slug>`) are the source's full catalogue; each genre
     * page carries the same tiles as the homepage feed, so the union parses a
     * few genre pages and dedupes by url.
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        val home = fetcher.getText("https://audiobook-mp3.com/uk")
        if (home.isEmpty()) return emptyList()
        val genres = GENRE_LINK.findAll(home)
            .map { it.groupValues[1] }
            .distinct()
            .take(6)
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        for (genre in genres) {
            if (books.size >= limit) break
            val html = fetcher.getText("https://audiobook-mp3.com$genre")
            if (html.isEmpty()) continue
            for (book in parseTiles(html, limit - books.size)) {
                if (seen.add(book.url)) books += book
            }
        }
        return books
    }

    /** Parses one listing page's cover tiles + text anchors into [SourceBook] rows. */
    private fun parseTiles(html: String, limit: Int): List<SourceBook> {
        // Each entry's cover rides in its own tile: <a class="image-abook"
        // href="/uk-audio-…"><img class="b-showshort__cover_image"
        // src="https://cdn.audiobook-mp3.com/audiobooks/uk/…"></a>.
        val covers = mutableMapOf<String, String>()
        COVER_TILE.findAll(html).forEach { m -> covers[m.groupValues[1]] = m.groupValues[2] }
        val seen = mutableSetOf<String>()
        return BOOK_LINK.findAll(html)
            .mapNotNull { m ->
                val path = m.groupValues[1]
                val url = "https://audiobook-mp3.com$path"
                // The image-abook cover tile precedes the text anchor and has
                // no text — it must not win the dedupe, or every feed entry
                // falls back to its transliterated slug.
                if (m.groupValues[2].trim().length < 3) return@mapNotNull null
                if (!seen.add(url)) return@mapNotNull null
                // The /uk feed renders each entry as «Автор - Назва» in real
                // Cyrillic — the real title and author, no page fetch needed.
                val anchor = m.groupValues[2].trim()
                val sep = anchor.indexOf(" - ")
                val author = if (sep >= 0) anchor.substring(0, sep).trim() else ""
                val title = if (sep >= 0) anchor.substring(sep + 3).trim() else anchor
                SourceBook(
                    title = title.ifBlank { slugTitle(url) },
                    author = author,
                    url = url,
                    sourceId = sourceId,
                    coverImageUrl = covers[path]
                )
            }
            .take(limit)
            .toList()
    }

    /**
     * The /uk slugs are transliterated `author-title` after the numeric id;
     * the author/title boundary is not delimited, so the whole remainder is
     * used as the feed title (the real title comes from the page's og:title
     * once the book page is fetched).
     */
    private fun slugTitle(url: String): String {
        val slug = url.substringAfterLast('/').substringBefore('?')
        val remainder = slug.substringAfter("uk-audio-", slug).substringAfter("-", slug)
        return titleFromSlug(remainder).ifBlank { slug }
    }

    private companion object {
        val PLAYLIST_URL = Regex("""(https://[a-z0-9]+\.redirectto\.cc/[^"'<> ]+\.pl\.txt)""", RegexOption.IGNORE_CASE)
        val BOOK_LINK = Regex("""href="(/uk-audio-\d+-[^"]+)"[^>]*>([^<]*)""", RegexOption.IGNORE_CASE)
        val COVER_TILE = Regex("""<a\s+class="image-abook"\s+href="([^"]+)"[^>]*>\s*<img[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        // Genre (category) pages of the full catalogue — `/uk-genre-<id>-<slug>`.
        val GENRE_LINK = Regex("""href="(/uk-genre-\d+-[^"]+)"""", RegexOption.IGNORE_CASE)
        val AUTHOR_LINK = Regex("""Автор:\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
        val JSONLD_AUTHOR = Regex(""""author"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    }
}
