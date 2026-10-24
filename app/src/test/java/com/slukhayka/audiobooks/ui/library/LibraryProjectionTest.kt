package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1284 — the Книги tab's ONE projection, pinned through its own interface.
 *
 * The screen no longer holds any of these rules: the filter/sort order, the Work
 * grouping, which narration speaks for a card, the form of the entry list and
 * the two index mappings are all [LibraryProjection]'s. So they are pinned here,
 * on the projection, instead of through a screen that used to restate them.
 *
 * ADR-0048 is the spine of the first group: a personal card belongs to the Work,
 * the narrations inside it keep their own Listening State, and changing which
 * narration is primary must not open a second card or merge two positions.
 */
class LibraryProjectionTest {

    private fun entity(
        id: String,
        mergeKey: String,
        narrator: String,
        title: String,
        author: String
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        description = "Фікстура",
        coverDrawableRes = 0,
        genre = "класика",
        sourceUrl = "https://fixtures.4read.org/$id.html"
    ).apply {
        this.mergeKey = mergeKey
        // The entity defaults to the wall clock; a frozen stamp keeps the sort
        // modes deterministic.
        this.createdAt = 0L
    }

    private fun row(
        id: String,
        mergeKey: String = "work-$id",
        narrator: String = "Читець",
        title: String = "Кобзар",
        author: String = "Тарас Шевченко",
        position: Long = 0L,
        total: Long = 100L,
        started: Boolean = position > 0L,
        lastListenedAt: Long = 0L
    ) = LibraryBook(
        book = entity(id, mergeKey, narrator, title, author),
        progress = if (started) {
            PlaybackProgressEntity(
                editionId = "edition-$id",
                bookId = id,
                currentPositionSeconds = position,
                lastListenedAt = lastListenedAt
            )
        } else {
            null
        },
        cumulativePositionSeconds = position,
        totalDurationSeconds = total
    )

    private fun project(
        rows: List<LibraryBook>,
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.TITLE,
        query: String = "",
        gridMode: Boolean = false,
        denseTitle: String = "Усі"
    ) = projectLibraryBooks(rows, filter, sort, query, gridMode, denseTitle)

    // ── ADR-0048: one card per Work ────────────────────────────────────────

    @Test
    fun `two editions of one Work are ONE card and keep both narrations`() {
        val projection = project(
            listOf(
                row("a", "k", narrator = "Іван", position = 90),
                row("b", "k", narrator = "Петро", position = 10)
            )
        )

        assertEquals("one Work is one card", 1, projection.cards.size)
        val card = projection.cards.single()
        assertEquals(setOf("a", "b"), card.narrations.map { it.book.id }.toSet())
        assertTrue(card.hasSeveralNarrations)
        assertEquals(
            "one row per Work reaches the entries",
            listOf("a"),
            projection.shownBooks.map { it.book.id }
        )
        assertEquals(
            "the Work is drawn exactly once",
            listOf("a"),
            projection.entries("").filterIsInstance<LibraryGridEntry.BookEntry>()
                .map { it.book.book.id }
        )
    }

    @Test
    fun `changing the primary narration opens no second card and merges no Listening State`() {
        val aLeads = listOf(
            row("a", "k", narrator = "Іван", position = 90),
            row("b", "k", narrator = "Петро", position = 10)
        )
        val bLeads = listOf(
            row("a", "k", narrator = "Іван", position = 10),
            row("b", "k", narrator = "Петро", position = 90)
        )

        val before = project(aLeads, sort = LibrarySort.RECENTLY_LISTENED)
        val after = project(bLeads, sort = LibrarySort.RECENTLY_LISTENED)

        // The primary really did change…
        assertEquals("a", before.shownBooks.single().book.id)
        assertEquals("b", after.shownBooks.single().book.id)
        // …and the Work is still ONE card on both sides.
        assertEquals(1, before.cards.size)
        assertEquals(1, after.cards.size)
        assertEquals("k", after.cards.single().workKey)
        // Each narration keeps its OWN position: the card never merges them.
        assertEquals(
            listOf("Іван" to 10L, "Петро" to 90L),
            after.cards.single().narrations
                .sortedBy { it.book.id }
                .map { it.book.narrator to it.cumulativePositionSeconds }
        )
    }

