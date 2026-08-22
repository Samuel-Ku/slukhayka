package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-37 T1 — the sluhay.com.ua popularity live collection: the site's
 * most-viewed catalog (the «Найпопулярніші» switch, `sort=views`) mapped
 * into one «Популярне у sluhay.com.ua» collection. The matcher hides
 * non-matches against the catalog union like every other collection.
 *
 * Transport: the shared [HttpFetcher] (ADR-0006 — one HTTP transport, no raw
 * connections); parsing: the shared [MiniJson]. Best-effort: a fetch failure,
 * a changed upstream shape or an empty payload yields no collection.
 *
 * The endpoint is XHR-gated (a plain GET serves an HTML shell — verified
 * 2026-08-11, spec-11 spike). The same gate as [com.slukhayka.audiobooks.data.source.SluhayuaAdapter].
 */
class SluhayuaPopularSource(
    private val fetcher: HttpFetcher = HttpFetcher(),
    private val endpoint: String = POPULAR_ENDPOINT,
    private val limit: Int = 40
) : LiveCollectionSource {

    override val sourceId: String = "sluhayua-popular"

    override suspend fun fetchLiveCollections(): List<CollectionList> = withContext(Dispatchers.IO) {
        try {
            val text = fetcher.getText(endpoint, XHR)
            if (text.isBlank()) return@withContext emptyList()
            val parsed = MiniJson.parse(text) as? Map<*, *> ?: return@withContext emptyList()
            val cards = parsed["cards"] as? List<*> ?: return@withContext emptyList()
            val entries = cards.mapNotNull { raw ->
                val card = raw as? Map<*, *> ?: return@mapNotNull null
                val title = card["bookName"] as? String ?: return@mapNotNull null
                val author = (card["bookAuthor"] as? List<*>)?.firstOrNull() as? String
                    ?: return@mapNotNull null
                if (title.isBlank() || author.isBlank()) return@mapNotNull null
                CollectionEntry(author = author.trim(), title = title.trim())
            }.take(limit)
            if (entries.isEmpty()) return@withContext emptyList()
            listOf(
                CollectionList(
                    id = "sluhayua-popular",
                    name = "Популярне у sluhay.com.ua",
                    sourceNote = "Найпопулярніші книги на sluhay.com.ua — за переглядами. Оновлюється автоматично при оновленні каталогу.",
                    entries = entries
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val POPULAR_ENDPOINT = "https://sluhay.com.ua/find/allcards?sort=views&order=desc&page=1"
        private val XHR = mapOf("X-Requested-With" to "XMLHttpRequest")
    }
}
