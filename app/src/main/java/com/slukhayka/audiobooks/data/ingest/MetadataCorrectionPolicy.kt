package com.slukhayka.audiobooks.data.ingest

/**
 * Spec-53 T7 — the listener's correction of a badly parsed book. The edit is
 * deliberately narrow: title, author and narrator are the three claims a
 * YouTube/TG parse gets wrong, and they are DISPLAY claims only. Identity —
 * the Work mergeKey and the Edition id — is never part of a correction, so a
 * fixed title can never fork a second Work or lose the listening state
 * (ADR-0007/0010).
 *
 * #855 (T2) adds the cover to the same form: the listener's own URL, or a
 * cleared field meaning «no cover» — the metadata Override the cover
 * resolvers then have to respect.
 *
 * Honesty rules (ADR-0014):
 * - a blank title is refused: the card would lose its name;
 * - a blank author/narrator is ALLOWED and means "this claim was invented —
 *   remove it", which is why the policy returns nullable-free strings and the
 *   caller persists them verbatim;
 * - the cover follows the same rule: a blank URL removes it honestly, and the
 *   app never substitutes a placeholder image.
 */
object MetadataCorrectionPolicy {

    /**
     * The display claims the listener may fix. Null means "unchanged" — for
     * the cover that also means "this form does not edit the cover at all".
     */
    data class Edit(
        val title: String? = null,
        val author: String? = null,
        val narrator: String? = null,
        /** #855 (T2) — null = unchanged, blank = «no cover». */
        val coverUrl: String? = null
    )

    /** The claims to persist — what the listener sees everywhere afterwards. */
    data class Correction(
        val title: String,
        val author: String,
        val narrator: String,
        /** #855 (T2) — null = no cover (honest absence, never a placeholder). */
        val coverUrl: String? = null,
        /**
         * #855 (T2) — whether the cover is part of THIS correction (it really
         * changed). The caller pins the Override only then, so editing a title
         * never freezes a cover the listener did not touch.
         */
        val coverChanged: Boolean = false
    )

    /** Refusal reasons, so the UI can explain rather than silently do nothing. */
    enum class Refusal { BLANK_TITLE, NOTHING_TO_CHANGE }

    sealed interface Outcome {
        data class Corrected(val correction: Correction) : Outcome
        data class Refused(val reason: Refusal) : Outcome
    }

    /**
     * Applies one edit on top of the stored claims. Unchanged fields keep the
     * stored value; a field that is present but blank clears it (except the
     * title). Returns [Outcome.Refused] when there is nothing to write.
     */
    fun apply(
        currentTitle: String,
        currentAuthor: String,
        currentNarrator: String,
        edit: Edit,
        /** #855 (T2) — the cover the card carries right now, if any. */
        currentCoverUrl: String? = null
    ): Outcome {
        val title = edit.title?.trim() ?: currentTitle.trim()
        if (title.isBlank()) return Outcome.Refused(Refusal.BLANK_TITLE)

        val author = edit.author?.trim() ?: currentAuthor.trim()
        val narrator = edit.narrator?.trim() ?: currentNarrator.trim()
        val storedCover = currentCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
        // A null edit leaves the cover alone; a blank one really clears it,
        // which is why the cover counts as a change of its own (a listener may
        // fix ONLY the cover). The branch is explicit: «cleared» and
        // «untouched» are different answers, and an elvis chain would merge
        // them back into the stored value.
        val cover = if (edit.coverUrl != null) edit.coverUrl.trim().ifEmpty { null } else storedCover
        val coverChanged = edit.coverUrl != null && cover != storedCover

        if (title == currentTitle.trim() &&
            author == currentAuthor.trim() &&
            narrator == currentNarrator.trim() &&
            !coverChanged
        ) {
            return Outcome.Refused(Refusal.NOTHING_TO_CHANGE)
        }
        return Outcome.Corrected(
            Correction(
                title = title,
                author = author,
                narrator = narrator,
                coverUrl = cover,
                coverChanged = coverChanged
            )
        )
    }
}
