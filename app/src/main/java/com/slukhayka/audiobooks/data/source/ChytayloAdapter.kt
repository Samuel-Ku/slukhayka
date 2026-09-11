package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.LanguageCode

/**
 * chytaylo.com.ua [SourceAdapter] (spec-47 T3; endpoints verified live in the
 * T1 spike — `docs/wayfinder/research/sources-wave-47-spike.md`, fixtures in
 * `research/fixtures/chytaylo/`; markup re-verified live 2026-09-09 for T3 —
 * the listing renders 30 `<article>` cards per page, not the spike-sampled 60).
 *
 * Next.js SSR site, server-fetch, no WebView. Three mechanics:
 * - **Listing** (`/audiobooks`, `?categoryKey=…`, `?page=N`): server-rendered
 *   `<article>` cards. The card block repeats `href="/books/<slug>"` twice
 *   (cover anchor + text anchor); the first anchor carries the cover `<img>`,
 *   the second the title div followed by the author div. A card without the
 *   author div keeps an empty author — never an invented one.
 * - **Book page** (`/books/<slug>`): schema.org `Book` JSON-LD (name,
 *   author.name, image, inLanguage) + the player props embedded in the
 *   escaped React server-component payload:
 *   `\"tracks\":[{\"title\":…,\"url\":…}]` + `\"coverUrl\":…`. Track URLs are
 *   root-relative — prefixed with the site origin here, once.
 * - **Audio:** `/api/audio-local/…mp3` serves `206 audio/mpeg` to plain GETs
 *   with ranges (T1) — direct, seekable, downloadable.
 *
 * **Content boundary (spec-47, fixed in CONTEXT.md): AUDIO ONLY.** The page's
 * own payload decides — no `tracks` array means not an audiobook, and the
 * detail parses with empty chapters (the import doors treat that as nothing
 * playable). Text books, online reading and community pages of the same site
 * never enter the catalogue through this adapter.
 *
 * Measured absences (ADR-0014 — never fabricated):
 * - **Search:** no server-side search endpoint exists (T1 found none; the T3
 *   probes — `?s=`, `/api/search`, `/search` — filter nothing or 404) →
 *   [search] is an honest empty list; discovery rides [fetchNew],
 *   [fetchCatalog] and the union.
 * - **Narrator:** the site names none — the field stays empty, never guessed.
 * - **Duration, rating, series:** the book page carries none; «Частина N»
 *   track titles are real chapter names and are kept as rendered.
 */
class ChytayloAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "chytaylo"

    /** Spec-47 — the catalogue speaks Ukrainian (schema.org `inLanguage: uk-UA`). */
    override val contentLanguage = LanguageCode.UKRAINIAN

    /**
     * No server-side search endpoint (T1 spike; T3 probes 404 or filter
     * nothing) — the honest refusal the seam contract allows; unified search
     * covers the source through the catalogue union and the other sources.
     */
    override suspend fun search(query: String): List<SourceBook> = emptyList()

    /** Page 1 of the audio listing — the site's own order. */
    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val html = fetcher.getText(AUDIOBOOKS_URL, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS)
        if (html.isEmpty()) return emptyList()
        return listingBooks(html).take(limit)
    }

    /**
     * Spec-15 T1 — the depth of the catalogue: the listing pages walked in
     * order (30 cards per page live, `?page=N` — verified in T1/T3). Every
     * card keeps a real title/author pair parsed from that page, never a slug.
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val books = mutableListOf<SourceBook>()
        var page = 1
        while (books.size < limit) {
            val url = if (page == 1) AUDIOBOOKS_URL else "$AUDIOBOOKS_URL?page=$page"
            val html = fetcher.getText(url, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.CATALOG_TTL_MS)
            if (html.isEmpty()) break
            val cards = listingBooks(html)
            if (cards.isEmpty()) break
            for (book in cards) {
                if (books.size >= limit) return books
                books += book
            }
            page++
        }
        return books
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url, emptyMap(), SourceRequestClass.LISTENER_ACTION, 0L)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())
        val ld = bookJsonLd(html)
        val player = playerPropsFrom(html)
        val cover = ld?.cover?.let(::absoluteUrl)
            ?: player?.coverUrl?.let(::absoluteUrl)
            ?: ogMeta(html, "og:image")
        val tracks = player?.tracks.orEmpty()
        return SourceBookDetail(
            title = ld?.name ?: player?.bookTitle.orEmpty(),
            author = ld?.author.orEmpty(),
            url = url,
            coverImageUrl = cover,
            language = ld?.let { LanguageCode.normalize(it.inLanguage).orEmpty() } ?: "",
            chapters = tracks.map { track ->
                SourceChapter(title = track.title, streamUrl = absoluteUrl(track.url))
            },
            // Measured absence: the page renders no per-book duration.
            totalDurationSeconds = null,
            description = descriptionFrom(html)
        )
    }

    /** `/books/<slug>` URLs carry no trailing slash; `?`-suffixed ones keep working. */
    override fun bookId(url: String): String {
        val slug = url.substringBefore('?').removeSuffix("/").substringAfterLast('/')
            .ifBlank { "book" }
        return "$sourceId-$slug"
    }

    // --- parsing helpers -----------------------------------------------------

    /** One embedded player track: `{title, url}` of the props payload. */
    private data class PlayerTrack(val title: String, val url: String)

    /** The player-props fields the adapter consumes, in decoded form. */
    private data class PlayerProps(
        val tracks: List<PlayerTrack>,
        val bookTitle: String,
        val coverUrl: String
    )

    /**
     * The escaped player-props payload, decoded to plain JSON once. The props
     * sit inside React server-component text where every quote is `\"` (one
     * escape layer — verified byte-for-byte on the live page) and backslashes
     * are `\\`; [jsonUnescapeWindow] strips exactly that layer (leaving
     * `\uXXXX` for the per-value second pass), after which the plain JSON
     * helpers read `tracks`, `bookTitle` and `coverUrl`. No props payload →
     * null (a non-audiobook page).
     */
    private fun playerPropsFrom(html: String): PlayerProps? {
        val key = html.indexOf(ESCAPED_TRACKS_KEY)
        if (key < 0) return null
        val decoded = jsonUnescapeWindow(html.substring(key))
        val arrayKeyAt = decoded.indexOf(PLAIN_TRACKS_KEY)
        if (arrayKeyAt < 0) return null
        // PLAIN_TRACKS_KEY ends WITH the opening bracket — start the scan on
        // it, not one past it (one past would never balance).
        val array = balancedContent(decoded, arrayKeyAt + PLAIN_TRACKS_KEY.length - 1, '[', ']')
            ?: return null
        val tracks = mutableListOf<PlayerTrack>()
        var pos = 0
        while (true) {
            val objStart = array.indexOf('{', pos)
            if (objStart < 0) break
            val obj = balancedContent(array, objStart, '{', '}') ?: break
            val url = quotedField(obj, "url")
            val title = quotedField(obj, "title").orEmpty().trim()
            if (!url.isNullOrBlank() && title.isNotBlank()) {
                tracks += PlayerTrack(title, url)
            }
            pos = objStart + obj.length
        }
        return PlayerProps(
            tracks = tracks,
            bookTitle = quotedField(decoded, BOOK_TITLE_KEY).orEmpty(),
            coverUrl = quotedField(decoded, COVER_URL_KEY).orEmpty()
        )
    }

    /** schema.org `Book` JSON-LD fields the adapter consumes. */
    private data class BookLd(
        val name: String,
        val author: String,
        val cover: String?,
        val inLanguage: String
    )

    /**
     * The schema.org `Book` block (a plain JSON-LD script; the live page
     * wraps the site's Organization/WebSite/Book blocks in ONE array script,
     * so the fields are read from the Book OBJECT, not from the script's
     * first object). Absent or non-Book → null.
     */
    private fun bookJsonLd(html: String): BookLd? {
        var from = 0
        while (true) {
            val open = html.indexOf(LD_JSON_MARKER, from)
            if (open < 0) return null
            val jsonStart = open + LD_JSON_MARKER.length
            val jsonEnd = html.indexOf("</script>", jsonStart)
            if (jsonEnd < 0) return null
            val book = bookObjectIn(html.substring(jsonStart, jsonEnd))
            if (book != null) {
                val name = quotedField(book, "name")
                if (name != null) {
                    return BookLd(
                        name = name,
                        author = quotedObjectField(book, "author", "name").orEmpty(),
                        cover = quotedField(book, "image")?.takeIf { it.isNotBlank() },
                        inLanguage = quotedField(book, "inLanguage").orEmpty()
                    )
                }
            }
            from = jsonEnd
        }
    }

    /** The first `{…}` object of the JSON-LD that claims `"@type":"Book"`. */
    private fun bookObjectIn(json: String): String? {
        var pos = 0
        while (true) {
            val objStart = json.indexOf('{', pos)
            if (objStart < 0) return null
            val obj = balancedContent(json, objStart, '{', '}') ?: return null
            if (LD_BOOK_TYPE.containsMatchIn(obj)) return obj
            pos = objStart + obj.length
        }
    }

    /**
     * The listing's annotation: the «Про що книга» section's container body,
     * bounded by a div-depth count so a nested container cannot truncate it
     * and the sections after cannot leak in. Absent section → "".
     */
    private fun descriptionFrom(html: String): String {
        val heading = html.indexOf(DESCRIPTION_HEADING)
        if (heading < 0) return ""
        val openTag = html.indexOf("<div", heading)
        if (openTag < 0) return ""
        val bodyStart = html.indexOf('>', openTag) + 1
        if (bodyStart == 0) return ""
        var depth = 1
        var i = bodyStart
        while (i < html.length && depth > 0) {
            val open = html.indexOf("<div", i)
            val close = html.indexOf("</div", i)
            when {
                close < 0 -> return ""
                open in 0 until close -> { depth++; i = open + 4 }
                else -> { depth--; i = close + 5 }
            }
        }
        val body = html.substring(bodyStart, i - 5)
        return decodeEntities(stripTags(BR_TAG.replace(body, "\n"))).replace("&nbsp;", " ").trim()
    }

    /** One `<article>` listing card: url, cover, title, author. */
    private data class ListingCard(val path: String, val cover: String?, val title: String, val author: String)

    private fun listingCards(html: String): List<ListingCard> {
        val starts = ARTICLE_CARD.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val cards = mutableListOf<ListingCard>()
        for (i in starts.indices) {
            val block = html.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] else html.length)
            val path = BOOK_LINK.find(block)?.groupValues?.get(1) ?: continue
            val title = TITLE_BLOCK.find(block)?.groupValues?.get(1)
                ?.let(::decodeEntities)?.trim().orEmpty()
            val author = AUTHOR_BLOCK.find(block)?.groupValues?.get(1)
                ?.let(::decodeEntities)?.trim().orEmpty()
            val cover = IMG_SRC.find(block)?.groupValues?.get(1)?.let(::absoluteUrl)
            cards += ListingCard(path, cover, title, author)
        }
        return cards
    }

    private fun listingBooks(html: String): List<SourceBook> =
        listingCards(html).mapNotNull { card ->
            // A card with no readable text is junk — dropped, never a slug row.
            if (card.title.isBlank()) return@mapNotNull null
            SourceBook(
                title = card.title,
                author = card.author,
                url = absoluteUrl(card.path),
                coverImageUrl = card.cover,
                sourceId = sourceId
            )
        }

    private fun absoluteUrl(url: String): String =
        when {
            url.startsWith("http") -> url
            url.startsWith("/") -> SITE_ORIGIN + url
            else -> url
        }

    /** A `"name":"value"` string field in plain JSON, escapes decoded once. */
    private fun quotedField(json: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(json)?.groupValues?.get(1)?.let(::jsonUnescape)

    /** The `"outer":{"…,"inner":"x"}` object field — the inner value. */
    private fun quotedObjectField(json: String, outer: String, inner: String): String? {
        val open = Regex(""""$outer"\s*:\s*\{""").find(json) ?: return null
        val obj = balancedContent(json, open.range.last, '{', '}') ?: return null
        return quotedField(obj, inner)
    }

    /**
     * Decodes ONE layer of JSON escaping over a server-component window:
     * `\"` → `"`, `\\` → `\`, `\/` → `/`; any other `\x` pair survives for
     * the per-value [jsonUnescape] pass (`\uXXXX` stays). Plain left-to-right
     * pair consumption — never a naive global replace, which would corrupt
     * `\\\"` (an escaped quote inside a string).
     */
    private fun jsonUnescapeWindow(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    '/' -> { out.append('/'); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Unescapes a JSON string value (`\"`, `\\`, `\/`, `\uXXXX`, `\n\t\r`). */
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
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
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

    private companion object {
        const val SITE_ORIGIN = "https://chytaylo.com.ua"
        const val AUDIOBOOKS_URL = "$SITE_ORIGIN/audiobooks"

        /**
         * The escaped `\"tracks\":[` key of the server-component payload —
         * the bytes as the live page renders them (one escape layer).
         */
        const val ESCAPED_TRACKS_KEY = "\\\"tracks\\\":["

        /** The fully decoded key the plain-JSON scan needs. */
        const val PLAIN_TRACKS_KEY = "\"tracks\":["

        /** Player-props fallback keys (used when JSON-LD is absent). */
        const val BOOK_TITLE_KEY = "bookTitle"
        const val COVER_URL_KEY = "coverUrl"

        /** The JSON-LD script opener the Book block sits behind. */
        const val LD_JSON_MARKER = """<script type="application/ld+json">"""

        /** The Book `@type` claim inside a JSON-LD object. */
        val LD_BOOK_TYPE = Regex(""""@type"\s*:\s*"Book"""")

        /** The listing's annotation heading («Про що книга»). */
        const val DESCRIPTION_HEADING = "Про що книга"

        /** One listing card: `<article class="group min-w-0">`. */
        val ARTICLE_CARD = Regex("""<article class="group min-w-0">""")

        /** The book link inside a card (cover anchor and text anchor alike). */
        val BOOK_LINK = Regex("""href="(/books/[^"]+)"""")

        /** The text anchor's title div, and the author div that follows it. */
        val TITLE_BLOCK = Regex("""class="line-clamp-2[^"]*"[^>]*>([^<]+)</div>""")
        val AUTHOR_BLOCK = Regex("""class="mt-1 line-clamp-2[^"]*"[^>]*>([^<]+)</div>""")

        /** The cover image of a card block (the first `<img>` in the block). */
        val IMG_SRC = Regex("""<img\s[^>]*?src="([^"]+)"""")

        val BR_TAG = Regex("""<br\s*/?>""")
    }
}
