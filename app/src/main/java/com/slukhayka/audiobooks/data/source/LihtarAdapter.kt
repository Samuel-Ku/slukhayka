package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * lihtar.in.ua [SourceAdapter] (spec-10 T1 verdict: PASS, niche).
 *
 * Book pages (e.g. `/biblioteka/dytjacha-literatura/<slug>`) link «Слухати»
 * to the player host `https://web.lihtar.in.ua/library/<cat>/<slug>`, whose
 * page embeds `<audio id="player" src="https://web.lihtar.in.ua/audio/library/
 * <id>/<slug>-converted.mp3">`. Collections instead link to ordered chapter
 * pages. Only `audio#player` is a recording: the first audio on either page
 * is usually a navigation cue (`audio/name/click.mp3`).
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

    /** Spec-45 (#405) — the catalogue speaks Ukrainian. */
    override val contentLanguage = "uk"

    override suspend fun search(query: String): List<SourceBook> = emptyList()

    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveBook(url) }
                ?: SourceBookDetail("", "", url = url, chapters = emptyList())
        }
    }

    private suspend fun bookText(url: String): String = fetcher.awaitListenerText(url)

    private suspend fun resolveBook(url: String): SourceBookDetail {
        val html = bookText(url)
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

        val chapters = resolveChapters(playerUrl, title)

        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            coverImageUrl = coverImageUrl,
            totalDurationSeconds = totalDurationSeconds,
            chapters = chapters
        )
    }

    private suspend fun resolveChapters(playerUrl: String, title: String): List<SourceChapter> {
        val html = bookText(playerUrl)
        playerAudio(html, playerUrl)?.let {
            return listOf(SourceChapter(title.ifBlank { "Аудіокнига" }, it))
        }
        val base = playerUrl.toHttpUrlOrNull() ?: return emptyList()
        val prefix = base.encodedPath.trimEnd('/') + "/"
        val links = linkedMapOf<String, String>()
        for (tag in NAVIGATION_TAG.findAll(html)) {
            val attrs = attributes(tag.value)
            val target = attrs["href"] ?: attrs["onclick"]?.let {
                LOCATION_ASSIGNMENT.find(it)?.groupValues?.get(2)
            } ?: continue
            val child = base.resolve(decodeEntities(target)) ?: continue
            if (child.host != base.host || child.scheme != base.scheme || child.port != base.port ||
                child.username.isNotEmpty() || child.password.isNotEmpty() ||
                child.query != null || child.fragment != null || !child.encodedPath.startsWith(prefix)
            ) continue
            // Only this collection's direct children — never a recursive site crawl.
            val slug = child.encodedPath.removePrefix(prefix).trimEnd('/')
            if (slug.isEmpty() || '/' in slug) continue
            val closing = html.indexOf("</${tag.groupValues[1]}", tag.range.last + 1, ignoreCase = true)
            val label = if (closing >= 0) {
                decodeEntities(stripTags(html.substring(tag.range.last + 1, closing))).trim()
            } else ""
            links.putIfAbsent(child.toString(), label.ifBlank { slugTitle(child.toString()) })
            if (links.size > MAX_CHAPTERS) return emptyList()
        }
        val chapters = mutableListOf<SourceChapter>()
        for ((url, label) in links) {
            currentCoroutineContext().ensureActive()
            // Never drop a missing middle chapter and shift all later indices.
            val audio = playerAudio(bookText(url), url) ?: return emptyList()
            chapters += SourceChapter(label, audio)
        }
        return chapters
    }

    private fun playerAudio(html: String, pageUrl: String): String? =
        AUDIO_TAG.findAll(html).map { attributes(it.value) }
            .firstOrNull { it["id"] == "player" }
            ?.get("src")?.let { pageUrl.toHttpUrlOrNull()?.resolve(decodeEntities(it))?.toString() }
            ?.takeIf(LihtarAudio::isBookAudio)

    private fun attributes(tag: String): Map<String, String> = ATTRIBUTE.findAll(tag).associate {
        it.groupValues[1].lowercase() to it.groupValues[3]
    }

    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        // The /biblioteka landing page only lists the category groups, not the
        // books. Category cards already carry image, Ukrainian title and
        // author. Reading each book separately exhausted the source budget
        // and replaced those visible fields with coverless URL slugs.
        val html = fetcher.getText("https://lihtar.in.ua/biblioteka", emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS)
        if (html.isEmpty()) return emptyList()
        val categories = CATEGORY_LINK.findAll(html).map { it.groupValues[1] }.distinct().toList()
        val seen = mutableSetOf<String>()
        val books = mutableListOf<SourceBook>()
        for (category in categories) {
            if (books.size >= limit) break
            val categoryHtml = fetcher.getText(category, emptyMap(), SourceRequestClass.TTL_REFRESH, FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS)
            for (book in categoryCards(categoryHtml)) {
                if (!seen.add(book.url)) continue
                books += book
                if (books.size >= limit) break
            }
        }
        return books
    }

    /**
     * #529 — ONE listener-opened category page: exactly one request to
     * `/biblioteka/<category>`. Measured 2026-09-12: a lihtar category is ONE
     * page (no pagination links), so there is never a cursor and the next page
     * does not exist. Cards carry the image/title/author already present on
     * that page; one action never follows every book link in the category.
     */
    override suspend fun fetchGenrePage(genrePath: String, cursor: String?, limit: Int): GenrePage {
        val path = genrePath.trim()
        // A lihtar category has no page 2: a continuation is a malformed call.
        if (cursor != null || !CATEGORY_PATH.matches(path)) return GenrePage(emptyList())
        val html = fetcher.getText(
            "https://lihtar.in.ua$path",
            emptyMap(),
            SourceRequestClass.LISTENER_ACTION,
            0L
        )
        if (html.isEmpty()) return GenrePage(emptyList())
        return GenrePage(categoryCards(html).take(limit))
    }

    private fun categoryCards(html: String): List<SourceBook> = BOOK_CARD.findAll(html).map { match ->
        val url = decodeEntities(match.groupValues[1])
        val body = match.groupValues[2]
        val image = IMAGE_TAG.find(body)?.value?.let(::attributes).orEmpty()
        fun text(tag: String): String = Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.get(1)?.let { decodeEntities(stripTags(it)).trim() }.orEmpty()
        SourceBook(
            title = text("h4").ifBlank { image["alt"]?.let(::decodeEntities).orEmpty() }
                .ifBlank { decodeEntities(stripTags(body)).trim() }.ifBlank { slugTitle(url) },
            author = text("p"),
            url = url,
            sourceId = sourceId,
            coverImageUrl = image["src"]?.let { url.toHttpUrlOrNull()?.resolve(decodeEntities(it))?.toString() },
            totalDurationSeconds = durationFrom(body) ?: 0L
        )
    }.distinctBy { it.url }.toList()

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
            ?.let { stripTags(it) }
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
        const val MAX_CHAPTERS = 100
        const val RESOLVE_TIMEOUT_MS = 180_000L
        val AUDIO_TAG = Regex("""<audio\b[^>]*>""", RegexOption.IGNORE_CASE)
        val NAVIGATION_TAG = Regex("""<(a|div)\b[^>]*>""", RegexOption.IGNORE_CASE)
        val ATTRIBUTE = Regex("""([\w-]+)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
        val LOCATION_ASSIGNMENT = Regex("""(?:window\.)?location\.href\s*=\s*(["'])(.*?)\1""")
        val CATEGORY_LINK = Regex("""href="(https://lihtar\.in\.ua/biblioteka/[a-z0-9-]+)"""", RegexOption.IGNORE_CASE)
        val BOOK_CARD = Regex("""<a\b[^>]*href=["'](https://lihtar\.in\.ua/biblioteka/[a-z0-9-]+/[a-z0-9-]+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val IMAGE_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)

        /** #529 — the only category path shapes a listener action may open. */
        private val CATEGORY_PATH = Regex("""^/biblioteka/[a-z0-9-]+$""")
    }
}
