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

        // og:title is «Автор — Назва <seo-суфікс> <сайт>»; take the part after
        // the FIRST separator as the title, then strip the site-URL tail. The
        // generic «слухати онлайн аудіокнигу безкоштовно» suffix is scrubbed
        // later by MetadataAssertions.normalizeTitle.
        val rawTitle = ogMeta(html, "og:title") ?: slugTitle(url)
        val title = splitAuthorTitle(rawTitle).second
            .replace(SITE_URL_TAIL, "")
            .trim()
            .ifBlank { rawTitle }
        // The real author lives in the «Автор:» breadcrumb link (and in the
        // page's JSON-LD as "author": "…"). The slug carries a transliterated
        // author-title blob with no delimiter, so it is never usable.
        val author = AUTHOR_LINK.find(html)?.groupValues?.get(1)?.trim()
            ?: JSONLD_AUTHOR.find(html)?.groupValues?.get(1)?.trim()
            ?: ""

        // Spec-15 T5: og:description is the book's own blurb — carry it for
        // the per-source detail blocks.
        val description = ogMeta(html, "og:description")?.trim().orEmpty()

        // The page's real cover is `<img class="abook_image" src="…">`
        // (og:image is malformed here — two URLs concatenated).
        val coverImageUrl = COVER_IMG.find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "https://audiobook-mp3.com$it" }

        val playlistUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1)
            ?: return SourceBookDetail(title = title, author = author, url = url, chapters = emptyList(), description = description, coverImageUrl = coverImageUrl)

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
            description = description,
            coverImageUrl = coverImageUrl
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
                // The /uk feed renders each entry as «Автор — Назва» (em-dash)
                // or «Автор - Назва» (hyphen) in real Cyrillic; comment-links
                // («"Валер’ян Підмогильний — Місто"») also carry surrounding
                // quotes — stripped so the real title and author survive.
                val anchor = m.groupValues[2].trim().trim('"').trim()
                val (author, title) = splitAuthorTitle(anchor)
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
     * Splits «Автор — Назва» / «Автор - Назва» on the FIRST em-dash, en-dash
     * or hyphen separator (the title itself may contain a later « - »). No
     * separator → ("" , whole anchor).
     */
    private fun splitAuthorTitle(anchor: String): Pair<String, String> {
        val parts = anchor.split(Regex("""\s+[—–-]\s+"""), limit = 2)
        val author = parts.getOrNull(0)?.trim().orEmpty()
        val title = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: anchor.trim()
        return author to title
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
        // The book page's real cover (og:image on this site is malformed).
        val COVER_IMG = Regex("""<img\s+class="abook_image"[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        // The site-URL tail appended to the og:title («… audiobook-mp3.com/uk»).
        val SITE_URL_TAIL = Regex("""\s*(?:audiobook-mp3\.com/uk|audiobook-mp3\.com)\s*$""", RegexOption.IGNORE_CASE)
        // Genre (category) pages of the full catalogue — `/uk-genre-<id>-<slug>`.
        val GENRE_LINK = Regex("""href="(/uk-genre-\d+-[^"]+)"""", RegexOption.IGNORE_CASE)
        val AUTHOR_LINK = Regex("""Автор:\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
        val JSONLD_AUTHOR = Regex(""""author"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    }
}
