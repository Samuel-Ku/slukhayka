package com.slukhayka.audiobooks.ui.library

/**
 * #1284 — the ONE projection of the Книги tab's book area.
 *
 * The screen used to keep the pipeline as five separate agreements, each local
 * to it: [filterAndSortLibrary] for the order, [workBookCards] for the Work
 * grouping, a `browsing` flag for the entry form, [libraryGridEntries] for the
 * entries, and a raw index lookup for the viewport. Two of them — the `browsing`
 * handed to the entries builder and the `browsing` handed to the renderer — had
 * to be kept in step by hand, and both mappings (a grid index back to a Work, a
 * book id back to its entry) lived inside the screen's effects.
 *
 * [LibraryProjection] concentrates all of it behind one interface, so the
 * listener-facing rules — the filter/sort order, which narrations share a Work
 * card, which narration speaks for it, the shape of the entry list and the two
 * index mappings — have one home and one test surface. It is a pure value: no
 * flow, no ViewModel, no new adapter (ADR-0008), and the scroll/focus EFFECTS
 * stay in the screen, which is the UI adapter this projection exists to keep
 * thin.
 *
 * ADR-0048: a card is the Work, not the rendition — [cards] keeps every
 * narration of a Work together and each keeps its own progress. ADR-0042: the
 * availability queue is driven by the Work keys of exactly the entries in the
 * viewport; [libraryWorkKeysAt] re-expresses that mapping without changing
 * which Works are checked or how often (no new budget).
 */

/**
 * How the Книги tab builds AND draws its entry list.
 *
 * [browsing] is the shape of the list: the browsing form (the resume card, the
 * section headers and the cover shelf) or the dense form (one header, then a row
 * or a tile per Work). The tab ALWAYS builds the dense form (#885 — the wall of
 * shelves belongs to «Полиці»); the browsing form is still drawn by the shared
 * renderer and pinned by the goldens, so the shape stays expressible rather than
 * deleted.
 *
 * [gridMode] is the listener's own list/grid choice and is a separate field on
 * purpose: it changes how a book is drawn, never which books are shown.
 */
data class LibraryBooksShape(val browsing: Boolean, val gridMode: Boolean) {
    companion object {
        /**
         * The Книги tab's shipped shape: the dense form (#885), in the view the
         * listener picked. One named constructor, so the entry builder and the
         * renderer cannot be handed different shapes.
         */
        fun denseTab(gridMode: Boolean): LibraryBooksShape =
            LibraryBooksShape(browsing = false, gridMode = gridMode)
    }
}

/**
 * One projection of the Книги tab's book area.
 *
 * [summarisesRemaining] is a SECOND rule, not the entry form: the dense header
 * carries the remaining-time total only once the listener narrowed the list down
 * (a status filter or a search). The screen used to hold that rule as the same
 * `false` literal written out twice — once for `denseTrailing`, once among the
 * `remember` keys — while the entry form came from a different source; two copies
 * of one fact, free to disagree. That is exactly what this module removes: the
 * default «Усі» header must not grow a total it never had.
 */
class LibraryProjection internal constructor(
    /** The Work-level cards, in the listener's order. */
    val cards: List<WorkBookCard>,
    /** The shape the entries are built with AND the shape the renderer must draw. */
    val shape: LibraryBooksShape,
    /** The resume card's Work, or null — the card the browsing form opens with. */
    val continueBook: LibraryBook?,
    /** Whether the dense header carries the remaining-time total. */
    val summarisesRemaining: Boolean,
    private val denseTitle: String
) {
    /**
     * One row per Work: the narration that speaks for each card.
     *
     * This is the list the entries are built from — never the raw pre-grouping
     * books, so a Work with two narrations cannot become two personal cards
     * (ADR-0048) and cannot be counted twice in the header's total.
     */
    val shownBooks: List<LibraryBook> = cards.map { it.primary }

    /**
     * The entry list, with the dense header's remaining-time total resolved by
     * the caller.
     *
     * [denseTrailing] is a parameter because it is CHROME — a composable read of
     * the string table (spec-46 T08) — while WHETHER the header carries it
     * ([summarisesRemaining]) and WHICH books it describes ([shownBooks]) are
     * this projection's own rules. Splitting it any other way would either put
     * Ukrainian chrome back on the model or need a second copy of the Work
     * grouping to compute the sum.
     */
    fun entries(denseTrailing: String): List<LibraryGridEntry> = libraryGridEntries(
        browsing = shape.browsing,
        gridMode = shape.gridMode,
        visible = shownBooks,
        continueBook = continueBook,
        denseTitle = denseTitle,
        denseTrailing = denseTrailing
    )
}

/**
 * Projects the library's rows into the Книги tab's one [LibraryProjection].
 *
 * The pipeline is the shipped one, in the shipped order: the quick filter and
 * the search, then the sort, then the Work grouping that picks each card's
 * primary narration. Nothing here guesses a Work for a row that has none — a row
 * with a blank merge key stays its own card ([workBookCards]) and gets no
 * invented identity by title or number.
 */
fun projectLibraryBooks(
    books: List<LibraryBook>,
    filter: LibraryFilter,
    sort: LibrarySort,
    query: String,
    gridMode: Boolean,
    denseTitle: String
): LibraryProjection {
    val visible = filterAndSortLibrary(books, filter, sort, query)
    // Spec-56 T3 (#730): the resume card is chosen from the WHOLE library, not
    // from the filtered rows — narrowing the list must not lose the book the
    // listener is in the middle of.
    val continueBook = books.filter { it.isListening }.maxByOrNull { it.lastListenedAt }
    return LibraryProjection(
        cards = workBookCards(visible),
        shape = LibraryBooksShape.denseTab(gridMode),
        continueBook = continueBook,
        // The header summarises only a NARROWED-DOWN list. This was the
        // screen's `browsing` variable; it is not the entry form, and the two
        // are kept apart here on purpose.
        summarisesRemaining = filter != LibraryFilter.ALL || query.isNotBlank(),
        denseTitle = denseTitle
    )
}

/**
 * The Work keys of exactly the Works shown at [indices] — the viewport mapping.
 *
 * It reads the entries THE RENDERER DRAWS, never the pre-grouping book list:
 * that is what keeps a header, the resume card, an emptied filter or a merged
 * Work from shifting the availability queue onto a different Work. A row without
 * a Work key (a local import) contributes nothing, exactly as before, so the
 * check budget ADR-0042 pays for is unchanged.
 */
fun libraryWorkKeysAt(
    entries: List<LibraryGridEntry>,
    indices: Iterable<Int>
): List<String> = indices
    .mapNotNull { index -> entries.getOrNull(index)?.shownBook?.book?.mergeKey }
    .filter { it.isNotBlank() }

/**
 * The index of the entry holding [bookId], or -1 — the focus-return anchor.
 *
 * It asks every entry, not just [LibraryGridEntry.BookEntry]: the book may be
 * the browsing form's resume card, and looking only at rows once dropped the
 * focus to the heading (the regression [LibraryGridEntry.showsBook] exists for).
 */
fun libraryIndexOfBook(entries: List<LibraryGridEntry>, bookId: String): Int =
    entries.indexOfFirst { it.showsBook(bookId) }
