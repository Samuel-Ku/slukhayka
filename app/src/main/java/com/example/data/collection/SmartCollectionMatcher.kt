package com.example.data.collection

import com.example.data.catalog.CatalogBook

/**
 * Spec-16 T1 (#107) — the pure JVM matcher of smart collections.
 *
 * A curated [CollectionSpec] (id, display name, note, entries) is matched
 * against the catalog: an entry matches a book when normalized author agrees
 * AND (normalized title agrees, unless the entry carries no title). The
 * author-only fallback (enrichment-spike rule) matches a single book at most
 * — several books by that author is ambiguity, so the entry contributes
 * nothing. Matching never fabricates: an entry with no catalog match simply
 * contributes nothing, and a collection whose match set is empty is absent
 * from the output. A book appears at most once per collection (first
 * matching entry wins), in entry order.
 *
 * Pure JVM, zero Android/network/database dependencies — pinned by fixture
 * tests.
 */
object SmartCollectionMatcher {

    /**
     * Matches every spec against the catalog. Returns specs with non-empty
     * match sets only, in input order.
     */
    fun matchCollections(
        specs: List<CollectionSpec>,
        books: List<CatalogBook>
    ): List<MatchedCollection> = specs.mapNotNull { spec -> matchSpec(spec, books) }

    private fun matchSpec(spec: CollectionSpec, books: List<CatalogBook>): MatchedCollection? {
        val normalizedBooks = books.map { book ->
            book to NormalizedBook(
                author = CollectionNormalizer.normalize(book.author),
                title = CollectionNormalizer.normalize(book.title)
            )
        }
        val matched = mutableListOf<CatalogBook>()
        val seen = mutableSetOf<String>()
        for (entry in spec.entries) {
            // A blank/absent title enables the author-only rule (CollectionModels):
            // normalize only when the title carries real content.
            val normalizedEntry = NormalizedEntry(
                author = CollectionNormalizer.normalize(entry.author),
                title = entry.title?.takeIf { it.isNotBlank() }?.let(CollectionNormalizer::normalize)
            )
            if (normalizedEntry.author.isBlank()) continue
            val authorsBooks = normalizedBooks.filter { it.second.author == normalizedEntry.author }
            val candidates = when {
                normalizedEntry.title != null ->
                    authorsBooks.filter { it.second.title == normalizedEntry.title }.map { it.first }
                // Author-only fallback: a single unambiguous book only.
                else ->
                    if (authorsBooks.size == 1) listOf(authorsBooks.single().first) else emptyList()
            }
            for (book in candidates) {
                if (seen.add(book.id)) matched += book
            }
        }
        if (matched.isEmpty()) return null
        return MatchedCollection(
            id = spec.id,
            displayName = spec.displayName,
            note = spec.note,
            books = matched
        )
    }

    private data class NormalizedBook(val author: String, val title: String)
    private data class NormalizedEntry(val author: String, val title: String?)
}