package com.slukhayka.audiobooks.data.entries

/**
 * ADR-0046 / spec-54 T13 (#863) — the personal library spans ALL formats:
 * one Work, one Library Entry, several **Readthrough**s.
 *
 * A Readthrough is ONE person's pass through ONE Work in ONE [ReadingFormat].
 * It owns its [ReadingState], its dates, its progress journal and its OWN
 * units — pages, percent or seconds are never converted into each other,
 * because a shared percentage across formats would be fiction (ADR-0014).
 *
 * An AUDIO Readthrough points at the existing Edition and nothing else: the
 * live player position stays in Listening State and is never duplicated here
 * (ADR-0046 §3). PAPER and EBOOK create neither an Edition nor a Source — the
 * format is not an Edition (§4).
 */
enum class ReadingFormat {
    /** The existing audio narration (an Edition). */
    AUDIO,

    /** A physical book: counted in pages. */
    PAPER,

    /** An electronic book: its own units, no Edition. */
    EBOOK
}

/** The owning unit of one format — never mixed with another format's. */
enum class ReadingUnit { SECONDS, PAGES, PERCENT }

/**
 * Why we DELIBERATELY have no `percentOf(otherUnits)` here: ADR-0046 §5.
 * A unit is stored with the unit it was observed in; conversion would invent
 * a truth nobody observed.
 */
data class ReadingUnits(val unit: ReadingUnit, val value: Int) {
    init {
        require(value >= 0) { "a reading unit can never be negative" }
    }
}

/** ADR-0046 §6 — completion belongs to the READTHROUGH, not to the Work. */
enum class ReadingState { PLANNED, IN_PROGRESS, FINISHED, ABANDONED }

/** One journaled progress record of one Readthrough. */
data class ReadthroughProgressEntry(
    val at: Long,
    val units: ReadingUnits
)

/** ADR-0046 §2 — one pass through one Work in one format. */
data class Readthrough(
    val id: String,
    /** The Library Entry this pass belongs to (ADR-0047: Work-level). */
    val libraryEntryId: String,
    val workId: String,
    val format: ReadingFormat,
    val state: ReadingState,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** AUDIO only — the existing Edition; null for every other format. */
    val editionId: String? = null,
    val units: ReadingUnits,
    /** Newest last; the journal is the honest history, never rewritten. */
    val journal: List<ReadthroughProgressEntry> = emptyList()
)

/**
 * The pure rules of a Readthrough: which formats may carry an Edition, what
 * finishing means, and how a re-read is created without erasing the previous
 * pass. The Room carrier and the migration land next (#863); keeping these
 * rules pure is what makes them testable before any row exists.
 */
object ReadthroughPolicy {

    /** ADR-0046 §3/§4 — the ONE format that may (and must) name an Edition. */
    fun editionAllowed(format: ReadingFormat): Boolean = format == ReadingFormat.AUDIO

    /** ADR-0046 §5 — the unit each format is observed in. */
    fun unitFor(format: ReadingFormat): ReadingUnit = when (format) {
        ReadingFormat.AUDIO -> ReadingUnit.SECONDS
        ReadingFormat.PAPER -> ReadingUnit.PAGES
        ReadingFormat.EBOOK -> ReadingUnit.PERCENT
    }

    /**
     * @return the new pass, or null when the request contradicts the format
     *   rules (an audio pass without an Edition, or a paper/e-book pass that
     *   tries to name one) — nothing is guessed.
     */
    fun start(
        id: String,
        libraryEntryId: String,
        workId: String,
        format: ReadingFormat,
        startedAt: Long,
        editionId: String? = null,
        value: Int = 0
    ): Readthrough? {
        if (id.isBlank() || libraryEntryId.isBlank() || workId.isBlank()) return null
        if (startedAt <= 0L) return null
        if (editionAllowed(format)) {
            if (editionId.isNullOrBlank()) return null
        } else if (editionId != null) {
            // A paper/e-book pass never names an Edition (ADR-0046 §4).
            return null
        }
        return Readthrough(
            id = id,
            libraryEntryId = libraryEntryId,
            workId = workId,
            format = format,
            state = ReadingState.IN_PROGRESS,
            startedAt = startedAt,
            editionId = editionId,
            units = ReadingUnits(unitFor(format), value)
        )
    }

    /**
     * Records progress: the journal gains a record and the pass keeps its
     * state. A FINISHED pass is history — progress goes to a NEW pass.
     */
    fun recordProgress(
        readthrough: Readthrough,
        at: Long,
        value: Int
    ): Readthrough? {
        if (at <= 0L) return null
        if (readthrough.state == ReadingState.FINISHED) return null
        if (readthrough.state == ReadingState.ABANDONED) return null
        val entry = ReadthroughProgressEntry(at, ReadingUnits(readthrough.units.unit, value))
        return readthrough.copy(
            state = ReadingState.IN_PROGRESS,
            units = entry.units,
            journal = readthrough.journal + entry
        )
    }

    /**
     * ADR-0046 §6 — finishing belongs to THIS pass: its state and date change,
     * and no other Readthrough is touched (the caller holds the others).
     */
    fun finish(readthrough: Readthrough, at: Long): Readthrough? {
        if (at <= 0L) return null
        if (readthrough.state == ReadingState.FINISHED) return null
        return readthrough.copy(state = ReadingState.FINISHED, finishedAt = at)
    }

    /**
     * ADR-0046 §6 — a re-read is a NEW pass: same Work and format, new id, and
     * the previous pass stays in history exactly as it was.
     */
    fun restart(
        previous: Readthrough,
        newId: String,
        startedAt: Long
    ): Readthrough? {
        if (newId.isBlank() || newId == previous.id) return null
        if (startedAt <= 0L) return null
        return previous.copy(
            id = newId,
            state = ReadingState.IN_PROGRESS,
            startedAt = startedAt,
            finishedAt = null,
            units = ReadingUnits(previous.units.unit, 0),
            journal = emptyList()
        )
    }
}