    // ── filter / sort ──────────────────────────────────────────────────────

    @Test
    fun `the filter narrows the projection and only a narrowed header summarises`() {
        val rows = listOf(
            row("a", "k1", title = "А", position = 90),
            row("b", "k2", title = "Б", started = false)
        )

        val all = project(rows)
        assertEquals(listOf("a", "b"), all.shownBooks.map { it.book.id })
        assertFalse(
            "the default «Усі» header has never carried a total",
            all.summarisesRemaining
        )

        val listening = project(rows, filter = LibraryFilter.LISTENING, denseTitle = "Слухаю")
        assertEquals(listOf("a"), listening.shownBooks.map { it.book.id })
        assertTrue("a status filter narrows the list, so the header summarises", listening.summarisesRemaining)
        assertEquals(listOf(LibraryGridEntry.Section("Слухаю", 1, "")), listening.entries("") .take(1))

        val searched = project(rows, query = "Б", denseTitle = "Пошук")
        assertEquals(listOf("b"), searched.shownBooks.map { it.book.id })
        assertTrue("a search narrows the list too", searched.summarisesRemaining)

        val blank = project(rows, query = "   ")
        assertFalse("a blank query is not a search", blank.summarisesRemaining)
    }

    @Test
    fun `the sort decides the card order and the entries follow it`() {
        val rows = listOf(
            row("a", "k1", title = "Яблуко", position = 10, lastListenedAt = 5_000L),
            row("b", "k2", title = "Абетка", position = 10, lastListenedAt = 1_000L)
        )

        val byTitle = project(rows, sort = LibrarySort.TITLE)
        assertEquals(listOf("b", "a"), byTitle.shownBooks.map { it.book.id })
        assertEquals(
            "the entries carry the projection's order, behind its one header",
            listOf("b", "a"),
            byTitle.entries("").filterIsInstance<LibraryGridEntry.BookEntry>()
                .map { it.book.book.id }
        )

        val byRecent = project(rows, sort = LibrarySort.RECENTLY_LISTENED)
        assertEquals(listOf("a", "b"), byRecent.shownBooks.map { it.book.id })
    }

    // ── the entry form: list and grid are the same books ───────────────────

    @Test
    fun `list and grid draw the same Works in the same order`() {
        val rows = listOf(
            row("a", "k1", title = "А"),
            row("b", "k2", title = "Б"),
            row("c", "k3", title = "В")
        )

        val list = project(rows, gridMode = false)
        val grid = project(rows, gridMode = true)

        assertEquals(false, list.shape.gridMode)
        assertEquals(true, grid.shape.gridMode)
        assertEquals(list.shownBooks.map { it.book.id }, grid.shownBooks.map { it.book.id })
        assertEquals(
            "the view mode never changes which books the grid draws, or their order",
            list.entries("").filterIsInstance<LibraryGridEntry.BookEntry>()
                .map { it.book.book.id },
            grid.entries("").filterIsInstance<LibraryGridEntry.BookEntry>()
                .map { it.book.book.id }
        )
    }

    // ── the viewport mapping ───────────────────────────────────────────────

    @Test
    fun `the viewport keys are exactly the Works of the shown entries`() {
        val projection = project(
            listOf(
                row("a", "k1", title = "А"),
                row("b", "k2", title = "Б"),
                row("c", "k3", title = "В")
            )
        )
        val entries = projection.entries("")
        assertEquals(
            listOf("library_section_Усі", "a", "b", "c"),
            entries.map { it.key }
        )

        assertEquals(listOf("k1", "k2", "k3"), libraryWorkKeysAt(entries, listOf(1, 2, 3)))
        assertEquals(
            "the header is chrome: it never claims a Work",
            emptyList<String>(),
            libraryWorkKeysAt(entries, listOf(0))
        )
        assertEquals(
            "a stale index past the end contributes nothing",
            emptyList<String>(),
            libraryWorkKeysAt(entries, listOf(99))
        )
        assertEquals(
            "the mapping follows the given order",
            listOf("k3", "k1"),
            libraryWorkKeysAt(entries, listOf(3, 1))
        )
    }

