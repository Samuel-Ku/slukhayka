package com.slukhayka.audiobooks.ui.library

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
