package com.slukhayka.audiobooks.data.availability

import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * ADR-0042 §1 (spec-56, ticket #728) — the availability state the library
 * card renders for a problem Work. A Work whose audio is available carries
 * no state at all, so the states exist only for the honest negative cases:
 *
 * - [CHECKING] — nothing observed yet (or a check is in flight); never a
 *   fake "checked" claim.
 * - [NOT_FOUND] — an honest, time-stamped "no source carries this Work".
 * - [FOUND] — a direct counterpart exists; the card offers it by name.
 * - [REFUSED] — the Work's only source is refused (Source Audio Refusal),
 *   distinct from "not found": the difference is why the book looks broken.
 */
enum class AvailabilityStatus { CHECKING, NOT_FOUND, FOUND, REFUSED }

/**
 * The last observed verdict of one Work, persisted across restarts. The
 * state is a projection of the checks that already happen (the replacement
 * mapping memo, the Work index, Source Watch) — there is no separate
 * "source of truth"; [observedAtMs] is the time of the last real check.
 */
data class AvailabilityVerdict(
    val status: AvailabilityStatus,
    val sourceId: String = "",
    val observedAtMs: Long = 0L
)

/** What the card renders for one Work (by mergeKey), or null for no marking. */
data class AvailabilityView(
    val status: AvailabilityStatus,
    val sourceId: String,
    val observedAtMs: Long
)

/**
 * The pure state projection (spec-56 T1): given what the library knows about
 * one Work, decide the card state — or none. Time is supplied by the caller
 * so the rule is JVM-testable without a clock.
 */
object LibraryAvailabilityPolicy {

    /**
     * Whether the Work's own source already carries playable audio, in which
     * case the card stays clean. A blank source URL (sourceless import) or a
     * refused source is NOT available; the availability layer replaces it.
     */
    fun hasAvailableAudio(sourceUrl: String, refusedSources: Set<String>): Boolean {
        if (sourceUrl.isBlank()) return false
        val sourceId = sourceIdForUrl(sourceUrl)
        if (sourceId.isBlank()) return false
        return sourceId !in refusedSources
    }

    /** The refused source of the Work's own URL, when that source is refused. */
    fun refusedSourceId(sourceUrl: String, refusedSources: Set<String>): String? {
        if (sourceUrl.isBlank()) return null
        val sourceId = sourceIdForUrl(sourceUrl)
        return sourceId.takeIf { it.isNotBlank() && it in refusedSources }
    }

    /**
     * The state to render, or null when the card stays clean.
     *
     * A stored verdict without an observation time is not a "checked" claim —
     * it degrades to [AvailabilityStatus.CHECKING] rather than fabricate a
     * fresh-looking verdict. Refusal is a configured fact, not a check, so it
     * renders without a time.
     */
    fun viewFor(
        hasAvailableAudio: Boolean,
        refusedSourceId: String?,
        stored: AvailabilityVerdict?
    ): AvailabilityView? {
        if (hasAvailableAudio) return null
        if (refusedSourceId != null && refusedSourceId.isNotBlank()) {
            return AvailabilityView(
                status = AvailabilityStatus.REFUSED,
                sourceId = refusedSourceId,
                observedAtMs = stored?.observedAtMs ?: 0L
            )
        }
        if (stored == null) {
            return AvailabilityView(AvailabilityStatus.CHECKING, "", 0L)
        }
        return when (stored.status) {
            AvailabilityStatus.CHECKING ->
                AvailabilityView(AvailabilityStatus.CHECKING, "", stored.observedAtMs)
            AvailabilityStatus.FOUND ->
                if (stored.observedAtMs > 0L && stored.sourceId.isNotBlank()) {
                    AvailabilityView(AvailabilityStatus.FOUND, stored.sourceId, stored.observedAtMs)
                } else {
                    AvailabilityView(AvailabilityStatus.CHECKING, "", 0L)
                }
            AvailabilityStatus.NOT_FOUND ->
                if (stored.observedAtMs > 0L) {
                    AvailabilityView(AvailabilityStatus.NOT_FOUND, "", stored.observedAtMs)
                } else {
                    AvailabilityView(AvailabilityStatus.CHECKING, "", 0L)
                }
            AvailabilityStatus.REFUSED ->
                AvailabilityView(AvailabilityStatus.REFUSED, stored.sourceId, stored.observedAtMs)
        }
    }
}
