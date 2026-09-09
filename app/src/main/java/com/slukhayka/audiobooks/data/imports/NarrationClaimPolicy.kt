package com.slukhayka.audiobooks.data.imports

/**
 * ADR-0037 §4 (spec-49 T3) — the pure Narration Claim policy: what counts
 * as «та сама начитка» and whose narrator wins. The stored narrator is the
 * Edition's identity (ADR-0010); the found card's narrator is a claim of
 * its source page. The listener's acceptance has the listener's precedence
 * (like Metadata Override); a chapter-count agreement is NEVER evidence of
 * identity — that is fabrication (ADR-0014).
 *
 * Pure JVM: the import door and the book-page UI both ask this policy, so
 * every surface agrees on when a claim exists and what acceptance writes.
 */
object NarrationClaimPolicy {

    /** A narration's named voice. Blank = the source names no narrator. */
    data class Narration(val narrator: String)

    /** The narrator as stored on this device's Edition row (ADR-0010). */
    data class StoredNarration(val narrator: String, val claimedByListener: Boolean = false)

    /** The narrator written when the listener accepts the claim. */
    data class AcceptedNarration(val narrator: String, val claimedByListener: Boolean)

    /** Whether the book page may offer «це та сама начитка» — and for whom. */
    data class Claim(
        val available: Boolean,
        /** The found side's claim, exactly as its page asserts it. */
        val foundNarrator: String?
    )

    /**
     * The found card's narrator is the assertion of its source page: absent
     * stays absent (never invented). The claim exists when the narrator is
     * known ONLY on the found side — the exact ADR-0037 §4 case (the 4read
     * side did not know the narrator, the found page does). Two named
     * narrators that differ are two honest narrations — never merged by a
     * claim, never by topology (ADR-0014).
     */
    fun claimable(stored: Narration, found: Narration): Claim {
        val foundName = found.narrator.trim()
        val storedName = stored.narrator.trim()
        val unknownStored = storedName.isEmpty() && foundName.isNotEmpty()
        return Claim(available = unknownStored, foundNarrator = foundName.ifBlank { null })
    }

    /**
     * Do both sides name the same narration? Both named + equal, or both
     * unknown (blank) — agreement, not invention. One named, one blank —
     * never an anchor.
     */
    fun sameNarration(stored: Narration, found: Narration): Boolean {
        val storedName = stored.narrator.trim()
        val foundName = found.narrator.trim()
        return when {
            storedName.isEmpty() && foundName.isEmpty() -> true
            storedName.isEmpty() || foundName.isEmpty() -> false
            else -> namesAgree(storedName, foundName)
        }
    }

    /**
     * The listener's acceptance: the found page's claim fills the Edition's
     * narrator with listener precedence (like Metadata Override).
     */
    fun accept(stored: StoredNarration, found: Narration): AcceptedNarration {
        val claim = found.narrator.trim()
        if (claim.isEmpty()) return AcceptedNarration(stored.narrator, stored.claimedByListener)
        return AcceptedNarration(
            narrator = normalizeClaim(claim),
            claimedByListener = true
        )
    }

    /** Rejection rewrites nothing — the sibling Edition stays untouched. */
    fun reject(stored: StoredNarration): StoredNarration = stored

    private fun namesAgree(a: String, b: String): Boolean =
        normalizeClaim(a).equals(normalizeClaim(b), ignoreCase = true)

    /** One normalization seam: whitespace collapsed, trimmed. */
    private fun normalizeClaim(name: String): String =
        name.trim().replace(Regex("\\s+"), " ")
}
