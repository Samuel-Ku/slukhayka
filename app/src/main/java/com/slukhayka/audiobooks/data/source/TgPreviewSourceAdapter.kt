package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.ingest.TitleNormalizer

/**
 * ADR-0035 / #606 — the Telegram public-preview adapter, over the
 * captured-page seam ([SourceAdapter.parseCapturedPage], ADR-0006): it
 * parses `t.me/s/<channel>` / `t.me/<channel>/<id>?embed=1` preview HTML and
 * yields the Work identity + metadata claims of ONE post.
 *
 * The RED prototype verdict (`docs/phone-test/tg-preview-playback-verdict.md`):
 * the public preview carries NO audio element, NO stream URL — only the post
 * text (the Channel Post Pattern, T1), a cover photo and a private-invite
 * «Слухати» button. So [parseCapturedPage] ALWAYS returns EMPTY chapters —
 * the honest «Джерело недоступне» state (ADR-0019) — and a TG submission is
 * published as METADATA-ONLY (ADR-0035 п. 13). A future GREEN verdict flips
 * only this adapter (emit chapters from the post's audio URLs); the seams
 * and the publication shape stay.
 *
 * Not a browsable catalog source: search/new/catalog are honestly empty
 * (spec-601: «YouTube і TG як оглядаємі каталоги — out of scope»).
 */
class TgPreviewSourceAdapter : SourceAdapter {

    override val sourceId: String = SourceIds.TELEGRAM

    override val contentLanguage: String get() = "uk"

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchBookPage(url: String): SourceBookDetail =
        // The submission path passes CAPTURED html through parseCapturedPage;
        // a live page fetch is not a browse surface (no catalog books here).
        SourceBookDetail("", "", url = url, chapters = emptyList())

    override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()

    /**
     * Parses one post from a public preview page. Selection is honest:
     *  - the URL carries a post id → the block whose `data-post` matches it
     *    (never a different post — an old post absent from the newest-10 page
     *    is a miss, null);
     *  - the URL carries no post id (a channel link) → the FIRST block;
     *  - a non-preview page (no message widgets) → null («not mine»).
     */
    override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? {
        if (url.isBlank() || !isTgPreviewUrl(url)) return null
        if (!html.contains(MESSAGE_TEXT_MARK)) return null
        val channel = channelFrom(url) ?: return null
        val postId = postIdFrom(url)
        val block = if (postId != null) {
            widgetBlockFor(html, "$channel/$postId")
        } else {
            firstWidgetBlock(html)
        } ?: return null
        return parseBlock(block, channel, postId ?: postIdFromBlock(block) ?: return null)
    }

    private fun parseBlock(block: String, channel: String, postId: String): SourceBookDetail {
        val text = messageText(block) ?: ""
        val identity = TitleNormalizer.parse(text, "@$channel")
        val postUrl = "https://t.me/$channel/$postId"
        return SourceBookDetail(
            title = identity.title.ifBlank { text.lineSequence().firstOrNull().orEmpty().trim() },
            author = identity.author.orEmpty(),
            narrator = identity.narrator.orEmpty(),
            url = postUrl,
            coverImageUrl = coverFrom(block),
            language = contentLanguage,
            // The RED verdict: the preview exposes no audio — never a
            // fabricated stream (ADR-0019 / spec-601 AC3).
            chapters = emptyList(),
            totalDurationSeconds = null,
            description = text.ifBlank { "" }
        )
    }

    // --- widget selection -------------------------------------------------

    private fun widgetBlockFor(html: String, dataPost: String): String? {
        val index = html.indexOf("data-post=\"$dataPost\"")
        if (index < 0) return null
        return blockAround(html, index)
    }

    private fun firstWidgetBlock(html: String): String? {
        val index = html.indexOf(WIDGET_WRAP_OPEN)
        if (index < 0) return null
        return blockAround(html, index)
    }

    /** The widget block enclosing [index]: from its wrap-open to the next wrap-open. */
    private fun blockAround(html: String, index: Int): String? {
        val start = html.lastIndexOf(WIDGET_WRAP_OPEN, index)
        if (start < 0) return null
        val next = html.indexOf(WIDGET_WRAP_OPEN, index + 1)
        return if (next < 0) html.substring(start) else html.substring(start, next)
    }

    // --- field extraction --------------------------------------------------

    private fun messageText(block: String): String? {
        val match = MESSAGE_TEXT.find(block) ?: return null
        val inner = match.groupValues[1]
        // <br>/<br/> are the post line breaks — the Channel Post Pattern's
        // line grammar needs them BEFORE tag stripping.
        val withBreaks = inner.replace(BREAK, "\n")
        val plain = withBreaks.replace(ANY_TAG, "")
        return decodeBasicEntities(plain).trim().takeIf { it.isNotEmpty() }
    }

    private fun coverFrom(block: String): String? =
        COVER_URL.find(block)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

    // --- URL parsing --------------------------------------------------------

    private fun isTgPreviewUrl(url: String): Boolean =
        TG_PREVIEW_URL.matches(url) || TG_EMBED_URL.matches(url) || TG_CHANNEL_URL.matches(url)

    private fun channelFrom(url: String): String? =
        (TG_PREVIEW_URL.matchEntire(url) ?: TG_EMBED_URL.matchEntire(url) ?: TG_CHANNEL_URL.matchEntire(url))
            ?.groupValues?.get(1)

    private fun postIdFrom(url: String): String? =
        (TG_PREVIEW_URL.matchEntire(url) ?: TG_EMBED_URL.matchEntire(url) ?: TG_CHANNEL_URL.matchEntire(url))
            ?.groupValues?.get(2)
            ?.takeIf { it.isNotEmpty() }

    private fun postIdFromBlock(block: String): String? =
        DATA_POST.find(block)?.groupValues?.get(2)

    /** The few entities the preview text carries (numeric + the named basics). */
    private fun decodeBasicEntities(text: String): String =
        text
            .replace(NUMERIC_ENTITY) { match ->
                val code = match.groupValues[1].toIntOrNull() ?: return@replace ""
                code.toChar().toString()
            }
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")

    private companion object {
        const val MESSAGE_TEXT_MARK = "js-message_text"
        const val WIDGET_WRAP_OPEN = "<div class=\"tgme_widget_message_wrap"

        /** `t.me/<channel>/<post>` — the canonical post link. */
        private val TG_CHANNEL_URL = Regex("""https?://t\.me/([A-Za-z0-9_]{3,32})(?:/(\d+))?(?:\?.*)?""")
        /** `t.me/s/<channel>(/<post>)` — the public preview page. */
        private val TG_PREVIEW_URL = Regex("""https?://t\.me/s/([A-Za-z0-9_]{3,32})(?:/(\d+))?(?:\?.*)?""")
        /** `t.me/<channel>/<post>?embed=1` — the single-post embed view. */
        private val TG_EMBED_URL = Regex("""https?://t\.me/([A-Za-z0-9_]{3,32})/(\d+)\?embed=1""")

        private val DATA_POST = Regex("""data-post="([A-Za-z0-9_]+)/(\d+)"""")
        // The message text div carries no nested divs — the FIRST </div> closes it.
        private val MESSAGE_TEXT = Regex("""class="tgme_widget_message_text js-message_text"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val COVER_URL = Regex("""tgme_widget_message_photo_wrap[^>]*background-image:url\('([^']+)'\)""")

        private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val ANY_TAG = Regex("""<[^>]+>""")
        private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
    }
}