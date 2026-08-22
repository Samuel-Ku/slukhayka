package com.slukhayka.audiobooks.data.catalog

/**
 * Pure-JVM parser for the 4read.org catalogue (no Android dependencies, so it
 * is unit-testable with plain JUnit on HTML fixtures — see
 * `CatalogParserTest`).
 *
 * The homepage is a flat list of "poster" blocks:
 *
 * ```
 * <div class="poster has-overlay ...">
 *   <div class="poster__desc order-last">
 *     <a href="https://4read.org/7611-slug.html" class="poster__link">
 *       <div class="poster__title line-clamp">Title</div></a>
 *     <div class="poster__subtitle ws-nowrap"> Author </div>
 *   </div>
 *   <div class="poster__img ...">
 *     <img src="/uploads/posts/2026-06/medium/cover.webp" ...>
 *     <div class="poster__series anim"><a href="https://4read.org/xfsearch/cikl/.../">Cycle name</a></div>
 *   </div>
 * </div>
 * ```
 *
 * Every poster maps to one [CatalogBook]; posters that carry a `poster__series`
 * link also contribute a [CatalogSeries] entry. The parser therefore yields
 * exactly two sections on the homepage:
 *
 *  - **"Новинки"** — every book poster (recent additions)
 *  - **"Цикли"** — the cycles featured on the homepage, each with the cover of
 *    its featured book
 *
 * A series (cycle) page has the same poster structure, so [parseSeriesPage]
 * reuses the same poster-splitting logic and returns just the books.
 *
 * The parser is defensive by construction: unknown markup degrades to fewer
 * books/sections, never to a crash.
 */
/** One book card shown in a catalogue row. */
data class CatalogBook(
    val id: String,
    val title: String,
    val author: String,
    val url: String,
    val coverImageUrl: String?,
    val seriesTitle: String? = null,
    val seriesUrl: String? = null,
    val seriesIndex: Int? = null,
    /** Real total duration carried by the source page (ТОП 100), 0 when unknown. */
    val totalDurationSeconds: Long = 0L
)

/** One person (narrator/author) from the Виконавці/Автори pages. */
data class CatalogPerson(
    val name: String,
    /** Raw site path, e.g. `/xfsearch/chitaet/Ім'я/` (encoded on fetch). */
    val path: String,
    val bookCount: Int
)

/** One series (cycle) chip shown in the "Цикли" row. */
data class CatalogSeries(
    val title: String,
    val url: String,
    val coverImageUrl: String?
)

/** One genre (category) chip from the homepage sidebar ("Аудіокниги жанру:"). */
data class CatalogGenre(
    val title: String,
    val url: String
)

/**
 * spec-28 (#197) — the stable typed identity of a homepage catalogue
 * section, independent of its display [CatalogSection.title]. The parser
 * assigns it at construction; every downstream consumer (the cross-source
 * «Новинки» rail, the Огляд exactly-once skip) matches on this id, never on
 * the title — so renaming the section in the parser (or on the site) cannot
 * silently duplicate 4read's new arrivals on Огляд.
 */
enum class CatalogSectionId {
    /** «Новинки» — the recent-additions posters feeding the cross-source rail. */
    NEW_ARRIVALS,

    /** «Цикли» — the featured series cycles. */
    SERIES,

    /** «Популярне» — the sidebar most-popular block. */
    POPULAR
}

/** A horizontal row of the Explore screen. */
data class CatalogSection(
    val title: String,
    val books: List<CatalogBook> = emptyList(),
    val series: List<CatalogSeries> = emptyList(),
    /** Stable identity, independent of the display [title] (spec-28 #197). */
    val id: CatalogSectionId
)

object CatalogParser {

    private const val SITE = "https://4read.org"
    private const val SECTION_NEW = "Новинки"
    private const val SECTION_SERIES = "Цикли"

