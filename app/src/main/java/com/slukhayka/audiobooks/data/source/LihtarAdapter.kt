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

        val playerUrl = LISTEN_LINK.find(html)?.groupValues?.get(1)
            ?: return SourceBookDetail(title = title, author = author, url = url, chapters = emptyList())

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
                val (title, author, cover) = pageMeta(url)
                books += SourceBook(
                    title = title.ifBlank { slugTitle(url) },
                    author = author,
                    url = url,
                    sourceId = sourceId,
                    coverImageUrl = cover.ifBlank { null }
                )
                if (books.size >= limit) break
            }
        }
        return books
    }

    /**
     * Fetches a book page and extracts its real title, author and cover
     * (og:image), best-effort — a failed fetch keeps the slug and an empty
     * cover.
     */
    private suspend fun pageMeta(url: String): Triple<String, String, String> {
        return try {
            val html = fetcher.getText(url)
            if (html.isEmpty()) return Triple("", "", "")
            Triple(
                decodeEntities(ogMeta(html, "og:title") ?: h1(html) ?: ""),
                authorFrom(html),
                ogMeta(html, "og:image") ?: ""
            )
        } catch (e: Exception) {
            Triple("", "", "")
        }
    }

    /** lihtar renders the author in og:description / meta description. */
    private fun authorFrom(html: String): String =
        decodeEntities(ogMeta(html, "og:description")?.trim()?.take(80) ?: "")

    private fun decodeEntities(s: String): String = s
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    private fun ogMeta(html: String, property: String): String? =
        Regex("""<meta\s+property="$property"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content="([^"]+)"\s+property="$property"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun h1(html: String): String? =
        Regex("""<h1>([^<]+)</h1>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()

    private fun slugTitle(url: String): String =
        url.substringAfterLast('/').substringBefore('?')
            .replace("-", " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }

    private companion object {
        val LISTEN_LINK = Regex("""href="(https://web\.lihtar\.in\.ua/library/[^"]+)"""", RegexOption.IGNORE_CASE)
        val AUDIO_SRC = Regex("""<audio[^>]+src="(https://web\.lihtar\.in\.ua/audio/[^"]+)"""", RegexOption.IGNORE_CASE)
        val CATEGORY_LINK = Regex("""href="(https://lihtar\.in\.ua/biblioteka/[a-z0-9-]+)"""", RegexOption.IGNORE_CASE)
        val BOOK_LINK = Regex("""href="(https://lihtar\.in\.ua/biblioteka/[a-z0-9-]+/[a-z0-9-]+)"""", RegexOption.IGNORE_CASE)
    }
}
