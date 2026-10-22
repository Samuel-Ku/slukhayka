package com.slukhayka.audiobooks.data.entries

/**
 * ADR-0047 / spec-54 T09 (#867) — how a personal link to a Work BEGAN. It is a
 * fact about the record, never a judgement about the book.
 *
 * The distinction that matters: an EXPLICIT action by the listener (a save, a
 * deliberate import of their own file or folder, actually starting playback)
 * versus an AUTOMATIC pass (a catalogue sync, a technical card open). An
 * automatic pass creates NO PERSONAL link (ADR-0063): [AUTO_SEED],
 * [CATALOG_SYNC] and [TECHNICAL_OPEN] mark hidden anchors or legacy rows, and
 * no personal surface or award metric reads them. And, for rows that predate
 * the fact itself, an honest [UNKNOWN]: the data simply does not say, so
 * nothing is guessed.
 */
enum class LibraryEntryOrigin {
    /** The listener saved the book on purpose. */
    EXPLICIT_SAVE,

    /** The listener imported their own file or folder on purpose. */
    EXPLICIT_IMPORT,

    /** The catalogue filled it in automatically (авто-сід). */
    AUTO_SEED,

    /** A catalogue sync added it without the listener asking (legacy row). */
    CATALOG_SYNC,

    /**
     * ADR-0063 — a Work materialized from the catalogue mirror so its page
     * and player can work before the listener saves it. Not a personal link
     * and not a triage candidate: it is mechanics, not a choice. The import
     * default writes this HIDDEN anchor (the Work link hangs on it), which no
     * personal surface or award metric reads; an explicit save or actual
     * playback upgrades it to `EXPLICIT_SAVE`.
     */
    TECHNICAL_OPEN,

    /**
     * The row predates the recorded origin and nothing in the data proves how
     * it began. These live in the «Імпортоване» subsection until the listener
     * decides — never rewritten into an auto-seed by guesswork.
     */
    UNKNOWN
}

/**
 * ADR-0047 §2 — the personal rule in the ONE form SQL can read.
 *
 * `library_entries.origin` stores the enum by NAME, so a query that asks for
 * personal rows needs those names as a SQL list. It lives beside
 * [LibraryEntryOriginPolicy.isPersonal] so the two cannot drift, and the
 * achievement queries interpolate it instead of spelling the pair out again
 * (`LibraryEntryOriginPolicyTest` pins the two forms to each other).
 */
const val PERSONAL_ORIGIN_SQL_LIST = "'EXPLICIT_SAVE','EXPLICIT_IMPORT'"

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
        LibraryEntryOrigin.TECHNICAL_OPEN -> true
        LibraryEntryOrigin.AUTO_SEED,
        LibraryEntryOrigin.CATALOG_SYNC,
        LibraryEntryOrigin.UNKNOWN -> false
    }

    /**
     * ADR-0047 §4 — the «Імпортоване» subsection holds exactly the links whose
     * origin the data does NOT recover. An auto-seed or a technical open is
     * not "imported": it is a known automatic state and simply is not
     * personal (ADR-0063).
     */
    fun needsTriage(origin: LibraryEntryOrigin): Boolean =
        origin == LibraryEntryOrigin.UNKNOWN

    /**
     * The listener confirmed the row as personal: it becomes an EXPLICIT save
     * and moves to its Reading-State shelf. The Work, the Edition and the
     * Source are untouched (ADR-0009).
     */
    fun confirmAsPersonal(origin: LibraryEntryOrigin): LibraryEntryOrigin = when (origin) {
        LibraryEntryOrigin.EXPLICIT_SAVE, LibraryEntryOrigin.EXPLICIT_IMPORT -> origin
        else -> LibraryEntryOrigin.EXPLICIT_SAVE
    }

    /**
     * ADR-0063 §2 — does an import door with this origin make the link
     * PERSONAL? (A technical door still writes the hidden anchor so the Work
     * stays reachable; this rule answers only where the listener's shelf
     * reads from.)
     *
     * This is the ONE rule the import core reads, so a new door cannot mean
     * personal by accident.
     */
    fun writesLibraryEntry(origin: LibraryEntryOrigin): Boolean = isPersonal(origin)

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
