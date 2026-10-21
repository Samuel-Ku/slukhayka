package com.slukhayka.audiobooks.data.achievements

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * #1175 (US42) — one library row that came through a source: which book it
 * brought, which door it came through (the persisted `sources.type`) and WHEN
 * that row arrived.
 *
 * [addedAt] is the arrival of the BOOK, not the appearance of the source. The
 * source's own date lives in the Source Registry (`SourceFacts.appearedOn`) —
 * the ONE holder of that fact (ADR-0038) — which is exactly why this award
 * needs no Room column and no migration.
 */
data class SourceArrival(val bookId: String, val sourceType: String, val addedAt: Long)

/**
 * #1175 (US42) — «Нова хвиля»: a book that arrived through a source while that
 * source was still NEW.
 *
 * The window runs FROM the source's recorded appearance: an arrival is inside
 * it while the distance between the two DATES is between zero and
 * [WINDOW_DAYS] days. Both ends are recorded facts — the source's appearance
 * (`SourceFacts.appearedOn`) and the book's arrival (`sources.addedAt`) — so
 * nothing here reads "now" and a book that qualified yesterday still qualifies
 * tomorrow, exactly like the vintage and comeback awards (#703).
 *
 * An undated source contributes NOTHING. Fifteen registered sources predate the
 * decision that records appearance dates, and their field stays empty on
 * purpose; the reader hands back exactly what the carrier says, so "nobody
 * recorded it" is never read as "today" or as "any arrival counts" (ADR-0014).
 * The award stays visible in «Попереду» and opens with the first dated source.
 *
 * A date the reader cannot confirm — an id the registry does not know, an empty
 * field — is unknown in the same way and counts no book.
 */
object NewWave {

    /** The window the owner set (#701 decision): thirty days from the appearance. */
    const val WINDOW_DAYS = 30L

    /**
     * How many DIFFERENT books arrived inside a source's own window.
     *
     * The unit is the book, like every other completion/start count in this
     * module: two rows of one book (two doors, one of them new) are one book the
     * listener got during a source's first month, not two.
     *
     * [zoneId] decides which calendar day an arrival instant belongs to, and it
     * is a parameter rather than a global for the same reason the night and
     * holiday awards take one: a machine's zone must not decide an award.
     *
     * An arrival EARLIER than the appearance (a contradictory pair: the book
     * predates the source) is not inside the window either — it is not proof of
     * anything, and the `0..` floor is what keeps it out.
     */
    fun books(arrivals: List<SourceArrival>, appearedOn: (String) -> LocalDate?, zoneId: ZoneId): Long =
        arrivals.filter { arrival ->
            val appeared = appearedOn(arrival.sourceType) ?: return@filter false
            val arrived = Instant.ofEpochMilli(arrival.addedAt).atZone(zoneId).toLocalDate()
            ChronoUnit.DAYS.between(appeared, arrived) in 0..WINDOW_DAYS
        }.map { it.bookId }.distinct().size.toLong()
}
