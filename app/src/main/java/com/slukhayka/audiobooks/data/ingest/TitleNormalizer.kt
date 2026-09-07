package com.slukhayka.audiobooks.data.ingest

/**
 * ADR-0035 / #602 — the pure-JVM parser that turns ONE channel post caption
 * into Work-identity Metadata Assertions: title, author, narrator, series,
 * position. No Android, no network, no database — fixture-tested from real
 * harvested posts.
 *
 * A **Channel Post Pattern** is the observed post template of ONE channel
 * (the glossary term from CONTEXT.md). Parsing is deliberately conservative:
 * a claim (author, narrator, series) is recorded ONLY when the post itself
 * declares it in the channel's observed shape — the author is NEVER invented,
 * and unmatched residue stays as the raw title. The final display cleaning
 * (emoji, SEO suffixes) stays at the ONE write-path normalization seam
 * (ADR-0004, [MetadataAssertions.normalizeTitle]); this parser only cuts
 * what it can prove: a declared author prefix, a declared narrator line, a
 * declared series line, and the trailing channel/brand mark.
 *
 * Fixtures (2026-09-07, harvested from the public `t.me/s/stivenkingua`
 * preview): «Стівен Кінг - Острів Дума» + «🔊Читає: Сергій Філатов»;
 * «Входить до збірки #Нічна_зміна (16\20)»; «Серія: #Темна_вежа (книга
 * 5\7)»; «#Якщо_кров_тече 2/4»; the channel's own typo «#Все_можливо
 * 4\14)»; multi-narrator «Сергій Філатов | Макс Шлапак»; co-author «Стівен
 * Кінг, Овен Кінг»; posts WITHOUT an author prefix («Джералдова гра»).
 * Other channels from the spec list expose no public preview yet — each gets
 * its pattern (and fixtures) when its posts are actually observed.
 */
object TitleNormalizer {

    /** One post parsed into identity claims. Absent fields are null — a
     *  claim exists only when the post declares it; the title is the
     *  author-prefix- and source-mark-free remainder, raw otherwise. */
    data class ParsedPost(
        val title: String,
        val author: String? = null,
        val narrator: String? = null,
        val series: String? = null,
        val position: Int? = null,
        val positionTotal: Int? = null,
        val sourceMark: String? = null
    )

    /** The observed post template of one channel: the author prefixes the
     *  channel actually signs with, plus the narrator/series line shapes. */
    data class ChannelPostPattern(
        val channelId: String,
        val authorPrefixes: List<String> = emptyList(),
        val narratorLine: Regex = DEFAULT_NARRATOR_LINE,
        val seriesLine: Regex = DEFAULT_SERIES_LINE
    )

    // ------------------------------------------------------------------
    // Shared line shapes (declared BEFORE the pattern instances — object
    // members initialize in declaration order).
    // ------------------------------------------------------------------

    /** A leading dash separator: « - », « — » or « – », then the title. */
    private val DASH_SEPARATED_TITLE = Regex("""^\s*(-|—|–)\s*(.+)$""")

    /** The narrator claim line observed on @stivenkingua: «🔊Читає: Ім'я»,
     *  «🔊Озвучував: Ім'я» (the 🔊 emoji is `\p{So}` — portable on JVM and
     *  Android). `(?iu)` folds Cyrillic case — RegexOption.IGNORE_CASE
     *  alone folds ASCII only (the same rule as the description scrub). */
    private val DEFAULT_NARRATOR_LINE = Regex(
        """(?iu)^(?:\p{So}\s*)?(?:читає|озвучував|озвучує)\s*:?\s*(.+)$"""
    )

    /** The series claim line observed on @stivenkingua: «Входить до збірки
     *  #Серія (16\20)», «Серія: #Серія (книга 5\7)», «#Серія 2/4» — the
     *  1-based position may sit behind `\` or `/`, with or without parens
     *  (both spellings observed, incl. the channel's own «4\14)» typo). */
    private val DEFAULT_SERIES_LINE = Regex(
        """(?iu)^(?:входить\s+до\s+збірки|серія)\s*:?\s*#([\p{L}\p{N}_]+)\s*(?:\(?\s*(?:книга\s*)?(\d+)\s*[\\/]\s*(\d+)\s*\)?)?$"""
    )

