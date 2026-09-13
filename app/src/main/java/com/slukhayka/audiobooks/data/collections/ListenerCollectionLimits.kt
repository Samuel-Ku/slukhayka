package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#689) — the text hygiene of a listener collection, enforced on the
 * WRITE path. Mirrors [com.slukhayka.audiobooks.data.reviews.ListenerReviewLimits]:
 * the bounds live in one place, the free tier stays bounded, and a hostile or
 * corrupt document is a miss, never a crash.
 *
 * Hygiene is deliberately blunt: links never survive (a collection is a
 * curation, not a spam carrier) and markdown is never rendered, so whatever a
 * listener types is stored as plain text and shown as plain text.
 */
object ListenerCollectionLimits {

    /** A collection title — real titles are short. */
    const val MAX_TITLE_LEN = 80

    /** A collection description — a paragraph, not an essay. */
    const val MAX_DESCRIPTION_LEN = 600

    /** Why this book belongs here — one line. */
    const val MAX_REASON_LEN = 200

    private val URL_PATTERN = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    /**
     * The canonical form of a free-text field: links removed, whitespace
     * collapsed, then truncated to [maxLen]. Never null — an empty string is
     * the honest "nothing was written".
     */
    fun clean(text: String?, maxLen: Int): String {
        if (text.isNullOrBlank()) return ""
        val withoutLinks = URL_PATTERN.replace(text, " ")
        val collapsed = withoutLinks.replace(Regex("""\s+"""), " ").trim()
        return if (collapsed.length <= maxLen) collapsed else collapsed.take(maxLen).trimEnd()
    }

    fun cleanTitle(title: String?): String = clean(title, MAX_TITLE_LEN)

    fun cleanDescription(description: String?): String = clean(description, MAX_DESCRIPTION_LEN)

    fun cleanReason(reason: String?): String = clean(reason, MAX_REASON_LEN)

    /** A collection must carry a real title; everything else is optional. */
    fun isWritableTitle(title: String?): Boolean = cleanTitle(title).isNotBlank()
}
