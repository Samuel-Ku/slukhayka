package com.example.data.catalog

/**
 * Pure-JVM parser for the 4read.org catalogue (no Android dependencies, so it
 * is unit-testable with plain JUnit on HTML fixtures — prior art:
 * `AudioParsingTest`).
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
    val seriesIndex: Int? = null
)

/** One series (cycle) chip shown in the "Цикли" row. */
data class CatalogSeries(
    val title: String,
    val url: String,
    val coverImageUrl: String?
)

/** A horizontal row of the Explore screen. */
data class CatalogSection(
    val title: String,
    val books: List<CatalogBook> = emptyList(),
    val series: List<CatalogSeries> = emptyList()
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
        if (books.isNotEmpty()) {
            sections.add(CatalogSection(SECTION_NEW, books = books))
        }
        if (seriesByUrl.isNotEmpty()) {
            sections.add(CatalogSection(SECTION_SERIES, series = seriesByUrl.values.toList()))
        }
        return sections
    }

    /**
     * Parses a series (cycle) page — `4read.org/xfsearch/cikl/<slug>/` — into
     * its books. The page reuses the poster markup of the homepage.
     */
    fun parseSeriesPage(html: String): List<CatalogBook> {
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

    private fun bookId(url: String): String {
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
