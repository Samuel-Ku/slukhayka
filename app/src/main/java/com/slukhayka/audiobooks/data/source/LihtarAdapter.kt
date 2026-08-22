package com.slukhayka.audiobooks.data.source

/**
 * lihtar.in.ua [SourceAdapter] (spec-10 T1 verdict: PASS, niche).
 *
 * Book pages (e.g. `/biblioteka/dytjacha-literatura/<slug>`) link «Слухати»
 * to the player host `https://web.lihtar.in.ua/library/<cat>/<slug>`, whose
 * page embeds `<audio id="player" src="https://web.lihtar.in.ua/audio/library/
 * <id>/<slug>-converted.mp3">`. One book = one audio file (the player's
 * `onended="nextsound()"` suggests some titles may be multi-part; the adapter
 * exposes the first stream, which is what the page itself plays first).
 *
 * No search endpoint exists — [search] returns empty; [fetchNew] enumerates
 * the library category pages.
 *
 * ## True profile completeness (spec-35 T3, inventory #237)
 *
 * What lihtar really provides, verified live: the book page carries the cover
 * (`og:image`), the real title (`og:title`/`<h1>`), the real author (the `<h4>`
 * right after the `<h1>`; fallback the full `og:description` — which IS the
 * author name, never truncated), and — on pages that have one — a full
 * duration (accepted via `«Тривалість:»` / `«Триває:»` / `itemprop="duration"`;
 * no live page carries it today). Measured negative findings, never
 * fabricated (ADR-0014):
 * - **No narrator anywhere** (no «Читає/Виконавець» on the book page or the
 *   `web.lihtar.in.ua` player host).
 * - **`og:description` is the AUTHOR, not a blurb** — so [SourceBookDetail.description]
 *   stays empty; the author is never substituted as a description.
 * - **No genres** on the book page (only the breadcrumb category), no series,
 *   no rating, no related rail — on both surfaces.
 */
class LihtarAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "lihtar"

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val html = fetcher.getText(url)
        if (html.isEmpty()) return SourceBookDetail("", "", url = url, chapters = emptyList())

        val title = decodeEntities(ogMeta(html, "og:title") ?: h1(html) ?: "").ifBlank { slugTitle(url) }
        val author = authorFrom(html)
        // Spec-35 T3: the book page's own og:image is the cover (the card
        // covers live on listing pages only). Narrator and description stay
        // absent: lihtar has no narrator anywhere and og:description IS the
        // author, never a blurb — the author is never substituted as a
        // description (ADR-0014; see class KDoc for the #237 negative
        // findings).
        val coverImageUrl = ogMeta(html, "og:image")?.takeIf { it.isNotBlank() }
        val totalDurationSeconds = durationFrom(html)

        val playerUrl = LISTEN_LINK.find(html)?.groupValues?.get(1)
            ?: return SourceBookDetail(
                title = title,
                author = author,
                url = url,
                coverImageUrl = coverImageUrl,
                totalDurationSeconds = totalDurationSeconds,
                chapters = emptyList()
            )

        val playerHtml = fetcher.getText(playerUrl)
        val audioSrc = AUDIO_SRC.find(playerHtml)?.groupValues?.get(1)
        val chapters = if (audioSrc != null) {
            listOf(
                SourceChapter(
                    title = title.ifBlank { "Аудіокнига" },
                    streamUrl = audioSrc
                )
            )
        } else {
            emptyList()
        }

        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            coverImageUrl = coverImageUrl,
            totalDurationSeconds = totalDurationSeconds,
            chapters = chapters
        )
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        // The /biblioteka landing page only lists the category groups, not the
        // books — the feed has to enumerate each category page and collect its
        // book links. Category pages carry only transliterated slugs; the real
        // Cyrillic title and the author live on the book page (og:title and
        // og:description), so every feed entry is enriched from it — otherwise
        // a Ukrainian query would never match the slug and no merge key forms.
        val html = fetcher.getText("https://lihtar.in.ua/biblioteka")
        if (html.isEmpty()) return emptyList()
        val categories = CATEGORY_LINK.findAll(html).map { it.groupValues[1] }.toList()
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        for (category in categories) {
            if (books.size >= limit) break
            val categoryHtml = fetcher.getText(category)
            for (m in BOOK_LINK.findAll(categoryHtml)) {
                val url = m.groupValues[1]
                if (!seen.add(url)) continue
                // Best-effort: a failed fetch keeps the transliterated slug.
                val meta = pageMeta(url)
                books += SourceBook(
                    title = meta.title.ifBlank { slugTitle(url) },
                    author = meta.author,
                    url = url,
                    sourceId = sourceId,
                    coverImageUrl = meta.cover.ifBlank { null },
                    // Spec-35 T3: the card carries the page's duration when
                    // the page provides one (no live page does today).
                    totalDurationSeconds = meta.durationSeconds ?: 0L
                )
                if (books.size >= limit) break
            }
        }
        return books
    }

    /** Real title, author, cover and duration of a book page, best-effort. */
    private data class PageMeta(
        val title: String,
        val author: String,
        val cover: String,
        val durationSeconds: Long?
    )

    /**
     * Fetches a book page and extracts its real title, author, cover (og:image)
     * and duration (when present), best-effort — a failed fetch keeps the slug
     * and empty values.
     */
    private suspend fun pageMeta(url: String): PageMeta {
        return try {
            val html = fetcher.getText(url)
            if (html.isEmpty()) return PageMeta("", "", "", null)
            PageMeta(
                decodeEntities(ogMeta(html, "og:title") ?: h1(html) ?: ""),
                authorFrom(html),
                ogMeta(html, "og:image") ?: "",
                durationFrom(html)
            )
        } catch (e: Exception) {
            PageMeta("", "", "", null)
        }
    }

    /**
     * The real author. Primary source: the `<h4>` subtitle right after the
     * `<h1>` title (live pages render «Чарівні історії нашого лісу» /
     * «Ольга Гура»). Fallback: the FULL og:description — on lihtar it IS the
     * author name, never truncated (spec-35 T3: the old `take(80)` cut real
     * names).
     */
    private fun authorFrom(html: String): String {
        val h1Match = Regex("""<h1[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        val h4AfterH1 = h1Match?.let { m ->
            Regex("""<h4[^>]*>(.*?)</h4>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html, m.range.last)
                ?.groupValues?.get(1)
        }
        val visible = h4AfterH1
            ?.let { Regex("""<[^>]+>""").replace(it, "") }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (visible != null) return decodeEntities(visible)
        return decodeEntities(ogMeta(html, "og:description")?.trim().orEmpty())
    }

    /**
     * Full duration when the page carries one. No live lihtar page provides it
     * (#237 negative finding) — the parser accepts the standard markers
     * («Тривалість:» / «Триває:» / `itemprop="duration"`) so a page that gains
     * the field is preserved; absent stays null (ADR-0014).
     */
    private fun durationFrom(html: String): Long? {
        val raw = Regex(
            """(?:itemprop="duration"\s+content="|Тривалість:\s*|Триває:\s*)(\d{1,2}:\d{2}(?::\d{2})?)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return null
        return parseDurationSeconds(raw)
    }

    private fun h1(html: String): String? =
        Regex("""<h1>([^<]+)</h1>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()

    private fun slugTitle(url: String): String =
        titleFromSlug(url.substringAfterLast('/').substringBefore('?'))

    private companion object {
        val LISTEN_LINK = Regex("""href="(https://web\.lihtar\.in\.ua/library/[^"]+)"""", RegexOption.IGNORE_CASE)
        val AUDIO_SRC = Regex("""<audio[^>]+src="(https://web\.lihtar\.in\.ua/audio/[^"]+)"""", RegexOption.IGNORE_CASE)
        val CATEGORY_LINK = Regex("""href="(https://lihtar\.in\.ua/biblioteka/[a-z0-9-]+)"""", RegexOption.IGNORE_CASE)
        val BOOK_LINK = Regex("""href="(https://lihtar\.in\.ua/biblioteka/[a-z0-9-]+/[a-z0-9-]+)"""", RegexOption.IGNORE_CASE)
    }
}