    private val bookUrlRegex = Regex("""https://4read\.org/\d+-[^"'<>]+\.html""")
    private val titleRegex = Regex("""class="poster__title line-clamp">([^<]+)</div>""")
    private val authorRegex = Regex("""class="poster__subtitle ws-nowrap">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val imgRegex = Regex("""<img[^>]+src="([^"]+)"[^>]*>""")
    private val seriesRegex = Regex("""class="poster__series anim"><a href="([^"]+)">([^<]+)</a>""")
    // Volume badge on a poster: the book's number inside its series (cycle).
    private val seriesIndexRegex = Regex("""<div class="poster__label poster__label--blue">(\d+)</div>""")
    private val htmlEntityRegex = Regex("""&#(\d+);""")

    private val htmlEntityNamedRegex = Regex("""&(amp|quot|apos|lt|gt);""")

    /** `/page/N/` links of the DLE pagination block — group 1 = href, group 2 = N. */
    private val paginationHrefRegex = Regex("""href="([^"]*/page/(\d+)/?)"""")

    /** How far past `id="pagination"` the link scan reaches (the block is small). */
    private const val PAGINATION_WINDOW = 4000

    /**
     * Parses the 4read.org homepage into catalogue sections ("Новинки" and
     * "Цикли"). An empty/unparseable page yields an empty list.
     */
    fun parseHomepage(html: String): List<CatalogSection> {
        val books = mutableListOf<CatalogBook>()
        val seriesByUrl = LinkedHashMap<String, CatalogSeries>()

        for (poster in splitPosters(html)) {
            val bookUrl = bookUrlRegex.find(poster)?.value ?: continue
            val title = titleRegex.find(poster)?.groupValues?.get(1)
                ?.let { decodeEntities(it.trim()) }
                ?.takeIf { it.length >= 2 && !isPromoTitle(it) }
                ?: continue

            val author = authorRegex.find(poster)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?: ""

            val cover = toAbsoluteUrl(imgRegex.find(poster)?.groupValues?.get(1))

            val series = seriesRegex.find(poster)
            var seriesTitle: String? = null
            var seriesUrl: String? = null
            if (series != null) {
                seriesUrl = series.groupValues[1]
                seriesTitle = decodeEntities(series.groupValues[2].trim())
                seriesByUrl.putIfAbsent(
                    seriesUrl,
                    CatalogSeries(title = seriesTitle, url = seriesUrl, coverImageUrl = cover)
                )
            }
            val seriesIndex = seriesIndexRegex.find(poster)?.groupValues?.get(1)?.toIntOrNull()

            books.add(
                CatalogBook(
                    id = bookId(bookUrl),
                    title = title,
                    author = author,
                    url = bookUrl,
                    coverImageUrl = cover,
                    seriesTitle = seriesTitle,
                    seriesUrl = seriesUrl,
                    seriesIndex = seriesIndex
                )
            )
        }

        val sections = mutableListOf<CatalogSection>()
        // The typed id is assigned HERE, at the single construction point —
        // not derived from the title downstream — so the section identity
        // survives any rename of the SECTION_NEW/SECTION_SERIES strings
        // (spec-28 #197).
        if (books.isNotEmpty()) {
            sections.add(CatalogSection(SECTION_NEW, books = books, id = CatalogSectionId.NEW_ARRIVALS))
        }
        if (seriesByUrl.isNotEmpty()) {
            sections.add(CatalogSection(SECTION_SERIES, series = seriesByUrl.values.toList(), id = CatalogSectionId.SERIES))
        }
        // Sidebar "Популярне" block — the homepage's most-popular cards.
        val popular = parsePopularBooks(html)
        if (popular.isNotEmpty()) {
            sections.add(CatalogSection("Популярне", books = popular, id = CatalogSectionId.POPULAR))
        }
        return sections
    }

    /**
     * Parses the homepage sidebar "Популярне" block into its books. The block
     * is `<div class="sb__title">…Популярне</div>` followed by an
     * `sb__content sb__grid` of `ftop-item` cards:
     *
     * ```
     * <a class="ftop-item d-flex ai-center has-overlay" href="https://4read.org/7894-….html">
     *   <div class="ftop-item__img img-fit-cover">
     *     <img src="/uploads/…/x.webp" alt="Жан-Крістоф Ґранже - Пасажир">
     *   </div>
     *   <div class="ftop-item__desc flex-grow-1">
     *     <div class="ftop-item__title poster__title line-clamp">Пасажир</div>
     *     <div class="ftop-item__meta poster__subtitle line-clamp">Світова література / Детектив / …</div>
     *     <div class="ftop-item__meta poster__subtitle line-clamp"><span class="fal fa-clock"></span> 24:54:14</div>
     *   </div>
     * </a>
     * ```
     *
     * The card has no author line — the author is recovered from the image's
     * `alt` ("Author - Title"). Duration comes from the clock meta.
     */
    fun parsePopularBooks(html: String): List<CatalogBook> {
        // The title carries an icon span before the word, so match lazily.
        val titleBlock = Regex("""<div class="sb__title"[^>]*>.*?Популярне</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?: return emptyList()
        val gridOpen = """<div class="sb__content sb__grid">"""
        val gridStart = html.indexOf(gridOpen, titleBlock.range.last)
        if (gridStart < 0) return emptyList()
        val gridEnd = gridStart + gridOpen.length
        // The grid closes at the next sidebar block or the aside itself.
        val asideEnd = html.indexOf("</aside>", gridEnd)
        val nextSb = html.indexOf("""<div class="sb """, gridEnd)
        val end = listOfNotNull(asideEnd.takeIf { it >= 0 }, nextSb.takeIf { it >= 0 }).minOrNull() ?: html.length
        val block = html.substring(gridEnd, end)

        val opener = """<a class="ftop-item"""
        val indices = Regex(Regex.escape(opener)).findAll(block).map { it.range.first }.toList()
        if (indices.isEmpty()) return emptyList()
        return indices.mapIndexed { i, start ->
            block.substring(start, indices.getOrNull(i + 1) ?: block.length)
        }.mapNotNull { chunk ->
            val bookUrl = bookUrlRegex.find(chunk)?.value ?: return@mapNotNull null
            val title = Regex("""class="ftop-item__title[^"]*">([^<]+)</div>""").find(chunk)
                ?.groupValues?.get(1)
                ?.let { decodeEntities(it.trim()) }
                ?.takeIf { it.length >= 2 }
                ?: return@mapNotNull null
            val alt = Regex("""<img[^>]+alt="([^"]+)""").find(chunk)?.groupValues?.get(1) ?: ""
            CatalogBook(
                id = bookId(bookUrl),
                title = title,
                author = alt.substringBeforeLast(" - ").trim(),
                url = bookUrl,
                coverImageUrl = toAbsoluteUrl(Regex("""<img[^>]+src="([^"]+)""").find(chunk)?.groupValues?.get(1)),
                totalDurationSeconds = parseInlineDuration(chunk)
            )
        }
    }

    /**
     * Parses the homepage's genre navigation sidebar ("Аудіокниги жанру:")
     * into genre chips. The block is a `<ul class="sb__content sb__nav">` of
     * plain `<li><a href="/kazka/">Казка</a></li>` entries; the trailing
     * "Додати книгу" entry embeds an `<i>` icon, so its anchor text contains
     * markup and is skipped by the `[^<]+` text match. Unknown markup
     * degrades to an empty list, never a crash.
     */
    fun parseGenreNav(html: String): List<CatalogGenre> {
        val nav = Regex("""<ul class="sb__content sb__nav">(.*?)</ul>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        return Regex("""<li>\s*<a href="([^"]+)">([^<]+)</a></li>""")
            .findAll(nav)
            .map { m -> m.groupValues[1] to decodeEntities(m.groupValues[2].trim()) }
            .filter { (href, title) ->
                title.length >= 2 &&
                    // ЗНО is a static works list (no poster grid): tapping it
                    // would land on an empty book list, so it is not a genre.
                    !href.endsWith(".html")
            }
            .map { (href, title) ->
                CatalogGenre(
                    title = title,
                    url = toAbsoluteUrl(href) ?: href
                )
            }
            .toList()
    }

    /**
     * Parses a series (cycle) page — `4read.org/xfsearch/cikl/<slug>/` — into
     * its books. The page reuses the poster markup of the homepage.
     */
    fun parseSeriesPage(html: String): List<CatalogBook> = parsePosterBooks(html)

    /**
     * The next page of a paginated listing (category / series), or null when
     * the page carries no DLE pagination. The block is
     * `<div class="pagination …" id="pagination">` whose links are
     * `/page/N/` hrefs (`…/fentezi/page/2/`); the chevron button repeats the
     * first next link, so picking the LOWEST page number is always «the next
     * one after this page». Scoped to a window after the pagination id so
     * unrelated `/page/` links elsewhere on the page never match. Unknown
     * markup degrades to null — the caller stops paging, never crashes.
     */
    fun parseNextPageUrl(html: String): String? {
        val start = html.indexOf("""id="pagination"""")
        if (start < 0) return null
        val scope = html.substring(start, minOf(html.length, start + PAGINATION_WINDOW))
        return paginationHrefRegex.findAll(scope)
            .map { it.groupValues[2].toIntOrNull() to it.groupValues[1] }
            .filter { (number, _) -> number != null && number >= 2 }
            .minByOrNull { (number, _) -> number!! }
            ?.second
            ?.let { toAbsoluteUrl(it) ?: it }
    }

    /**
     * Parses the "Можливо, Тебе зацікавить:" related-books section of a book
     * page into books. The block is
     * `<section class="sect pmovie__related carou">` whose inner posters are
     * the standard poster markup, so the shared poster parser applies.
     */
    fun parseRelatedBooks(html: String): List<CatalogBook> {
        val section = Regex("""<section class="sect pmovie__related[^"]*"[^>]*>(.*?)</section>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        return parsePosterBooks(section)
    }

    /**
     * Parses the ТОП 100 page (`/top-100.html`) into its ranked books. The
     * page uses `linek` cards (not posters): a cover, a "Title - Author" line
     * and a real "Триває:" duration. Rank is the list order (1-based).
     *
     * ```
     * <div class="linek d-flex ai-center has-overlay card">
     *   <div class="linek__img img-fit-cover"><img src="/uploads/.../x.webp"></div>
     *   <div class="linek__desc flex-grow-1">
     *     <a href="https://4read.org/6945-....html"><div class="linek__title ws-nowrap">Чорти - Джо Аберкромбі</div></a>
     *     <div class="linek__meta ws-nowrap"><span>Триває:</span> 21:42:42</div>
     *   </div>
     * </div>
     * ```
     */
    fun parseTop100(html: String): List<CatalogBook> {
        val opener = """<div class="linek d-flex ai-center has-overlay card">"""
        val indices = Regex(Regex.escape(opener)).findAll(html).map { it.range.first }.toList()
        if (indices.isEmpty()) return emptyList()
        val chunks = indices.mapIndexed { i, start ->
            html.substring(start, indices.getOrNull(i + 1) ?: html.length)
        }
        return chunks.mapNotNull { chunk ->
            val bookUrl = bookUrlRegex.find(chunk)?.value ?: return@mapNotNull null
            val titleLine = Regex("""class="linek__title ws-nowrap">([^<]+)</div>""").find(chunk)
                ?.groupValues?.get(1)
                ?.let { decodeEntities(it.trim()) }
                ?.takeIf { it.length >= 2 }
                ?: return@mapNotNull null
            // "Title - Author": split at the LAST separator so titles that
            // themselves contain " - " stay intact.
            val split = titleLine.lastIndexOf(" - ")
            val title = if (split > 0) titleLine.substring(0, split).trim() else titleLine
            val author = if (split > 0) titleLine.substring(split + 3).trim() else ""
            val cover = toAbsoluteUrl(Regex("""<img[^>]+src="([^"]+)""").find(chunk)?.groupValues?.get(1))
            val duration = parseInlineDuration(chunk)
            CatalogBook(
                id = bookId(bookUrl),
                title = title,
                author = author,
                url = bookUrl,
                coverImageUrl = cover,
                totalDurationSeconds = duration
            )
        }
    }

    /**
     * Parses the Виконавці/Автори index pages (`/readers.html`, `/avtors.html`)
     * into people. Each entry is
     * `<li><a href="/xfsearch/chitaet/Ім'я/">Ім'я - N книг</a></li>`, so the
     * person's book page is `/xfsearch/<kind>/<name>/` (a poster grid).
     */
    fun parsePeopleList(html: String): List<CatalogPerson> {
        return Regex("""<li><a href="(/xfsearch/(?:chitaet|avtor)/[^"]+)"[^>]*>([^<]+)</a></li>""")
            .findAll(html)
            .mapNotNull { m ->
                val label = decodeEntities(m.groupValues[2].trim())
                val name = label.substringBefore(" - ").trim()
                if (name.length < 2) return@mapNotNull null
                val count = Regex(""" - (\d+) книг""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                CatalogPerson(name = name, path = m.groupValues[1], bookCount = count)
            }
            .toList()
    }

    /**
     * Real duration from a duration span — both the ТОП 100 "Триває:" label
     * and the "Популярне" clock meta (`<span class="fal fa-clock"></span>
     * 24:54:14`). 0 when absent.
     */
    private fun parseInlineDuration(chunk: String): Long {
        val raw = Regex("""(?:Триває:</span>|fa-clock"></span>)\s*(\d{1,2}:\d{2}(?::\d{2})?)""")
            .find(chunk)?.groupValues?.get(1) ?: return 0L
        val parts = raw.split(":").map { it.toLongOrNull() ?: return 0L }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            else -> 0L
        }
    }

    /** Shared poster-chunk → books logic for series and related-book sections. */
    private fun parsePosterBooks(html: String): List<CatalogBook> {
        val books = mutableListOf<CatalogBook>()
        for (poster in splitPosters(html)) {
            val bookUrl = bookUrlRegex.find(poster)?.value ?: continue
            val title = titleRegex.find(poster)?.groupValues?.get(1)
                ?.let { decodeEntities(it.trim()) }
                ?.takeIf { it.length >= 2 && !isPromoTitle(it) }
                ?: continue
            val author = authorRegex.find(poster)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?: ""

            val series = seriesRegex.find(poster)
            var seriesTitle: String? = null
            var seriesUrl: String? = null
            if (series != null) {
                seriesUrl = series.groupValues[1]
                seriesTitle = decodeEntities(series.groupValues[2].trim())
            }
            val seriesIndex = seriesIndexRegex.find(poster)?.groupValues?.get(1)?.toIntOrNull()

            books.add(
                CatalogBook(
                    id = bookId(bookUrl),
                    title = title,
                    author = author,
                    url = bookUrl,
                    coverImageUrl = toAbsoluteUrl(imgRegex.find(poster)?.groupValues?.get(1)),
                    seriesTitle = seriesTitle,
                    seriesUrl = seriesUrl,
                    seriesIndex = seriesIndex
                )
            )
        }
        return books
    }

    /**
     * Splits the page into one chunk per poster. Posters are contiguous
     * `<div class="poster ...">` blocks; splitting on their opening tag and
     * keeping the remainder between the current and next opening tag yields a
     * chunk per poster regardless of how the surrounding markup changes.
     */
    private fun splitPosters(html: String): List<String> {
        // The trailing space is deliberate: `<div class="poster__desc">` and
        // friends also start with `<div class="poster`, so a bare prefix would
        // split every poster into fragments. Only the real poster container
        // has a space after "poster".
        val opener = """<div class="poster """
        val indices = Regex(Regex.escape(opener)).findAll(html).map { it.range.first }.toList()
        if (indices.isEmpty()) return emptyList()
        return indices.mapIndexed { i, start ->
            val end = indices.getOrNull(i + 1) ?: html.length
            html.substring(start, end)
        }
    }

    /** The "4read-slug" book id scheme — produced in exactly this one place. */
    internal fun bookId(url: String): String {
        val slug = url.removePrefix("https://4read.org/").removeSuffix(".html")
        return "4read-$slug"
    }

    private fun toAbsoluteUrl(src: String?): String? {
        if (src.isNullOrBlank()) return null
        return if (src.startsWith("http")) src else "$SITE$src"
    }

    private fun isPromoTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("реклам") ||
            lower.contains("без рекламы") ||
            lower.startsWith("топ-") ||
            lower.startsWith("\uD83D\uDD25") // 🔥 promo emoji used by 4read
    }

    /** Minimal numeric + named HTML entity decoding (&#039; and &amp; etc.). */
    internal fun decodeEntities(input: String): String {
        var out = htmlEntityRegex.replace(input) { m ->
            m.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: m.value
        }
        out = htmlEntityNamedRegex.replace(out) { m ->
            when (m.groupValues[1]) {
                "amp" -> "&"
                "quot" -> "\""
                "apos", "#039" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                else -> m.value
            }
        }
        return out
    }
}
