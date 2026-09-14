package com.slukhayka.audiobooks.data.ingest

/**
 * Spec-53 T7 — the listener's correction of a badly parsed book. The edit is
 * deliberately narrow: title, author and narrator are the three claims a
 * YouTube/TG parse gets wrong, and they are DISPLAY claims only. Identity —
 * the Work mergeKey and the Edition id — is never part of a correction, so a
 * fixed title can never fork a second Work or lose the listening state
 * (ADR-0007/0010).
 *
 * Honesty rules (ADR-0014):
 * - a blank title is refused: the card would lose its name;
 * - a blank author/narrator is ALLOWED and means "this claim was invented —
 *   remove it", which is why the policy returns nullable-free strings and the
 *   caller persists them verbatim.
 */
object MetadataCorrectionPolicy {

    /** The three display claims the listener may fix. Null means "unchanged". */
    data class Edit(
        val title: String? = null,
        val author: String? = null,
        val narrator: String? = null
    )

    /** The claims to persist — what the listener sees everywhere afterwards. */
    data class Correction(
        val title: String,
        val author: String,
        val narrator: String
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
        edit: Edit
    ): Outcome {
        val title = edit.title?.trim() ?: currentTitle.trim()
        if (title.isBlank()) return Outcome.Refused(Refusal.BLANK_TITLE)

        val author = edit.author?.trim() ?: currentAuthor.trim()
        val narrator = edit.narrator?.trim() ?: currentNarrator.trim()

        if (title == currentTitle.trim() &&
            author == currentAuthor.trim() &&
            narrator == currentNarrator.trim()
        ) {
            return Outcome.Refused(Refusal.NOTHING_TO_CHANGE)
        }
        return Outcome.Corrected(Correction(title = title, author = author, narrator = narrator))
    }
}
