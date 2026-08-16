package com.slukhayka.audiobooks.ui.library

/**
 * spec-28 (#191) — full cross-shelf dedup for the Listen shelves.
 *
 * A book renders in at most ONE shelf — the highest one on screen. The
 * function walks the blocks in DISPLAY order and claims each book id once
 * into [claimedBookIds] (the ticket's `visibleWorkIds` set, at the library
 * card level); a book already claimed by an earlier block is dropped from
 * every later shelf, so reordering a shelf re-prioritises which shelf claims
 * a book.
 *
 * The [ListenComposer.BlockId.HERO] block is exempt: its resume card always
 * keeps its book, and the caller pre-seeds [claimedBookIds] with the hero
 * book first (US-3 — the hero book never leaks into a shelf below).
 *
 * Pure JVM — dedup and empty-shelf behaviour are unit-tested in
 * `ListenShelfDedupTest`.
 */
fun deduplicateListenShelves(
    blocks: List<ListenComposer.Block>,
    claimedBookIds: MutableSet<String> = mutableSetOf()
): List<ListenComposer.Block> = blocks.map { block ->
    if (block.id == ListenComposer.BlockId.HERO) return@map block
    val kept = block.books.filter { claimedBookIds.add(it.book.id) }
    if (kept.size == block.books.size) block else block.copy(books = kept)
}
