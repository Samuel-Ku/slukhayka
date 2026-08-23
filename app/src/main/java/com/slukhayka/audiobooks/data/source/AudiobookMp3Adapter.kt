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
 *
 * ## True profile completeness (spec-35 T4, inventory #237)
 *
 * Verified live: the book page carries the cover (visible `abook_image`), the
 * narrator («Виконавець:» panel row), the full duration (`fa-clock-o`,
 * HH:MM:SS), the genres («Жанр:» panel links) and the real blurb (the first
 * `<p>` of `.abook-desc`); the listing cards carry the narrator
 * (`a-info-item` fa-microphone), the duration (fa-clock-o) and the genre
 * (`abook-genre` links) next to the cover and the «Автор - Назва» anchor.
 * Measured negative findings, never fabricated (ADR-0014):
 * - **og:image is broken** — `https://audiobook-mp3.comhttps://cdn.…` (double
 *   prefix), so the cover is only ever the visible `abook_image`.
 * - **og:description is a TEMPLATE** («Слухати аудіокниги онлайн — <Назва>,
 *   безкоштовно та без реєстрації.»), never a blurb — the description is
 *   taken from the visible `.abook-desc` `<p>`.
 * - **No series/cycle** on either surface (no «Серія/Цикл» markers on the
 *   page or the cards), **no rating**.
 * - The page has a «Подібні аудіокнижки» related rail (cover + title + url),
 *   but the adapter does not take it yet (the app's related feature is
 *   4read-gated; out of T4 scope).
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
        // The real author lives in the «Автор:» panel row (and in the page's
        // JSON-LD as "author": "…"). The slug carries a transliterated
        // author-title blob with no delimiter, so it is never usable.
        val author = AUTHOR_LINK.find(html)?.groupValues?.get(1)?.trim()
            ?: JSONLD_AUTHOR.find(html)?.groupValues?.get(1)?.trim()
            ?: ""

        // Spec-35 T4: the page's own cover is the visible abook_image — the
        // og:image carries a broken double-prefix URL (#237) and is never
        // used. The narrator, the full duration and the genres are the
        // «Виконавець:» / fa-clock-o / «Жанр:» panel rows when present;
        // absent stays absent (ADR-0014).
        val coverImageUrl = COVER_IMG.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "https://audiobook-mp3.com$it" }
        val narrator = NARRATOR_LINK.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val totalDurationSeconds = durationSecondsFrom(html)
        val genres = genresFrom(html)

        // The real blurb is the first <p> of .abook-desc; og:description is a
        // site-wide TEMPLATE («Слухати аудіокниги онлайн — <Назва>, …», #237
        // negative finding) and is never substituted as the description.
        val description = descriptionFrom(html)

        val playlistUrl = PLAYLIST_URL.find(html)?.groupValues?.get(1)
            ?: return SourceBookDetail(
                title = title,
                author = author,
                narrator = narrator,
                url = url,
                coverImageUrl = coverImageUrl,
                chapters = emptyList(),
                totalDurationSeconds = totalDurationSeconds,
                genres = genres,
                description = description
            )

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
                        // The playerjs titles are file names («Роберт І. Говард 1 -
                        // Черепи серед Зірок.mp3») — strip the extension like the
                        // sluhay parser, so the UI shows the real chapter name, never
                        // a raw .mp3 (spec-35 #237 player-layer inventory).
                        title = titles.getOrNull(index)
                            ?.substringBeforeLast('.')
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: "Глава ${index + 1}",
                        streamUrl = file
                    )
                )
            }
        }

        return SourceBookDetail(
            title = title,
            author = author,
            narrator = narrator,
            url = url,
            coverImageUrl = coverImageUrl,
            chapters = chapters,
            totalDurationSeconds = totalDurationSeconds,
            genres = genres,
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
        val extras = cardExtras(html)
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
                val extra = extras[path] ?: CardExtras()
                SourceBook(
                    title = title.ifBlank { slugTitle(url) },
                    author = author,
                    narrator = extra.narrator,
                    url = url,
                    sourceId = sourceId,
                    coverImageUrl = covers[path],
                    genre = extra.genre,
                    totalDurationSeconds = extra.durationSeconds
                )
            }
            .take(limit)
            .toList()
    }

    /** The narrator/duration/genre a listing card carries, keyed by its /uk-audio path. */
    private data class CardExtras(
        val narrator: String = "",
        val durationSeconds: Long = 0L,
        val genre: String = ""
    )

    /**
     * Spec-35 T4 — the listing cards carry the narrator (`a-info-item`
     * fa-microphone), the duration (fa-clock-o) and the genre (`abook-genre`
     * links) in the same article block as the cover; cards without the rows
     * keep the fields empty (ADR-0014).
     */
    private fun cardExtras(html: String): Map<String, CardExtras> {
        val extras = mutableMapOf<String, CardExtras>()
        for (m in CARD_BLOCK.findAll(html)) {
            val block = m.value
            val path = CARD_URL.find(block)?.groupValues?.get(1) ?: continue
            val narrator = CARD_NARRATOR.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val duration = durationSecondsFrom(block) ?: 0L
            val genre = CARD_GENRE_BLOCK.find(block)?.groupValues?.get(1)
                ?.let { links -> LINK_TEXT.findAll(links).joinToString(", ") { decodeEntities(it.groupValues[1].trim()) } }
                .orEmpty()
            extras[path] = CardExtras(narrator = narrator, durationSeconds = duration, genre = genre)
        }
        return extras
    }

    /** Full duration (HH:MM:SS) from a fa-clock-o row, null when absent. */
    private fun durationSecondsFrom(html: String): Long? {
        // The row is <i class="fa fa-clock-o"></i> HH:MM:SS — the closing
        // </i> tag sits between the icon and the digits, so the optional tag
        // must be crossed before the digits (a bare `[^>]*>` stops at the
        // first `>` and can never reach the duration).
        val raw = Regex("""fa-clock-o[^>]*>(?:\s*</i>)?\s*(\d{1,2}:\d{2}(?::\d{2})?)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return null
        return parseDurationSeconds(raw)
    }

    /** The «Жанр:» panel links of the book page, in order; empty when absent. */
    private fun genresFrom(html: String): List<String> {
        val block = Regex("""Жанр:</span>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        return LINK_TEXT.findAll(block)
            .map { decodeEntities(it.groupValues[1].trim()) }
            .toList()
    }

    /**
     * The real blurb: the first `<p>` of `.abook-desc`. Empty when the page
     * carries none — og:description is a site template, never used (#237).
     */
    private fun descriptionFrom(html: String): String {
        val raw = Regex("""class="abook-desc".*?<p[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: return ""
        return decodeEntities(stripTags(raw)).trim()
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
        // The «Автор:» panel row (<span>Автор:</span> <a …>) and the older
        // plain «Автор: <a …>» form both resolve to the same link.
        val AUTHOR_LINK = Regex("""Автор:(?:\s*</span>)?\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
        val JSONLD_AUTHOR = Regex(""""author"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        val NARRATOR_LINK = Regex("""Виконавець:</span>\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
        val CARD_BLOCK = Regex("""<article class="abook-item">(.*?)</article>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val CARD_URL = Regex("""href="(/uk-audio-\d+-[^"]+)"[^>]*>\s*<img""", RegexOption.IGNORE_CASE)
        val CARD_NARRATOR = Regex("""fa-microphone[^>]*>.*?<a[^>]*>([^<]+)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val CARD_GENRE_BLOCK = Regex("""abook-genre">(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val LINK_TEXT = Regex("""<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
    }
}
