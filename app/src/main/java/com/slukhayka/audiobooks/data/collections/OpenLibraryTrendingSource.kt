package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-16 follow-up — the keyless live collection: OpenLibrary's trending
 * feed (`/trending/now.json`, the enrichment spike's keyless secondary
 * source) mapped into one «Популярне зараз» collection. The matcher hides
 * non-matches, so the English-centric trending list contributes only the
 * books the catalog actually carries — the designed fallback, never gaps.
 *
 * Transport: the shared [HttpFetcher] (ADR-0006 — one HTTP transport, no raw
 * connections); parsing: the shared [MiniJson]. Best-effort: a fetch failure,
 * a changed upstream shape or an empty payload yields no collection.
 */
class OpenLibraryTrendingSource(
    private val fetcher: HttpFetcher = HttpFetcher(),
    private val endpoint: String = TRENDING_ENDPOINT,
    private val limit: Int = 40
) : LiveCollectionSource {

    override val sourceId: String = "openlibrary-trending"

    override suspend fun fetchLiveCollections(): List<CollectionList> = withContext(Dispatchers.IO) {
        try {
            val text = fetcher.getText(endpoint)
            if (text.isBlank()) return@withContext emptyList()
            val parsed = MiniJson.parse(text) as? Map<*, *> ?: return@withContext emptyList()
            val works = parsed["works"] as? List<*> ?: return@withContext emptyList()
            val entries = works.mapNotNull { raw ->
                val work = raw as? Map<*, *> ?: return@mapNotNull null
                val title = work["title"] as? String ?: return@mapNotNull null
                // The primary author is the first name in the array; the
                // Work is matched by (author, title) like any other entry.
                val author = (work["author_name"] as? List<*>)?.firstOrNull() as? String
                    ?: return@mapNotNull null
                if (title.isBlank() || author.isBlank()) return@mapNotNull null
                CollectionEntry(author = author, title = title)
            }.take(limit)
            if (entries.isEmpty()) return@withContext emptyList()
            listOf(
                CollectionList(
                    id = "live-trending",
                    name = "Популярне зараз",
                    sourceNote = "Тренди відкритої бібліотеки (Open Library) — книги, які зараз читають. Оновлюється автоматично при оновленні каталогу.",
                    entries = entries
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val TRENDING_ENDPOINT = "https://openlibrary.org/trending/now.json"
    }
}
