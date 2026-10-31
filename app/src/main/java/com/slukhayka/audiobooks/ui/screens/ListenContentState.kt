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
 * Kept as a pure decision so the cold-start pair
 * `(loaded = false, allBooksEmpty = true)` is assertable without a ViewModel.
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
    allBooksEmpty: Boolean,
    composedBlocks: Int,
    dedupedBlocks: Int,
): ListenContent = when {
    // Cold start is NOT an empty library — the #1704 false flash.
    !libraryLoaded -> ListenContent.Loading
    allBooksEmpty -> ListenContent.FreshInstall
    // Blocks not composed yet (initial `emptyList()`) is still loading, never
    // «усі полиці приховано»: that state is only true after composition.
    composedBlocks == 0 -> ListenContent.Loading
    dedupedBlocks == 0 -> ListenContent.AllShelvesHidden
    else -> ListenContent.Shelves
}