    /** Channel/brand marks cut from the END of any claimed title, behind a
     *  separator (« — », « - », «|», «(», «,», «:»). */
    private val SOURCE_MARK = Regex(
        """(?iu)\s*(?:[-—–:,|(]\s*)?(аудіокниги\s+українською|книги\s+українською)\s*\)?\s*$"""
    )

    /**
     * @stivenkingua — the observed pattern. Author prefixes are the
     * channel's own signed titles; a post WITHOUT a prefix keeps author
     * null — the channel's brand is never substituted for an invented
     * author (a non-King book could appear in the feed tomorrow).
     */
    val stivenkingua = ChannelPostPattern(
        channelId = "@stivenkingua",
        authorPrefixes = listOf("Стівен Кінг, Овен Кінг", "Стівен Кінг")
    )

    private val KNOWN: Map<String, ChannelPostPattern> =
        listOf(stivenkingua).associateBy { it.channelId }

    /** Parses one post caption. An unknown channel gets the generic pattern
     *  (source-mark cut only): everything else stays raw — «нерозібраний
     *  залишок лишається сирим текстом» (ADR-0035 п. 4). */
    fun parse(raw: String, channelId: String): ParsedPost {
        val pattern = KNOWN[channelId] ?: return parse(raw, ChannelPostPattern(channelId))
        return parse(raw, pattern)
    }

    /** Parses one post caption against an explicit pattern. */
    fun parse(raw: String, pattern: ChannelPostPattern): ParsedPost {
        var title: String? = null
        var author: String? = null
        var narrator: String? = null
        var series: String? = null
        var position: Int? = null
        var positionTotal: Int? = null
        var sourceMark: String? = null

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val narratorClaim = pattern.narratorLine.matchEntire(trimmed)
            if (narratorClaim != null && narrator == null) {
                narrator = narratorClaim.groupValues[1].trim()
                continue
            }

            val seriesClaim = pattern.seriesLine.matchEntire(trimmed)
            if (seriesClaim != null && series == null) {
                // The hashtag «#Нічна_зміна» is the channel's own series
                // name («Нічна зміна») — underscores are word separators.
                series = seriesClaim.groupValues[1].replace('_', ' ').trim()
                seriesClaim.groupValues[2].toIntOrNull()?.let { position = it }
                seriesClaim.groupValues[3].toIntOrNull()?.let { positionTotal = it }
                continue
            }

            if (title == null) {
                val (mark, remainder) = cutSourceMark(trimmed)
                sourceMark = mark
                val (prefix, rest) = splitAuthorPrefix(remainder, pattern.authorPrefixes)
                author = prefix
                // A whole-string mark never blanks the title.
                title = rest.ifBlank { trimmed }
            }
        }

        return ParsedPost(
            title = title ?: raw.trim(),
            author = author,
            narrator = narrator,
            series = series,
            position = position,
            positionTotal = positionTotal,
            sourceMark = sourceMark
        )
    }

    /** Splits «Стівен Кінг - Острів Дума» into (prefix, remainder). The
     *  prefix must be followed by a dash separator — «Стівен Кінг, Овен
     *  Кінг - …» never splits at the comma, so prefix order is irrelevant.
     *  A prefix with nothing after it never yields an author. */
    private fun splitAuthorPrefix(line: String, prefixes: List<String>): Pair<String?, String> {
        for (prefix in prefixes) {
            if (!line.startsWith(prefix)) continue
            val rest = line.removePrefix(prefix).trimStart()
            val title = DASH_SEPARATED_TITLE.matchEntire(rest)?.groupValues?.get(2)?.trim() ?: continue
            if (title.isNotBlank()) return prefix to title
        }
        return null to line
    }

    /** Cuts a known channel/brand mark («— АудіоКниги Українською») from
     *  the END of a claimed title and returns it as [ParsedPost.sourceMark]
     *  — the plural brand form the site actually appends (spec-27 BUG-002).
     *  The mark behind a separator is noise everywhere; a whole-string mark
     *  never blanks the title. */
    private fun cutSourceMark(line: String): Pair<String?, String> {
        val match = SOURCE_MARK.find(line) ?: return null to line
        val cut = line.dropLast(match.value.length).trimEnd().trim()
        if (cut.isEmpty()) return null to line
        return match.groupValues[1] to cut
    }
}