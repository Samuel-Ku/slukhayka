package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.LanguageCode

/**
 * knigi-online.com.ua [SourceAdapter] (spec-50 T2; endpoints verified live
 * in the T1 spike — `docs/wayfinder/research/sources-wave-50-spike.md`,
 * fixtures in `research/fixtures/knigionline/`).
 *
 * WordPress site, server-fetch, no WebView. Three mechanics:
 * - **Search** (`/?s=<query>`): server-rendered `post-card` results; the
 *   anchor text reads `«Title» Author`. Mixed site — only `/audioknyha-/`
 *   URLs are audio claims; ebook cards never enter.
 * - **New** (`/audioknyhy/`): the same post-cards in the site's own order.
 * - **Book page**: og:* metadata (title `Аудіокнига «Title» Author`,
 *   description, image) + the AudioIgniter `data-tracks-url` block; the
 *   playlist is plain JSON (`[{title, subtitle, audio, cover}]`) with
 *   direct same-host mp3s (T1: `Range` → 206 `audio/mpeg`).
 *
 * Measured absences (ADR-0014 — never fabricated):
 * - **Narrator:** pages name none — the field stays empty, never guessed.
 * - **Duration:** pages carry none; track titles («1», «2», …) are kept as
 *   rendered.
 */
class KnigiOnlineAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "knigionline"

    /** The catalogue speaks Ukrainian (`og:locale: uk_UA`). */
    override val contentLanguage = LanguageCode.UKRAINIAN

    /** Server-side WordPress search, audiobook cards only. */
    override suspend fun search(query: String): List<SourceBook> {
        if (query.isBlank()) return emptyList()
        val html = fetcher.getText("$SITE_ORIGIN/?s=${urlEncode(query)}")
        if (html.isEmpty()) return emptyList()
        return listingBooks(html)
    }

    /** Page 1 of `/audioknyhy/` in the site's own order. */
    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val html = fetcher.getText(NEW_URL)
        if (html.isEmpty()) return emptyList()
        return listingBooks(html).take(limit)
    }

    /**
     * The sitemap carries URLs only, so the sample is filled with real page
     * parses up to [limit] — every card keeps a real title/author pair,
     * never a slug (precedent: AudiobookCoUaAdapter.fetchCatalog). Only
     * `/audioknyha-/` locs are audio claims.
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val books = mutableListOf<SourceBook>()
        for (loc in sitemapLocs(fetcher.getText(SITEMAP_URL))) {
            if (books.size >= limit) return books
            if (AUDIO_PATH !in loc) continue
            val page = fetcher.getText(loc)
            if (page.isEmpty()) continue
            cardFromPage(page, loc)?.let { books += it }
        }
        return books
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url)
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
        val tracks = playlistTracks(fetcher.getText(playlistUrl))
        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            coverImageUrl = cover,
            chapters = tracks.map { track ->
                SourceChapter(title = track.title, streamUrl = track.url)
            },
            // Measured absence: the page renders no per-book duration.
            totalDurationSeconds = null,
            description = ogMeta(html, "og:description").orEmpty()
        )
    }

    /** `/audioknyha-<slug>-<author>/` URLs; `?`-suffixed ones keep working. */
    override fun bookId(url: String): String {
        val slug = url.substringBefore('?').removeSuffix("/").substringAfterLast('/')
            .ifBlank { "book" }
        return "$sourceId-$slug"
    }

    // --- parsing helpers -----------------------------------------------------

    /** One `post-card` block: url, cover, `«Title» Author` anchor text. */
    private data class Card(val url: String, val cover: String?, val title: String, val author: String)

    private fun listingCards(html: String): List<Card> {
        val starts = CARD.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val cards = mutableListOf<Card>()
        for (i in starts.indices) {
            val block = html.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] else html.length)
            val url = CARD_LINK.find(block)?.groupValues?.get(1) ?: continue
            if (AUDIO_PATH !in url) continue
            val anchor = CARD_TITLE.find(block)?.groupValues?.get(1)
                ?.let(::decodeEntities)?.trim().orEmpty()
            val (title, author) = splitTitleAuthor(anchor)
            if (title.isBlank()) continue
            val cover = CARD_IMG.find(block)?.groupValues?.get(1)
            cards += Card(url, cover, title, author)
        }
        return cards
    }

    private fun listingBooks(html: String): List<SourceBook> =
        listingCards(html).map { card ->
            SourceBook(
                title = card.title,
                author = card.author,
                url = card.url,
                coverImageUrl = card.cover,
                sourceId = sourceId
            )
        }

    /** A catalog card from a real book page (og title + image). */
    private fun cardFromPage(html: String, url: String): SourceBook? {
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

    /**
     * `Аудіокнига «Title» Author` (og:title) or bare `«Title» Author`
     * (card anchors) → (`«Title»`, `Author`). No `»` → the whole text is
     * the title, the author stays empty — never split-guessed.
     */
    private fun titleAndAuthorFrom(html: String): Pair<String, String> {
        val raw = (ogMeta(html, "og:title") ?: titleTag(html))
            .removePrefix("Аудіокнига ").trim()
        return splitTitleAuthor(raw)
    }

    private fun splitTitleAuthor(anchor: String): Pair<String, String> {
        val close = anchor.indexOf('»')
        if (close < 0) return anchor.trim() to ""
        return anchor.substring(0, close + 1).trim() to anchor.substring(close + 1).trim()
    }

    private fun titleTag(html: String): String =
        Regex("""<title>([^<]*)""").find(html)?.groupValues?.get(1)
            ?.let(::decodeEntities)?.trim().orEmpty()

    /** The AudioIgniter `data-tracks-url` block; absent → not a playable page. */
    private fun playlistUrlFrom(html: String): String? =
        TRACKS_URL.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** One AudioIgniter playlist track: `{title, url}` (cover rides the page). */
    private data class PlaylistTrack(val title: String, val url: String)

    /** The plain-JSON playlist; broken JSON → empty, never a throw. */
    private fun playlistTracks(json: String): List<PlaylistTrack> {
        if (json.isBlank()) return emptyList()
        val arrayStart = json.indexOf('[')
        val array = balancedBounds(json, arrayStart) ?: return emptyList()
        println("DEBUG504 arrayLen=${array.length}")
        val tracks = mutableListOf<PlaylistTrack>()
        var pos = 0
        while (true) {
            val objStart = array.indexOf('{', pos)
            if (objStart < 0) break
            val obj = balancedBounds(array, objStart) ?: break
            // AudioIgniter schema names the stream "audio" (verified live).
            val url = quotedField(obj, "audio")
            val title = quotedField(obj, "title").orEmpty().trim()
            if (!url.isNullOrBlank() && title.isNotBlank()) {
                tracks += PlaylistTrack(title, url)
            }
            pos = objStart + obj.length
        }
        return tracks
    }

    /** The `<loc>` URLs of a sitemap document. */
    private fun sitemapLocs(xml: String): List<String> =
        LOC.findAll(xml).map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }.toList()

    /** A `"name":"value"` string field in plain JSON. */
    private fun quotedField(json: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(json)?.groupValues?.get(1)

    /**
     * The substring from [start] (an opening bracket) through its match,
     * respecting JSON strings and escapes; null when unbalanced. Kept local
     * per the adapter-owns-its-parsing precedent (ChytayloAdapter and
     * AudiobookCoUaAdapter carry their own copies), so a markup change in
     * one source fails only that source's fixture tests.
     */
    private fun balancedBounds(s: String, start: Int): String? {
        if (start < 0 || start >= s.length) return null
        val open = s[start]
        val close = when (open) {
            '[' -> ']'
            '{' -> '}'
            else -> return null
        }
        var depth = 0
        var inString = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
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

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val SITE_ORIGIN = "https://knigi-online.com.ua"
        const val NEW_URL = "$SITE_ORIGIN/audioknyhy/"
        const val SITEMAP_URL = "$SITE_ORIGIN/post-sitemap.xml"

        /** Only `/audioknyha-/` URLs are audio claims (mixed site). */
        const val AUDIO_PATH = "/audioknyha-"

        /** One search/catalog card block. */
        val CARD = Regex("""<div class="post-card post-card--vertical""")

        /** The card's book link. */
        val CARD_LINK = Regex("""<a href="([^"]+)"""")

        /** The card title anchor (`«Title» Author`). */
        val CARD_TITLE = Regex("""<div class="post-card__title"[^>]*><span[^>]*><a[^>]*>([^<]+)</a>""")

        /** The card cover (first `<img>` of the block). */
        val CARD_IMG = Regex("""<img[^>]*?src="([^"]+)"""")

        /** The AudioIgniter tracks endpoint of a book page. */
        val TRACKS_URL = Regex("""data-tracks-url="([^"]+)"""")

        /** A sitemap location. */
        val LOC = Regex("""<loc>([^<]+)</loc>""")
    }
}
