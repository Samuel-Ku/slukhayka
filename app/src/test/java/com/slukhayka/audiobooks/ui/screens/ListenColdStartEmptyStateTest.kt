package com.slukhayka.audiobooks.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1704 — the cold-start regression, asserted at the branch that caused it.
 *
 * `ListenScreen` collects the library with `initial = emptyList()` and the
 * block composer starts empty too, so the FIRST composition sees an empty
 * library and no blocks. The old code read that as «свіжа інсталяція» /
 * «усі полиці приховано». `isLibraryLoaded` is the fact that separates "not
 * read yet" from either claim; these cases pin the separation, including the
 * window after the flag flips but before the composer publishes.
 */
class ListenColdStartEmptyStateTest {

    @Test
    fun `cold start is loading, never the fresh-install empty state`() {
        assertEquals(
            "the initial (unloaded) pair must not claim an empty library",
            ListenContent.Loading,
            listenContentFor(
                libraryLoaded = false,
                blocksReady = false,
                allBooksEmpty = true,
                composedBlocks = 0,
                dedupedBlocks = 0,
            ),
        )
    }

    @Test
    fun `a library with books never flashes fresh install while loading`() {
        assertEquals(
            ListenContent.Loading,
            listenContentFor(
                libraryLoaded = false,
                blocksReady = false,
                allBooksEmpty = false,
                composedBlocks = 0,
                dedupedBlocks = 0,
            ),
        )
    }

    @Test
    fun `the empty library is claimed only after the read`() {
        assertEquals(
            ListenContent.FreshInstall,
            listenContentFor(
                libraryLoaded = true,
                blocksReady = true,
                allBooksEmpty = true,
                composedBlocks = 0,
                dedupedBlocks = 0,
            ),
        )
    }

    @Test
    fun `uncomposed blocks are not read as all shelves hidden`() {
        assertEquals(
            "listenBlocks has not published yet; that is loading, not a choice",
            ListenContent.Loading,
            listenContentFor(
                libraryLoaded = true,
                blocksReady = false,
                allBooksEmpty = false,
                composedBlocks = 0,
                dedupedBlocks = 0,
            ),
        )
    }

    @Test
    fun `a ready composer with zero blocks is all shelves hidden, not loading`() {
        assertEquals(
            "all dismissed / no eligible book is a legitimate empty composer",
            ListenContent.AllShelvesHidden,
            listenContentFor(
                libraryLoaded = true,
                blocksReady = true,
                allBooksEmpty = false,
                composedBlocks = 0,
                dedupedBlocks = 0,
            ),
        )
    }

    @Test
    fun `all shelves hidden needs composed blocks with nothing deduped`() {
        assertEquals(
            ListenContent.AllShelvesHidden,
            listenContentFor(
                libraryLoaded = true,
                blocksReady = true,
                allBooksEmpty = false,
                composedBlocks = 9,
                dedupedBlocks = 0,
            ),
        )
        assertEquals(
            ListenContent.Shelves,
            listenContentFor(
                libraryLoaded = true,
                blocksReady = true,
                allBooksEmpty = false,
                composedBlocks = 9,
                dedupedBlocks = 2,
            ),
        )
    }
}
