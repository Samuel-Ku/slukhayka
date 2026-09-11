package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.LanguageCode

/**
 * chitaka.com.ua [SourceAdapter] (spec-50 T3; endpoints verified live in the
 * T1 spike — `docs/wayfinder/research/sources-wave-50-spike.md`, fixtures in
 * `research/fixtures/chitaka/`).
 *
 * WordPress site, server-fetch, no WebView. Two mechanics:
 * - **Listing** (`/audioknyhy/`): `book-image` + `recomend-book-title`
 *   anchors (lazyload covers ride `data-src`, the `src` is a placeholder
 *   SVG). Titles only — cards name no author, and none is invented.
 * - **Book page** (`/knigi/<slug>/`): native `<audio class="lib_book_audio">`
 *   with a direct same-host mp3 in `<source src>` (T1: `Range` → 206).
 *   Books are single-file — one track carries the book's own title.
 *
 * **Content boundary (spec-50, fixed in CONTEXT.md): AUDIO ONLY.** Text
 * books share the `/knigi/` path but render no `<audio>` tag — a page
 * without it parses with empty chapters (the import doors treat that as
 * nothing playable). fb2/epub/txt/online reading never enter the catalogue.
 *
 * Measured absences (ADR-0014 — never fabricated):
 * - **Search:** query-string URLs are robots-discouraged (`Disallow: *?*`)
 *   → [search] is an honest empty list; discovery rides [fetchNew] and
 *   the union.
 * - **Author on cards:** the listing names none — the field stays empty.
 * - **Narrator, duration:** pages name none — empty, never guessed.
 */
class ChitakaAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "chitaka"

    /** The catalogue speaks Ukrainian. */
    override val contentLanguage = LanguageCode.UKRAINIAN

    /**
     * No server-side search through query strings (robots `Disallow: *?*`)
     * — the honest refusal the seam contract allows; unified search covers
     * the source through the catalogue union.
     */
    override suspend fun search(query: String): List<SourceBook> = emptyList()

    /** Page 1 of `/audioknyhy/` in the site's own order. */
    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        if (limit <= 0) return emptyList()
        val html = fetcher.getText(NEW_URL, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS)
        if (html.isEmpty()) return emptyList()
        return listingBooks(html).take(limit)
    }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url, emptyMap(), SourceRequestClass.LISTENER_ACTION, 0L)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())
        val (title, author) = titleAndAuthorFrom(html)
        val cover = ogMeta(html, "og:image")
        val audio = audioUrlFrom(html)
            ?: return SourceBookDetail(
                title = title,
                author = author,
                url = url,
                coverImageUrl = cover,
                chapters = emptyList()
            )
        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            coverImageUrl = cover,
            chapters = listOf(SourceChapter(title = title, streamUrl = audio)),
            // Measured absence: single-file books carry no per-book duration.
            totalDurationSeconds = null
        )
    }

    /** `/knigi/<slug>/` URLs; `?`-suffixed ones keep working. */
    override fun bookId(url: String): String {
        val slug = url.substringBefore('?').removeSuffix("/").substringAfterLast('/')
            .ifBlank { "book" }
        return "$sourceId-$slug"
    }

    // --- parsing helpers -----------------------------------------------------

    /** One listing card: url, cover (lazyload `data-src`), title. */
    private data class Card(val url: String, val cover: String?, val title: String)

    private fun listingCards(html: String): List<Card> {
        val elements = CARD_ELEMENT.findAll(html).toList()
        if (elements.isEmpty()) return emptyList()
        // The cover anchor and the title anchor share the href; group whole
        // anchor ELEMENTS by URL in document order (cover first, title
        // after) — the opening tag alone carries neither the title nor the
        // lazyload cover.
        val byUrl = LinkedHashMap<String, MutableList<String>>()
        val covers = HashMap<String, String>()
        for (element in elements) {
            // Book cards only: cover (`book-image`) and title
            // (`recomend-book-title`) anchors — nav/menu anchors never enter.
            if (!CARD_CLASS.containsMatchIn(element.value.substringBefore('>'))) continue
            val url = element.groupValues[1]
            val inner = element.groupValues[2]
            val titleAttr = TITLE_ATTR.find(element.value)?.groupValues?.get(1)
                ?.let(::decodeEntities)?.trim().orEmpty()
            val anchorText = decodeEntities(stripTags(inner)).trim()
            val title = titleAttr.ifBlank { anchorText }
            if (title.isNotBlank()) byUrl.getOrPut(url) { mutableListOf() } += title
            IMG_DATA_SRC.find(inner)?.groupValues?.get(1)?.let { covers.putIfAbsent(url, it) }
        }
        return byUrl.mapNotNull { (url, titles) ->
            val title = titles.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            Card(url, covers[url], title)
        }
    }

    private fun listingBooks(html: String): List<SourceBook> =
        listingCards(html).map { card ->
            SourceBook(
                title = card.title,
                // Measured absence: cards name no author — empty, never split-guessed.
                author = "",
                url = card.url,
                coverImageUrl = card.cover,
                sourceId = sourceId
            )
        }

    /**
     * `«Title» Author ⭐️ …` (og:title preferred, `<title>` fallback) →
     * (`«Title»`, `Author`). The store suffix (`⭐️ fb2, …`) is cut — the
     * author is what precedes it. No `»` → the whole text is the title.
     */
    private fun titleAndAuthorFrom(html: String): Pair<String, String> {
        val raw = (ogMeta(html, "og:title") ?: titleTag(html)).trim()
        val close = raw.indexOf('»')
        if (close < 0) return raw to ""
        val author = raw.substring(close + 1).substringBefore('⭐').trim()
        return raw.substring(0, close + 1).trim() to author
    }

    private fun titleTag(html: String): String =
        Regex("""<title>([^<]*)""").find(html)?.groupValues?.get(1)
            ?.let(::decodeEntities)?.trim().orEmpty()

    /**
     * The native audio tag's mp3; absent → not an audiobook page (the
     * audio-only boundary — text books share the path but render no audio).
     */
    private fun audioUrlFrom(html: String): String? {
        val audio = AUDIO_TAG.find(html)?.value ?: return null
        return MP3_SRC.find(audio)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val SITE_ORIGIN = "https://chitaka.com.ua"
        const val NEW_URL = "$SITE_ORIGIN/audioknyhy/"

        /** Whole anchor elements (cover and title anchors share the href). */
        val CARD_ELEMENT = Regex("""<a[^>]*?href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL))

        /** Book-card anchor classes; nav/menu anchors never enter. */
        val CARD_CLASS = Regex("""class="[^"]*(?:book-image|recomend-book-title)[^"]*"""")

        /** The human title (`title` attr or anchor text are the same string). */
        val TITLE_ATTR = Regex("""title="([^"]+)"""")

        /** Lazyload covers ride `data-src` (the `src` is a placeholder SVG). */
        val IMG_DATA_SRC = Regex("""data-src="([^"]+)"""")

        /** The native audio tag (DOTALL: `<source>` sits on the next line). */
        val AUDIO_TAG = Regex("""<audio.*?</audio>""", setOf(RegexOption.DOT_MATCHES_ALL))

        /** The direct mp3 inside the audio tag. */
        val MP3_SRC = Regex("""<source\s[^>]*?src="([^"]+\.mp3[^"]*)"""")
    }
}
