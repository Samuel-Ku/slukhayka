package com.slukhayka.audiobooks.data.achievements

/**
 * #701 — one OWN library book with the Work identity the curated rules read.
 *
 * «Власний» is the ADR-0060 rule, not a synonym for "in the library": the row
 * was really saved or imported by the listener (EXPLICIT_SAVE /
 * EXPLICIT_IMPORT). A book that merely arrived with the catalogue
 * (AUTO_SEED / CATALOG_SYNC / UNKNOWN) never reaches this row, so it can
 * neither complete a series nor cover a curated collection — a catalogue
 * mirror is not a listener's choice (ADR-0047).
 *
 * [completedAt] is the FIRST real end-of-book event for this book
 * (`playback_events.kind='COMPLETED'`), which is what ADR-0060 accepts as
 * completion; a hand-set «Прослухано» writes no such event and never finishes
 * anything here. It is null for a book the listener has not finished.
 *
 * The identity fields are the Work's own: [title]/[author] are the canonical
 * bibliographic pair the curated matchers already read (falling back to the
 * book row for a local import that has no Work), and [seriesTitle],
 * [seriesUrl], [seriesIndex] are the Work's series claim — the same fields the
 * cycles shelf, the universes and the series page read.
 */
data class OwnLibraryBook(
    val bookId: String,
    val workId: String,
    val title: String,
    val author: String,
    val seriesTitle: String?,
    val seriesUrl: String?,
    val seriesIndex: Int?,
    val completedAt: Long?
)
