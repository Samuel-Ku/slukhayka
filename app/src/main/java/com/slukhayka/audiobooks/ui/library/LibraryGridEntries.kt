package com.slukhayka.audiobooks.ui.library

/**
 * UI (v1.5 review) — one item of the Медіатека grid.
 *
 * The grid is no longer a flat list of books: while the listener is browsing
 * the whole library it also carries the «Продовжити» card, the section headers
 * and a cover shelf. Keeping that structure as data (instead of inline
 * branches inside the lazy builder) is what lets the screen map a *grid index*
 * back to a *book* — the focus-return scroll and the availability-jump queue
 * both depend on that mapping being exact.
 */
sealed interface LibraryGridEntry {
    /** The book in progress right now, with the resume CTA. */
    data class Continue(val book: LibraryBook) : LibraryGridEntry

    /** An uppercase section header with its pluralised count. */
    data class Section(
        val title: String,
        val count: Int,
        val trailing: String = ""
    ) : LibraryGridEntry

    /** A full-width, horizontally scrolling shelf of cover tiles. */
    data class Shelf(val books: List<LibraryBook>) : LibraryGridEntry

    /** One book, rendered as a row or a tile depending on the view mode. */
    data class BookEntry(val book: LibraryBook) : LibraryGridEntry

    /** A stable key for the lazy grid; book ids are unique per screen. */
    val key: String
        get() = when (this) {
            is Continue -> "library_continue"
            is Section -> "library_section_$title"
            is Shelf -> "library_shelf"
            is BookEntry -> book.book.id
        }
}

/**
 * Builds the Медіатека grid.
 *
 * - **Browsing** (no status filter, no query): the resume card, then the books
 *   grouped by what they are to the listener — in progress, unstarted (a cover
 *   shelf in list mode), finished.
 * - **Narrowed down** (a status filter or a search): nothing but books, under a
 *   single header — the dense mode.
 *
 * [LibraryBook.isNew], [LibraryBook.isListening] and [LibraryBook.isCompleted]
 * are exhaustive and mutually exclusive (pinned by the unit test next to this
 * file), so a book can never fall out of the grid — and [continueBook] is
 * lifted out of the «Слухаю» section so no book is shown twice.
 *
 * Pure on purpose: the grouping and the «exactly once» guarantee are unit
 * tested without a screen.
 */
internal fun libraryGridEntries(
    browsing: Boolean,
    gridMode: Boolean,
    visible: List<LibraryBook>,
    continueBook: LibraryBook?,
    denseTitle: String,
    denseTrailing: String = ""
): List<LibraryGridEntry> {
    if (!browsing) {
        return buildList {
            if (visible.isNotEmpty()) {
                add(LibraryGridEntry.Section(denseTitle, visible.size, denseTrailing))
            }
            visible.forEach { add(LibraryGridEntry.BookEntry(it)) }
        }
    }
    return buildList {
        if (continueBook != null) add(LibraryGridEntry.Continue(continueBook))

        fun section(title: String, books: List<LibraryBook>, asShelf: Boolean = false) {
            if (books.isEmpty()) return
            add(LibraryGridEntry.Section(title, books.size))
            if (asShelf) {
                add(LibraryGridEntry.Shelf(books))
            } else {
                books.forEach { add(LibraryGridEntry.BookEntry(it)) }
            }
        }

        // The book on the resume card is already the first thing on the
        // screen; the section lists the rest of what is in progress.
        val heroId = continueBook?.book?.id
        section(
            "Слухаю",
            visible.filter { it.isListening && it.book.id != heroId }
        )
        section("Нові", visible.filter { it.isNew }, asShelf = !gridMode)
        section("Завершені", visible.filter { it.isCompleted })
    }
}
