package com.example.data.source

import java.net.URLEncoder

/**
 * sluhay.com.ua [SourceAdapter] (spec-11 T2; endpoints verified live in the
 * T1 spike — `docs/wayfinder/research/sluhayua-spike.md`).
 *
 * Three mechanics, all server-fetch, no WebView:
 * - **Search / «new» feed:** `GET /find/allcards?search=<q>&page=1` (newest
 *   first with `sort=time&order=desc`) returns a JSON array of cards carrying
 *   the real Cyrillic title (`bookName`), author (`bookAuthor`), narrator
 *   (`audioAuthor`), cover (`kindSrc`) — everything the Work-level MergeKey
 *   needs, no extra page fetches.
 * - **Book page:** the chapter list is inline (`var playlist = [["0",0],…]`,
 *   length = chapter count) plus real metadata in og:title/og:description
 *   («Автор - Назва. Слухай аудіокнигу онлайн», «…, читає Диктор.»).
 * - **Audio per chapter:** `GET /play?bookId=<id>&fileId=<n>` returns the bare
 *   mp3 URL on the `mp3.sluhay.com.ua` CDN; `0`/`404` means the file does not
 *   exist — the loop stops.
 *
 * Both JSON endpoints are gated on `X-Requested-With: XMLHttpRequest` (a plain
 * GET 404s) — the only gate; no cookies or CSRF are required (verified with a
 * wrong/absent token). Kept pure JVM (regex JSON parsing, no org.json stubs)
 * like the other adapters.
 */
class SluhayuaAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "sluhayua"

    override suspend fun search(query: String): List<SourceBook> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        return cardsFrom(fetcher.getText(allCardsUrl("search=${urlEncode(cleanQuery)}"), XHR))
            .map { it.toSourceBook() }
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> =
        cardsFrom(fetcher.getText(allCardsUrl("sort=time&order=desc"), XHR))
            .take(limit)
            .map { it.toSourceBook() }

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(encodedPageUrl(url))
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())

        val bookId = url.substringAfterLast('/').substringBefore(':')
        val chapterCount = playlistCount(html)
        if (bookId.isEmpty() || !bookId.all { it.isDigit() } || chapterCount <= 0) {
            return SourceBookDetail(
                title = titleFromPage(html),
                author = authorFromPage(html),
                narrator = narratorFromPage(html),
                url = url,
                chapters = emptyList(),
                description = ogMeta(html, "og:description")?.trim().orEmpty()
            )
        }

        val chapters = mutableListOf<SourceChapter>()
        for (fileId in 0 until chapterCount) {
            val stream = fetcher.getText("https://sluhay.com.ua/play?bookId=$bookId&fileId=$fileId", XHR).trim()
            if (stream.isEmpty() || stream == "0" || stream == "404" || !stream.startsWith("http")) break
            chapters += SourceChapter(title = "Глава ${chapters.size + 1}", streamUrl = stream)
        }

        return SourceBookDetail(
            title = titleFromPage(html),
            author = authorFromPage(html),
            narrator = narratorFromPage(html),
            url = url,
            coverImageUrl = ogMeta(html, "og:image")
                ?.replace("//uploads", "/uploads"),
            chapters = chapters,
            description = ogMeta(html, "og:description")?.trim().orEmpty()
        )
    }

    // --- parsing helpers -----------------------------------------------------

    /** `_id` / `slug` / metadata of one `/find/allcards` card. */
    private data class SluhayuaCard(
        val id: String,
        val slug: String,
        val title: String,
        val author: String,
        val narrator: String,
        val cover: String
    )

    private fun cardsFrom(json: String): List<SluhayuaCard> {
        if (json.isBlank()) return emptyList()
        val cardsKey = json.indexOf("\"cards\"")
        if (cardsKey < 0) return emptyList()
        val arrayStart = json.indexOf('[', cardsKey)
        if (arrayStart < 0) return emptyList()
        val arrayBody = balancedContent(json, arrayStart, '[', ']') ?: return emptyList()
        val cards = mutableListOf<SluhayuaCard>()
        var pos = 0
        while (pos < arrayBody.length) {
            val objStart = arrayBody.indexOf('{', pos)
            if (objStart < 0) break
            val obj = balancedContent(arrayBody, objStart, '{', '}') ?: break
            cards += parseCard(obj)
            pos = objStart + obj.length
        }
        return cards
    }

    private fun parseCard(obj: String): SluhayuaCard {
        val id = numberField(obj, "_id").orEmpty()
        val slug = quotedField(obj, "slug").orEmpty()
        val title = quotedField(obj, "bookName")
            ?.takeIf { it.isNotBlank() }
            ?: quotedField(obj, "title")?.substringBefore(" - ")?.trim().orEmpty()
        val author = firstArrayElement(obj, "bookAuthor").orEmpty()
        val narrator = firstArrayElement(obj, "audioAuthor").orEmpty()
        val cover = quotedField(obj, "kindSrc").orEmpty()
        return SluhayuaCard(id, slug, title, author, narrator, cover)
    }

    private fun SluhayuaCard.toSourceBook(): SourceBook = SourceBook(
        title = title,
        author = author,
        narrator = narrator,
        url = "https://sluhay.com.ua/$id:$slug",
        coverImageUrl = cover.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://sluhay.com.ua$it"
        },
        sourceId = sourceId
    )

    private fun allCardsUrl(params: String): String =
        "https://sluhay.com.ua/find/allcards?$params&page=1"

    /** The book page URL — the slug may be Cyrillic and needs encoding. */
    private fun encodedPageUrl(url: String): String {
        val base = url.substringAfterLast("sluhay.com.ua/")
        val id = base.substringBefore(':', base)
        val slug = base.substringAfter(':', "")
        return if (slug.isEmpty() || slug.none { it.code > 127 || it == ' ' }) {
            url
        } else {
            "https://sluhay.com.ua/$id:${urlEncode(slug)}"
        }
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Number of files in the page's inline `var playlist = [["0",0],…]`. */
    private fun playlistCount(html: String): Int {
        // Capture up to the semicolon — a `[^\]]*` character class would stop
        // at the first inner `]` of `[["0",0],…]`.
        val playlistBlock = Regex("""var\s+playlist\s*=\s*([^;]+);""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return 0
        return Regex("""\[\s*"\d+"\s*,\s*\d+\s*\]""").findAll(playlistBlock).count()
    }

    private fun titleFromPage(html: String): String {
        val og = ogMeta(html, "og:title") ?: return ""
        val withoutSuffix = og
            .replace(Regex("""\s*\.\s*Слухай аудіокнигу онлайн\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return withoutSuffix.substringAfter(" - ", withoutSuffix).trim()
    }

    private fun authorFromPage(html: String): String {
        val og = ogMeta(html, "og:title") ?: return ""
        val withoutSuffix = og
            .replace(Regex("""\s*\.\s*Слухай аудіокнигу онлайн\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return if (withoutSuffix.contains(" - ")) withoutSuffix.substringBefore(" - ").trim() else ""
    }

    private fun narratorFromPage(html: String): String =
        Regex("""читає\s+([^.,]+)""", RegexOption.IGNORE_CASE)
            .find(ogMeta(html, "og:description").orEmpty())
            ?.groupValues?.get(1)?.trim()
            .orEmpty()

    private fun ogMeta(html: String, property: String): String? =
        Regex("""<meta\s+property="$property"\s+content="([^"]+)"\s*/?>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content="([^"]+)"\s+property="$property"\s*/?>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    /** A `"name": 123` numeric field. */
    private fun numberField(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(obj)?.groupValues?.get(1)

    /** A `"name": "value"` string field with JSON escapes unescaped. */
    private fun quotedField(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)""").find(obj)?.groupValues?.get(1)?.let(::unescape)

    /** The first element of a `"name": ["a", …]` array field. */
    private fun firstArrayElement(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)""").find(obj)?.groupValues?.get(1)?.let(::unescape)?.trim()

    /**
     * Unescapes a JSON string value: `\"`, `\\`, `\/`, `\n`/`\t`/`\r` and
     * `\uXXXX` (the live allcards JSON escapes every non-ASCII char — found
     * on-device during #88: titles rendered as literal `\u041a\u043e…`).
     * Single pass so a literal `\\u` (escaped backslash + `u`) is not
     * double-decoded into a unicode char.
     */
    private fun unescape(s: String): String {
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
     * matching [close] bracket, respecting JSON strings and escapes — so a
     * `{`/`[` inside a description cannot break the split.
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
        val XHR = mapOf("X-Requested-With" to "XMLHttpRequest")
    }
}
