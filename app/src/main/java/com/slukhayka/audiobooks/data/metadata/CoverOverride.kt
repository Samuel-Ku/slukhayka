package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.CorrectionKind

/**
 * ADR-0053 / #855 (T2) — the listener's own decision about a Work's cover
 * (Metadata Override), remembered in the EXISTING synced correction memory
 * (`corrections`, kind = FIELD): no new table, no new column, no migration.
 *
 * The rule the milestone pins is precedence, not storage: an Override is
 * STRONGER than any external claim. A pinned cover survives every later
 * resolution (the shared canonical base, the search mirror, the source's own
 * claim on a card), and a pinned ABSENCE is a decision too — the honest «no
 * cover» state must not be filled back in by the next claim, or the
 * listener's fix would silently undo itself on the next refresh.
 *
 * The value grammar is the one the import preview already writes for field
 * edits (`field=value`, see
 * [com.slukhayka.audiobooks.data.imports.ImportPlanner.editBook]):
 * `cover=<url>`, and the bare `cover=` for the explicit absence.
 */
object CoverOverride {

    /** The FIELD name the cover decision rides under. */
    const val FIELD = "cover"

    private const val PREFIX = "$FIELD="

    /** The correction value of one decision; [coverUrl] null = «no cover». */
    fun encoded(coverUrl: String?): String = PREFIX + coverUrl?.trim().orEmpty()

    /** One listener decision; [coverUrl] null = the listener said «no cover». */
    data class Pinned(val coverUrl: String?)

    /**
     * The listener's decision about one Work, or null when they never made
     * one. The NEWEST decision wins: the memory keeps the history (its PK is
     * mergeKey|kind|value), and the latest `updatedAt` is the truth.
     */
    fun pinned(corrections: List<CorrectionEntity>): Pinned? =
        corrections
            .filter { it.kind == CorrectionKind.FIELD && it.value.startsWith(PREFIX) }
            .maxByOrNull { it.updatedAt }
            ?.value
            ?.removePrefix(PREFIX)
            ?.trim()
            ?.let { Pinned(it.ifEmpty { null }) }

    /**
     * The cover the listener must see: a pinned decision beats both values
     * below it, and a pinned ABSENCE beats them with `null` (the card renders
     * no cover honestly). With no decision the local value wins over the
     * claim — the precedence the cover resolvers already implement.
     *
     * The branch is explicit on purpose: a pinned `null` and «no decision» are
     * different answers, and an elvis chain would silently merge them.
     */
    fun over(pinned: Pinned?, local: String?, claimed: String?): String? {
        if (pinned != null) return pinned.coverUrl
        return local?.takeIf { it.isNotBlank() } ?: claimed
    }

    /** Whether an external claim may not touch the row at all. */
    fun blocksWrite(pinned: Pinned?): Boolean = pinned != null
}
