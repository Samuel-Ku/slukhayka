package com.slukhayka.audiobooks.data.collections

/**
 * Spec-16 smart collections — the curated-list data model (pure JVM).
 *
 * A [CollectionList] is one curated external list (Нобелівські лауреати,
 * Шевченківська премія, Букер) bundled as a static JSON asset. Each
 * [CollectionEntry] is one book claim: an author (always) plus an optional
 * title and an optional per-entry note (the award year, a category, …).
 * Entries with no title are the author-only case — every catalog book of
 * that author belongs to the collection ([CollectionMatcher]).
 */
data class CollectionEntry(
    val author: String,
    val title: String? = null,
    val note: String? = null
)

/** One curated collection: stable [id], display [name], a source-of-list
 *  note and the curated [entries]. */
data class CollectionList(
    val id: String,
    val name: String,
    val sourceNote: String = "",
    val entries: List<CollectionEntry> = emptyList()
)
