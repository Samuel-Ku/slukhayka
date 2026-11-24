package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import java.net.URI

/**
 * #1487 (slice 2) — the shared page probe for a host NO source claims.
 *
 * The listener's ask: «send a link to a book and it opens it and pulls out the
 * media data». The known-source half is slice 1 (the registry + the adapter
 * seam). This is the other half: one explicit tap fetches ONE public page
 * through the existing transport, and the page is read for what it plainly
 * publishes — title, author, cover, description and playable audio URLs. No
 * JS execution, no cookie, no second request, no server-side conversion.
 *
 * Pure: HTML in, [ProbedPage] out. Every absent field stays absent (null), and
 * a page with no audio is reported as such ([ProbedPage.audios] empty) so the
 * caller can refuse honestly instead of inventing a card.
 */
data class ProbedAudio(val url: String, val title: String? = null)

data class ProbedPage(
    val url: String,
    val title: String?,
    val author: String?,
    val coverImageUrl: String?,
    val description: String?,
    val audios: List<ProbedAudio>
) {
    /**
     * The import materialisation of this probe: the plain facts of the page as
     * a source book page. Chapter titles the markup did not carry fall back to
     * an ordinal — never to a fabricated book fact.
     */
    fun toSourceBookDetail(): SourceBookDetail = SourceBookDetail(
        title = title.orEmpty(),
        author = author.orEmpty(),
        url = url,
        coverImageUrl = coverImageUrl,
        chapters = audios.mapIndexed { index, audio ->
            SourceChapter(
                title = audio.title?.trim()?.takeIf { it.isNotBlank() } ?: "Частина ${index + 1}",
                streamUrl = audio.url
            )
        },
        description = description.orEmpty()
    )
}

object BookPageProbe {

    /** A page bigger than this is not a book page the probe should chew on. */
    const val MAX_HTML_CHARS = 2 * 1024 * 1024

    /** The transport reads at most this many BYTES before the parser sees it. */
    const val MAX_HTML_BYTES = 2 * 1024 * 1024

    private val MEDIA_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus")

