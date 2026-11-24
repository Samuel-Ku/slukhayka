package com.slukhayka.audiobooks.ui.screens

/**
 * #1704 — what the «Слухати» tab may claim at this moment.
 *
 * The library flows publish `emptyList()` BEFORE their first real value, and
 * [com.slukhayka.audiobooks.ui.MainViewModel.isLibraryLoaded] is the fact that
 * separates «not read yet» from «empty». The old branches read only the list,
 * so a listener with books saw the fresh-install CTAs; and because the block
 * composer also starts at `emptyList()`, the «усі полиці приховано» state
 * could be claimed for a frame before the first composition.
 *
 * [blocksReady] separates "the composer has not published yet" from "the
 * composer legitimately produced zero blocks" (every book dismissed, or no
 * book passes any shelf rule). Without it the second case rendered a header
 * with no manage door forever.
 *
 * Kept as a pure decision so the cold-start pair
 * `(loaded = false, blocksReady = false, allBooksEmpty = true)` is assertable
 * without a ViewModel.
 */
internal enum class ListenContent {
    /** The first real library value has not arrived: render the header only. */
    Loading,

    /** Read, and genuinely empty: the fresh-install CTAs. */
    FreshInstall,

    /** Read, with at least one visible shelf. */
    Shelves,

    /** Read, blocks composed, none visible: the listener's own choice. */
    AllShelvesHidden,
}

internal fun listenContentFor(
    libraryLoaded: Boolean,
    blocksReady: Boolean,
    allBooksEmpty: Boolean,
    composedBlocks: Int,
    dedupedBlocks: Int,
): ListenContent = when {
    // Cold start is NOT an empty library — the #1704 false flash.
    !libraryLoaded || !blocksReady -> ListenContent.Loading
    allBooksEmpty -> ListenContent.FreshInstall
    // After the first real composition zero blocks is a legitimate outcome
    // (all dismissed / none eligible), not "still loading": keep the manage
    // door reachable through AllShelvesHidden.
    composedBlocks == 0 || dedupedBlocks == 0 -> ListenContent.AllShelvesHidden
    else -> ListenContent.Shelves
}