    @Test
    fun `a filtered-out row is not in the projection, so its Work leaves the queue`() {
        val rows = listOf(
            row("a", "k1", title = "А", position = 50),
            row("b", "k2", title = "Б", started = false)
        )

        val narrowed = project(rows, filter = LibraryFilter.LISTENING, denseTitle = "Слухаю")
        val entries = narrowed.entries("")

        assertEquals(listOf("k1"), libraryWorkKeysAt(entries, entries.indices.toList()))
        assertEquals(
            "the unstarted row has left the projection entirely",
            listOf("a"),
            entries.filterIsInstance<LibraryGridEntry.BookEntry>().map { it.book.book.id }
        )
    }

    // ── rows without a full Work identity ──────────────────────────────────

    @Test
    fun `rows without a Work key are their own cards and get no invented identity`() {
        val rows = listOf(
            row("x", "", title = "Кобзар", author = "Тарас Шевченко"),
            // A second row with the SAME title and author: identity by title
            // must never be invented, so this is a second card, not a Work.
            row("y", "", title = "Кобзар", author = "Тарас Шевченко")
        )

        val projection = project(rows)
        assertEquals("blank keys never share a card", 2, projection.cards.size)
        assertTrue(projection.cards.all { it.workKey.isBlank() })
        assertEquals(
            "neither row has a Work key to queue",
            emptyList<String>(),
            libraryWorkKeysAt(projection.entries(""), projection.entries("").indices.toList())
        )
    }

    @Test
    fun `a keyless local row is drawn but never joins the availability queue`() {
        val rows = listOf(
            row("a", "k1", title = "А"),
            row("local", "", title = "Б")
        )

        val entries = project(rows).entries("")
        assertEquals(
            listOf("a", "local"),
            entries.filterIsInstance<LibraryGridEntry.BookEntry>().map { it.book.book.id }
        )
        assertEquals(
            "only the Work with a key reaches the queue: ADR-0042's budget is unchanged",
            listOf("k1"),
            libraryWorkKeysAt(entries, entries.indices.toList())
        )
    }

    // ── focus anchor ───────────────────────────────────────────────────────

    @Test
    fun `the focus anchor is found whether the book is a row or the browsing hero card`() {
        // The tab builds the dense form, but the shared renderer still draws the
        // browsing one, and the mapping must answer for both — looking only at
        // rows once dropped the focus to the heading.
        val hero = row("hero", "k1", title = "А", position = 90)
        val other = row("other", "k2", title = "Б")
        val browsingEntries = libraryGridEntries(
            browsing = true,
            gridMode = true,
            visible = listOf(hero, other),
            continueBook = hero,
            denseTitle = "Усі"
        )

        val heroIndex = libraryIndexOfBook(browsingEntries, "hero")
        assertTrue(heroIndex >= 0)
        assertEquals("hero", browsingEntries[heroIndex].shownBook?.book?.id)

        val otherIndex = libraryIndexOfBook(browsingEntries, "other")
        assertTrue(otherIndex >= 0)
        assertEquals("other", browsingEntries[otherIndex].shownBook?.book?.id)

        assertEquals(-1, libraryIndexOfBook(browsingEntries, "absent"))
        assertEquals(-1, libraryIndexOfBook(emptyList(), "hero"))
    }

    @Test
    fun `an empty library projects to no cards and no entries`() {
        val projection = project(emptyList())
        assertTrue(projection.cards.isEmpty())
        assertTrue(projection.shownBooks.isEmpty())
        assertTrue(projection.entries("").isEmpty())
        assertTrue(libraryWorkKeysAt(emptyList(), listOf(0, 1)).isEmpty())
    }