    /**
     * #1487 slice 2 — a link that IS an audio file, not a page. It needs no
     * page request at all: the import becomes a one-chapter card whose chapter
     * is the URL itself. Keeps big binary bodies out of the page probe.
     */
    fun isDirectMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val name = path.substringAfterLast('/').lowercase()
        return name.substringAfterLast('.') in MEDIA_EXTENSIONS && name.contains('.')
    }

    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*[\"']([^\"']*)[\"']")
    private val TITLE_TAG = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val SCRIPT_TAG = Regex(
        "<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val AUDIO_TAG = Regex("<audio\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val AUDIO_BLOCK = Regex(
        "<audio\\b[^>]*>(.*?)</audio>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val SOURCE_TAG = Regex("<source\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val JSON_CONTENT_URL = Regex(
        "\"contentUrl\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE
    )
    private val JSON_AUTHOR_OBJECT = Regex(
        "\"author\"\\s*:\\s*\\{[^{}]*?\"name\"\\s*:\\s*\"([^\"]+)\"",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val JSON_AUTHOR_STRING = Regex(
        "\"author\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE
    )
    private val ABSOLUTE_MEDIA_URL = Regex(
        "https?://[^\\s\"'<>]+?\\.(?:mp3|m4a|aac|ogg|opus)(?:\\?[^\\s\"'<>]*)?",
        RegexOption.IGNORE_CASE
    )

    private val TITLE_KEYS = listOf("og:title", "twitter:title")
    private val AUTHOR_KEYS = listOf(
        "book:author", "article:author", "og:article:author", "author", "twitter:creator"
    )
    private val COVER_KEYS = listOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")
    private val DESCRIPTION_KEYS = listOf("og:description", "description")

    /**
     * Parses one public page. Null only for empty/oversized input — a page the
     * probe cannot read is the caller's `PAGE_UNREADABLE`, never a blank card.
     */
    fun parse(html: String, pageUrl: String): ProbedPage? {
        if (html.isBlank() || html.length > MAX_HTML_CHARS) return null
        val metas = metaValues(html)
        val jsonLd = SCRIPT_TAG.findAll(html).joinToString("\n") { it.groupValues[1] }
        val title = firstMeta(metas, TITLE_KEYS)
            ?: TITLE_TAG.find(html)?.groupValues?.get(1)?.let(::cleanText)
            ?: jsonLd.firstString("\"name\"")
        val author = firstMeta(metas, AUTHOR_KEYS)
            ?: JSON_AUTHOR_OBJECT.find(jsonLd)?.groupValues?.get(1)?.let(::cleanText)
            ?: JSON_AUTHOR_STRING.find(jsonLd)?.groupValues?.get(1)?.let(::cleanText)
        return ProbedPage(
            url = pageUrl,
            title = title?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            coverImageUrl = firstMeta(metas, COVER_KEYS)
                ?.let { resolveUrl(pageUrl, it) },
            description = firstMeta(metas, DESCRIPTION_KEYS)?.takeIf { it.isNotBlank() },
            audios = audioUrls(html, jsonLd, pageUrl)
        )
    }

    // --- meta / JSON-LD reads ------------------------------------------------

    private fun metaValues(html: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (tag in META_TAG.findAll(html)) {
            val attrs = attributes(tag.value)
            val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: continue
            val content = attrs["content"]?.let(::decodeEntities)?.trim() ?: continue
            if (content.isNotBlank() && key !in result) result[key] = content
        }
        return result
    }

    private fun attributes(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun firstMeta(metas: Map<String, String>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> metas[key]?.takeIf { it.isNotBlank() } }

    private fun String.firstString(key: String): String? =
        Regex("$key\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)?.let(::cleanText)
            ?.takeIf { it.isNotBlank() }

    // --- audio ---------------------------------------------------------------

    private fun audioUrls(html: String, jsonLd: String, pageUrl: String): List<ProbedAudio> {
        val seen = linkedMapOf<String, ProbedAudio>()
        fun add(raw: String, title: String?) {
            val resolved = resolveUrl(pageUrl, raw) ?: return
            if (resolved in seen) return
            seen[resolved] = ProbedAudio(resolved, title?.trim()?.takeIf { it.isNotBlank() })
        }
        // 1. The page's own AUDIO elements (the strongest signal: an ordered
        // list). `<source>` is read only INSIDE an `<audio>` block — a
        // `<video><source>` is a video, not an audiobook chapter.
        for (block in AUDIO_BLOCK.findAll(html)) {
            val opening = AUDIO_TAG.find(block.value)?.value ?: continue
            val attrs = attributes(opening)
            attrs["src"]?.let { add(it, attrs["title"] ?: attrs["data-title"] ?: attrs["aria-label"]) }
            for (source in SOURCE_TAG.findAll(block.groupValues[1])) {
                val sourceAttrs = attributes(source.value)
                sourceAttrs["src"]?.let { add(it, sourceAttrs["title"] ?: attrs["title"]) }
            }
        }
        // A self-closing `<audio src=…>` without a closing tag.
        for (tag in AUDIO_TAG.findAll(html)) {
            val attrs = attributes(tag.value)
            attrs["src"]?.let { add(it, attrs["title"] ?: attrs["data-title"] ?: attrs["aria-label"]) }
        }
        // 2. schema.org contentUrl (JSON-LD) — the playlist form.
        for (match in JSON_CONTENT_URL.findAll(jsonLd)) {
            add(match.groupValues[1], null)
        }
        // 3. Bare media URLs in the markup (anchors, inline configs).
        for (match in ABSOLUTE_MEDIA_URL.findAll(html)) {
            add(match.value, null)
        }
        return seen.values.toList()
    }

    // --- text / URL hygiene --------------------------------------------------

    private fun resolveUrl(pageUrl: String, raw: String): String? {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isBlank()) return null
        val lower = value.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("blob:")) {
            return null
        }
        val resolved = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun cleanText(raw: String): String = decodeEntities(raw)
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Minimal HTML entity decoding — enough for attributes, never a parser. */
    private fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        val named = mapOf(
            "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
            "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
            "laquo" to "«", "raquo" to "»", "rsquo" to "’", "lsquo" to "‘",
            "ldquo" to "“", "rdquo" to "”"
        )
        return Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);").replace(value) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.drop(2).toIntOrNull(16)?.let { codePointToString(it) } ?: match.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let { codePointToString(it) } ?: match.value
                else -> named[body.lowercase()] ?: match.value
            }
        }
    }

    private fun codePointToString(codePoint: Int): String? =
        if (codePoint in 1..0x10FFFF) {
            runCatching { String(Character.toChars(codePoint)) }.getOrNull()
        } else {
            null
        }
}
