package com.slukhayka.audiobooks.data.source

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
 *
 * ## True profile completeness (spec-35 T6, inventory #237)
 *
 * Cards carry genre (`genre` array), duration (`totalSeconds` preferred over
 * `timeLength`, both `01:00:00` and `01:44` formats measured); the book page
 * carries the duration («Час запису:» row), the genres («Жанр:» filterLinks),
 * and three related rails («Інші книги автора», «Інші книги виконавця»,
 * «Схожі книги» — self-excluded). Measured negative findings, never
 * fabricated (ADR-0014):
 * - **No series/cycle** on either surface (no «Серія/Цикл/Том» markers; the
 *   allcards key set carries none).
 * - **No book rating on the book page** — the main card shows only
 *   «Автор/Начитано/Жанр/Час запису»; a rating exists in the JSON card and
 *   rail cards but [SourceBookDetail.rating] has no honest source there.
 * - **Card rating and card text** exist in the allcards JSON but have no
 *   field in the [SourceBook] seam — documented, unused (same treatment as
 *   soundbooks T5).
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

        // Spec-35 T6: the page-level profile rows — «Час запису:» (MM:SS or
        // HH:MM:SS), the «Жанр:» filterLink anchors and the three cardRoll
        // rails («Інші книги автора», «Інші книги виконавця», «Схожі книги»).
        val totalDurationSeconds = pageDurationFrom(html)
        val genres = genresFrom(html)
        val related = relatedFrom(html, url)

        val bookId = url.substringAfterLast('/').substringBefore(':')
        val chapterCount = playlistCount(html)
        if (bookId.isEmpty() || !bookId.all { it.isDigit() } || chapterCount <= 0) {
            return SourceBookDetail(
                title = titleFromPage(html),
                author = authorFromPage(html),
                narrator = narratorFromPage(html),
                url = url,
                chapters = emptyList(),
                totalDurationSeconds = totalDurationSeconds,
                genres = genres,
                related = related,
                description = pageDescription(html)
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
            totalDurationSeconds = totalDurationSeconds,
            genres = genres,
            related = related,
            description = pageDescription(html)
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
        val cover: String,
        val genre: String,
        val durationSeconds: Long
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
        // Spec-35 T6: «genre» is a string array («поема»); duration arrives
        // as either totalSeconds (number, preferred) or timeLength
        // («01:00:00» / «01:44» — both formats measured in the inventory).
        val genre = stringArrayElements(obj, "genre").joinToString(", ")
        val durationSeconds = numberField(obj, "totalSeconds")?.toLongOrNull()
            ?: quotedField(obj, "timeLength")?.let(::parseDurationSeconds)
            ?: 0L
        return SluhayuaCard(id, slug, title, author, narrator, cover, genre, durationSeconds)
    }

    private fun SluhayuaCard.toSourceBook(): SourceBook = SourceBook(
        title = title,
        author = author,
        narrator = narrator,
        url = "https://sluhay.com.ua/$id:$slug",
        coverImageUrl = cover.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://sluhay.com.ua$it"
        },
        genre = genre,
        totalDurationSeconds = durationSeconds,
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

    /**
     * #265 — the book annotation: the body's `bookDescription[itemprop=
     * description]` container carries the CLEAN blurb (no «Аудіокнігу
     * онлайн…» prefix the og:description template adds), so it wins when
     * present; og:description stays the fallback for pages without it. The
     * trailing «Автор озвучки:» meta line inside the container is cut — the
     * narrator is already a field of its own. `<br>` becomes a line break,
     * other tags become spaces, entities decode, blank lines drop.
     */
    private fun pageDescription(html: String): String {
        val ogFallback = ogMeta(html, "og:description")?.trim().orEmpty()
        val open = Regex("""<div[^>]*itemprop="description"[^>]*>""", RegexOption.IGNORE_CASE).find(html)
            ?: return ogFallback
        val close = html.indexOf("</div>", open.range.last)
        if (close < 0) return ogFallback
        val text = html.substring(open.range.last + 1, close)
        val cut = Regex("""Автор озвучки\s*:""", RegexOption.IGNORE_CASE).find(text)?.range?.first ?: text.length
        val cleaned = text.substring(0, cut)
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(TAGS, " ")
            .let(::decodeEntities)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return cleaned.ifBlank { ogFallback }
    }

    /**
     * Spec-35 T6 — the book page's full duration from the meta row
     * «Час запису:</span> 01:44» (MM:SS) / «1:02:03» (HH:MM:SS); both go
     * through the shared [parseDurationSeconds]. Absent row → null.
     */
    private fun pageDurationFrom(html: String): Long? =
        Regex("""Час запису:\s*</span>\s*(\d{1,2}:\d{2}(?::\d{2})?)""")
            .find(html)?.groupValues?.get(1)
            ?.let(::parseDurationSeconds)

    /**
     * Spec-35 T6 — genre names from the «Жанр:</span><div class="rowData">»
     * filterLink anchors. Multiple links join in DOM order; absent row →
     * empty.
     */
    private fun genresFrom(html: String): List<String> {
        val block = Regex("""Жанр:</span>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<a[^>]*>([^<]+)</a>""").findAll(block)
            .map { decodeEntities(it.groupValues[1].trim()) }
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * Spec-35 T6 — related books from the cardRoll rails («Інші книги
     * автора», «Інші книги виконавця», «Схожі книги»): each rail is a
     * `cardRollCategoryDescription` header followed by cards whose
     * `titlePreviewText` blurb precedes the `/id:slug` anchor. The anchor is
     * «Автор - Назва»; a missing separator keeps author empty («Анфіса –
     * золоті коси» uses an en-dash). The SELF book appears inside its own
     * author rail and is excluded; covers are not carried by this markup.
     */
    private fun relatedFrom(html: String, selfUrl: String): List<RelatedBook> {
        val selfId = selfUrl.substringAfterLast('/').substringBefore(':')
        var rail: String? = null
        val seen = mutableSetOf<String>()
        val related = mutableListOf<RelatedBook>()
        for (token in RAIL_TOKEN.findAll(html)) {
            val header = token.groupValues[1]
            if (header.isNotEmpty()) {
                rail = decodeEntities(header).trim()
                continue
            }
            if (rail == null) continue
            val href = token.groupValues[2]
            val id = href.substringBefore(':')
            if (id == selfId || !seen.add(href)) continue
            val anchor = decodeEntities(token.groupValues[3]).trim()
            val sep = anchor.indexOf(" - ")
            related += RelatedBook(
                title = if (sep >= 0) anchor.substring(sep + 3).trim() else anchor,
                author = if (sep >= 0) anchor.substring(0, sep).trim() else "",
                url = "https://sluhay.com.ua/$href"
            )
        }
        return related
    }

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

    /** A `"name": 123` numeric field. */
    private fun numberField(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(obj)?.groupValues?.get(1)

    /** A `"name": "value"` string field with JSON escapes unescaped. */
    private fun quotedField(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)""").find(obj)?.groupValues?.get(1)?.let(::unescape)

    /** The first element of a `"name": ["a", …]` array field. */
    private fun firstArrayElement(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)""").find(obj)?.groupValues?.get(1)?.let(::unescape)?.trim()

    /** Every string element of a `"name": ["a", "b", …]` array field. */
    private fun stringArrayElements(obj: String, name: String): List<String> {
        val start = Regex(""""$name"\s*:\s*\[""").find(obj)?.range?.last ?: return emptyList()
        val body = balancedContent(obj, obj.indexOf('[', start), '[', ']') ?: return emptyList()
        // A fully-quoted string literal — both delimiters required, so junk
        // BETWEEN elements (`,`/`]`) can never become an element.
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body)
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

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
        val TAGS = Regex("""<[^>]+>""")

        // Spec-35 T6 — one pass over the page's cardRoll region: a rail
        // header (cardRollCategoryDescription) or a rail card (blurb span
        // followed by the /id:slug anchor), in document order.
        private val RAIL_TOKEN = Regex(
            """cardRollCategoryDescription[^>]*>\s*([^<]+)|titlePreviewText">.*?</span>\s*<a\s+href="/(\d+:[^"]+)"[^>]*>([^<]+)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
