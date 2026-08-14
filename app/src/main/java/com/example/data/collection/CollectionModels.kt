package com.example.data.collection

import com.example.data.catalog.CatalogBook
import com.squareup.moshi.JsonClass

/**
 * Spec-16 T1 (#107) — a curated external list: metadata plus entries
 * (author, optional title). Shipped as a static JSON asset; adding a
 * collection is a data change, not a code change.
 */
@JsonClass(generateAdapter = true)
data class CollectionSpec(
    val id: String,
    val displayName: String,
    val note: String? = null,
    val entries: List<CollectionEntry>
)

/** One curated list entry. A blank/absent [title] enables the author-only rule. */
@JsonClass(generateAdapter = true)
data class CollectionEntry(
    val author: String,
    val title: String? = null
)

/**
 * Spec-16 T1/T2 — the result of matching one [CollectionSpec] against the
 * catalog: the display metadata plus the books that are actually available.
 * Empty match sets never produce a [MatchedCollection] — the block hides
 * empty rows entirely.
 */
data class MatchedCollection(
    val id: String,
    val displayName: String,
    val note: String? = null,
    val books: List<CatalogBook>
)