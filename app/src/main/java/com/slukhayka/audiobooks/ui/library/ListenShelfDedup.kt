package com.slukhayka.audiobooks.ui.library

/**
 * spec-28 (#191) — full cross-shelf dedup for the Listen shelves.
 *
 * A book renders in at most ONE shelf — the highest one on screen. The
 * function walks the blocks in DISPLAY order and claims each book id once
 * into an internal claimed set (the ticket's `visibleWorkIds`, at the
 * library card level); a book already claimed by an earlier block is dropped
 * from every later shelf, so reordering a shelf re-prioritises which shelf
 * claims a book.
 *
 * spec-28 (#200): the function owns the ENTIRE claimed set, hero included.
 * The [ListenComposer.BlockId.HERO] block is exempt from dedup — its resume
 * card always keeps its book — and its book is claimed FIRST, before any
 * shelf runs, so the hero book never leaks into a shelf below (US-3). No
 * caller pre-seeds anything; correctness does not depend on caller memory.
 *
 * Pure JVM — dedup and empty-shelf behaviour are unit-tested in
 * `ListenShelfDedupTest`.
 */
fun deduplicateListenShelves(
    blocks: List<ListenComposer.Block>
): List<ListenComposer.Block> {
    val claimedBookIds = mutableSetOf<String>()
    // The hero claims its book(s) before any shelf is walked — the resume
    // card always keeps its book, which never leaks into a shelf below.
    blocks.firstOrNull { it.id == ListenComposer.BlockId.HERO }
        ?.books
        ?.forEach { claimedBookIds.add(it.book.id) }
    return blocks.map { block ->
        if (block.id == ListenComposer.BlockId.HERO) return@map block
        val kept = block.books.filter { claimedBookIds.add(it.book.id) }
        if (kept.size == block.books.size) block else block.copy(books = kept)
    }
}
