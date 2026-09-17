package com.slukhayka.audiobooks.data.entries

/**
 * ADR-0047 / spec-54 T09 (#867) — how a personal link to a Work BEGAN. It is a
 * fact about the record, never a judgement about the book.
 *
 * The distinction that matters: an EXPLICIT action by the listener (a save, a
 * deliberate import of their own file or folder) versus an AUTOMATIC pass (the
 * catalogue seed, a catalogue sync). And, for rows that predate the fact
 * itself, an honest [UNKNOWN]: the data simply does not say, so nothing is
 * guessed.
 */
enum class LibraryEntryOrigin {
    /** The listener saved the book on purpose. */
    EXPLICIT_SAVE,

    /** The listener imported their own file or folder on purpose. */
    EXPLICIT_IMPORT,

    /** The catalogue filled it in automatically (авто-сід). */
    AUTO_SEED,

    /** A catalogue sync added it without the listener asking. */
    CATALOG_SYNC,

    /**
     * The row predates the recorded origin and nothing in the data proves how
     * it began. These live in the «Імпортоване» subsection until the listener
     * decides — never rewritten into an auto-seed by guesswork.
     */
    UNKNOWN
}

/**
 * ADR-0047 §2–§4 — the pure rules of intent. «Мої книги» builds on links that
 * began EXPLICITLY; an auto-seeded Work stays reachable through the catalogue
 * mirror but never pretends to be a personal choice; and a row whose origin is
 * unknown waits in «Імпортоване» with two honest actions.
 */
object LibraryEntryOriginPolicy {

    /** ADR-0047 §2 — does this link belong on the personal shelves? */
    fun isPersonal(origin: LibraryEntryOrigin): Boolean = when (origin) {
        LibraryEntryOrigin.EXPLICIT_SAVE,
        LibraryEntryOrigin.EXPLICIT_IMPORT -> true
        LibraryEntryOrigin.AUTO_SEED,
        LibraryEntryOrigin.CATALOG_SYNC,
        LibraryEntryOrigin.UNKNOWN -> false
    }

    /**
     * ADR-0047 §4 — the «Імпортоване» subsection holds exactly the links whose
     * origin the data does NOT recover. An auto-seed is not "imported": it is
     * a known automatic pass and simply is not personal.
     */
    fun needsTriage(origin: LibraryEntryOrigin): Boolean =
        origin == LibraryEntryOrigin.UNKNOWN

    /**
     * The listener confirmed the row as personal: it becomes an EXPLICIT save
     * and moves to its Reading-State shelf. The Work, the Edition and the
     * Source are untouched (ADR-0009).
     */
    fun confirmAsPersonal(origin: LibraryEntryOrigin): LibraryEntryOrigin = when (origin) {
        LibraryEntryOrigin.UNKNOWN -> LibraryEntryOrigin.EXPLICIT_SAVE
        else -> origin
    }

    /**
     * The triage bucket for a whole library, in the listener's own order.
     * ADR-0047 §4: the subsection EMPTIES once every row is decided — the
     * function is the reason, not a UI filter.
     */
    fun <T> triageSection(
        entries: List<T>,
        originOf: (T) -> LibraryEntryOrigin
    ): List<T> = entries.filter { needsTriage(originOf(it)) }

    /** ADR-0047 §4 — the two explicit actions of the triage subsection. */
    enum class TriageAction {
        /** Keep the link and mark it personal (it moves to its Reading-State shelf). */
        CONFIRM_PERSONAL,

        /**
         * Drop the LINK. The Work, the Edition and the Source stay exactly
         * where they were (ADR-0009/§5) — the listener removes an intent, not
         * a book.
         */
        REMOVE_LINK
    }

    /**
     * @return the origin after the action, or null when the link itself is
     *   gone — either way the triage bucket no longer holds this row.
     */
    fun afterTriage(origin: LibraryEntryOrigin, action: TriageAction): LibraryEntryOrigin? =
        when (action) {
            TriageAction.CONFIRM_PERSONAL -> confirmAsPersonal(origin)
            TriageAction.REMOVE_LINK -> null
        }
}