    /**
     * #1372 — where `continueBook` comes from once the filter hides it.
     *
     * The rule arrived with the whole-library projection (spec-56 T3, #730) and
     * moved into this module without a test. The resume card is the LIBRARY's
     * resume, not the narrowed view's: it is computed from every row, so a
     * status filter or a search that hides that book must not blank the card.
     *
     * Pinned deliberately, not assumed: an untested move is where behaviour
     * changes without anyone noticing.
     */
    @Test
    fun `continueBook stays the library's resume even when the filter hides it`() {
        val rows = listOf(
            row("a", "k1", title = "А", position = 90, lastListenedAt = 100),
            row("b", "k2", title = "Б", started = false)
        )

        val all = project(rows)
        assertEquals("a", all.continueBook?.book?.id)

        val searched = project(rows, query = "Б")
        assertEquals(listOf("b"), searched.shownBooks.map { it.book.id })
        assertEquals(
            "a filter that hides the resume card's book does not change whose resume it is",
            "a",
            searched.continueBook?.book?.id
        )

        val empty = project(rows, query = "нічого не збігається")
        assertTrue("the narrowed view has nothing to show", empty.shownBooks.isEmpty())
        assertEquals("the resume card survives an empty result", "a", empty.continueBook?.book?.id)
    }

    // ── #1285: the return anchor is the Work, not the narration id ─────────

    @Test
    fun `the return anchor is the book's own entry while it is still primary`() {
        val projection = project(listOf(row("a", "k", position = 90)))
        val entries = projection.entries("")

        val index = projection.indexOfReturnAnchor(entries, "a")

        assertTrue(index >= 0)
        assertEquals("a", entries[index].shownBook?.book?.id)
    }

    @Test
    fun `a changed primary narration keeps the same Work card as the return anchor`() {
        // The listener opened narration "b", then started it: progress makes it
        // the card's voice, so the entries now SHOW "b" while the saved anchor
        // is still "a" — the exact order the ticket describes.
        val before = project(
            listOf(
                row("a", "k", narrator = "Іван", position = 90),
                row("b", "k", narrator = "Петро", position = 10)
            )
        )
        val entriesBefore = before.entries("")
        val anchorBefore = before.indexOfReturnAnchor(entriesBefore, "b")
        assertEquals(
            "before the change the card still speaks with the other narration",
            "a",
            entriesBefore[anchorBefore].shownBook?.book?.id
        )

        val after = project(
            listOf(
                row("a", "k", narrator = "Іван", position = 10),
                row("b", "k", narrator = "Петро", position = 95)
            )
        )
        val entriesAfter = after.entries("")
        val anchorAfter = after.indexOfReturnAnchor(entriesAfter, "a")

        assertTrue("the Work card is still in the projection", anchorAfter >= 0)
        assertEquals(
            "the anchor lands on the card that owns the opened narration",
            "b",
            entriesAfter[anchorAfter].shownBook?.book?.id
        )
    }

    @Test
    fun `a Work hidden by the current filter has no return anchor`() {
        val rows = listOf(
            row("a", "k1", title = "Кобзар", position = 90),
            row("b", "k2", title = "Лісова пісня", position = 10)
        )

        val filtered = project(rows, query = "Кобзар")

        assertEquals(
            "a Work the filter hides must not receive an invented anchor",
            -1,
            filtered.indexOfReturnAnchor(filtered.entries(""), "b")
        )
        assertEquals(
            "the filter itself is untouched",
            listOf("a"),
            filtered.shownBooks.map { it.book.id }
        )
    }

    @Test
    fun `a filter that hides the opened narration still finds its Work card`() {
        // The opened narration "b" no longer matches the search, but the Work
        // card is still visible through narration "a" — membership must be
        // resolved over the whole library, not the filtered rows.
        val rows = listOf(
            row("a", "k", narrator = "Іван", title = "Кобзар", position = 90),
            row("b", "k", narrator = "Петро", title = "Лісова пісня", position = 10)
        )

        val filtered = project(rows, query = "Кобзар")
        val entries = filtered.entries("")
        val anchor = filtered.indexOfReturnAnchor(entries, "b")

        assertTrue("the Work card is visible through its other narration", anchor >= 0)
        assertEquals("a", entries[anchor].shownBook?.book?.id)
    }

    @Test
    fun `an unknown book has no return anchor`() {
        val projection = project(listOf(row("a", "k", position = 90)))

        assertEquals(-1, projection.indexOfReturnAnchor(projection.entries(""), "missing"))
    }
}
