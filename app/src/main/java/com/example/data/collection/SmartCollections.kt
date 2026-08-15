package com.example.data.collection

import com.example.data.catalog.CatalogBook
import com.example.data.db.AudiobookDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-16 T2 (#108) — smart collections: matched on every catalog sync.
 * Ported from the old god repository onto the deep-module seams (ADR-0002):
 * the match sets are recomputed against the stored catalog — every book in
 * Room is the playable multi-source union, so a book just imported (any
 * source) surfaces in its collections on the next sync.
 *
 * Purely in-memory: nothing is persisted, an empty match set contributes
 * nothing. The matcher is the pure [SmartCollectionMatcher] module, so the
 * recompute is a two-liner: read rows, run the rules. The curated lists
 * come from [SmartCollectionAssets]; a test may inject its own loader.
 */
class SmartCollections(
    private val dao: AudiobookDao,
    private val loadSpecs: suspend () -> List<CollectionSpec>
) {
    private val _matched = MutableStateFlow<List<MatchedCollection>>(emptyList())
    val matched: StateFlow<List<MatchedCollection>> = _matched.asStateFlow()

    suspend fun recompute() = withContext(Dispatchers.IO) {
        val specs = loadSpecs()
        val books = dao.getAllAudiobooksOnce().map { book ->
            CatalogBook(
                id = book.id,
                title = book.title,
                author = book.author,
                url = book.sourceUrl,
                coverImageUrl = book.coverImageUrl
            )
        }
        _matched.value = SmartCollectionMatcher.matchCollections(specs, books)
    }
}
