package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-37 T2 — the sound-books.net TOP-100 live collection: the site's
 * server-rendered `short-item` tiles with `short-title` anchors
 * «Назва - Автор», mapped into one «ТОП-100 sound-books» collection.
 * The matcher hides non-matches against the catalog union like every other
 * collection.
 *
 * Transport: the shared [HttpFetcher] (ADR-0006 — one HTTP transport, no raw
 * connections). Best-effort: a fetch failure, a changed upstream shape or an
 * empty payload yields no collection.
 *
 * Scoping: only `a.short-title` anchors are parsed — sidebar / comments /
 * related blocks that also link to sound-books books use different markup
 * and must be ignored (verified on the live top page: the main list is the
 * only place with `short-title` links).
 */
class SoundBooksTopSource(
    private val fetcher: HttpFetcher = HttpFetcher(),
    private val endpoint: String = TOP_ENDPOINT,
    private val limit: Int = 40
) : LiveCollectionSource {

    override val sourceId: String = "soundbooks-top"

    override suspend fun fetchLiveCollections(): List<CollectionList> = withContext(Dispatchers.IO) {
        try {
            val html = fetcher.getText(endpoint)
            if (html.isBlank()) return@withContext emptyList()
            val entries = parseTiles(html).take(limit)
            if (entries.isEmpty()) return@withContext emptyList()
            listOf(
                CollectionList(
                    id = "soundbooks-top",
                    name = "ТОП-100 sound-books",
                    sourceNote = "ТОП-100 найпопулярніших аудіокниг на sound-books.net — за прослуховуваннями. Оновлюється автоматично при оновленні каталогу.",
                    entries = entries
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseTiles(html: String): List<CollectionEntry> {
        val results = mutableListOf<CollectionEntry>()
        for (m in SHORT_TITLE.findAll(html)) {
            val rawAnchor = m.groupValues[2].trim()
            if (rawAnchor.length < 3) continue
            val anchor = decodeEntities(rawAnchor)
            val sep = anchor.lastIndexOf(" - ")
            if (sep < 0) continue
            val title = anchor.substring(0, sep).trim()
            val author = anchor.substring(sep + 3).trim()
            if (title.isBlank() || author.isBlank()) continue
            results += CollectionEntry(author = author, title = title)
        }
        return results
    }

    private fun decodeEntities(s: String): String {
        var out = s
        // Named entities used on sound-books tiles (&#039; for apostrophe).
        out = out.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // Numeric decimal entities &#123;
        out = numericEntityRegex.replace(out) { mr ->
            val code = mr.groupValues[1].toIntOrNull() ?: return@replace mr.value
            try {
                code.toChar().toString()
            } catch (_: Exception) {
                mr.value
            }
        }
        return out
    }

    companion object {
        const val TOP_ENDPOINT = "https://sound-books.net/top-100-audioknyg-nashogu-saitu.html"

        // Only the main-list tiles carry this class — sidebar / comments / related
        // blocks use different markup and must be ignored.
        private val SHORT_TITLE = Regex(
            """<a\s+class="short-title"[^>]*href="(https://sound-books\.net/[^"]+\.html)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val numericEntityRegex = Regex("""&#(\d+);""")
    }
}
