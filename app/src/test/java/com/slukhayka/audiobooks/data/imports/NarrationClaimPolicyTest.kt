package com.slukhayka.audiobooks.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0037 §4 (spec-49 T3) — the pure Narration Claim policy: what counts
 * as «та сама начитка» and who wins the narrator. The stored narrator is
 * the Edition's identity (ADR-0010); the found card's narrator is a
 * claim of its source page. The listener's claim has the listener's
 * precedence (like Metadata Override); the chapter-count agreement is
 * NEVER evidence of identity — that is fabrication (ADR-0014).
 *
 * Pure JVM: the import door and the book-page UI both ask this policy,
 * so the same narration agrees on the same edition everywhere.
 */
class NarrationClaimPolicyTest {

    private val known = NarrationClaimPolicy.Narration(narrator = "Іван Франко")
    private val found = NarrationClaimPolicy.Narration(narrator = "Степан Бандура")

    // ------------------------------------------------------------------
    // Is a claim possible at all?
    // ------------------------------------------------------------------

    @Test
    fun `claim possible when narrator known only on the found side`() {
        // The ADR-0037 §4 case: the stored side never knew the narrator,
        // the found page asserts one — the one-tap claim exists.
        val claim = NarrationClaimPolicy.claimable(
            NarrationClaimPolicy.Narration(narrator = ""),
            found
        )
        assertTrue(claim.available)
        assertEquals(found.narrator, claim.foundNarrator)
    }

    @Test
    fun `two named narrators that differ are two narrations - no claim`() {
        val claim = NarrationClaimPolicy.claimable(known, found)
        assertFalse(claim.available)
        // The found claim is still the found claim — shown as its page's
        // assertion, never silently merged in.
        assertEquals(found.narrator, claim.foundNarrator)
    }

    @Test
    fun `no claim when found side names no narrator`() {
        // A blank claim stays blank — nothing is ever invented (ADR-0014).
        val claim = NarrationClaimPolicy.claimable(known, NarrationClaimPolicy.Narration(narrator = ""))
        assertFalse(claim.available)
        assertNull(claim.foundNarrator)
    }

    @Test
    fun `no claim when narrators already agree`() {
        val same = NarrationClaimPolicy.Narration(narrator = known.narrator)
        assertFalse(NarrationClaimPolicy.claimable(known, same).available)
    }

    @Test
    fun `claim compares names case-insensitively with whitespace trimmed`() {
        val sloppy = NarrationClaimPolicy.Narration(narrator = "  іван франко ")
        assertFalse(NarrationClaimPolicy.claimable(known, sloppy).available)
    }

    @Test
    fun `claim does not depend on chapter topology`() {
        // A chapter-count match is never identity evidence: the policy type
        // carries no chapter field at all — no topology coupling exists.
        assertEquals(0, NarrationClaimPolicy.Claim::class.members.count { it.name == "chapterCount" })
        assertEquals(0, NarrationClaimPolicy.Claim::class.members.count { it.name == "sameByChapterCount" })
    }

    // ------------------------------------------------------------------
    // Same-named narrators auto-anchor; unknown stays unknown
    // ------------------------------------------------------------------

    @Test
    fun `same named narrators anchor the same edition`() {
        val foundOther = NarrationClaimPolicy.Narration(narrator = "іван франко")
        val verdict = NarrationClaimPolicy.sameNarration(known, foundOther)
        assertTrue(verdict)
    }

    @Test
    fun `two unknown narrators anchor the same edition - blank never invents`() {
        // Both sides unknown (blank): the one narration with no named voice
        // is the same rendition — the anchor is agreement, not invention.
        val knownBlank = NarrationClaimPolicy.Narration(narrator = "")
        val foundBlank = NarrationClaimPolicy.Narration(narrator = "  ")
        assertTrue(NarrationClaimPolicy.sameNarration(knownBlank, foundBlank))
    }

    @Test
    fun `unknown on one side only never anchors`() {
        assertFalse(
            NarrationClaimPolicy.sameNarration(
                known,
                NarrationClaimPolicy.Narration(narrator = "")
            )
        )
        assertFalse(
            NarrationClaimPolicy.sameNarration(
                NarrationClaimPolicy.Narration(narrator = ""),
                found
            )
        )
    }

    // ------------------------------------------------------------------
    // The listener's acceptance wins; rejection keeps the sibling
    // ------------------------------------------------------------------

    @Test
    fun `acceptance writes the found claim over the stored narrator`() {
        val stored = NarrationClaimPolicy.StoredNarration(narrator = known.narrator)
        val accepted = NarrationClaimPolicy.accept(stored, found)
        assertEquals(found.narrator, accepted.narrator)
        assertTrue(accepted.claimedByListener)
    }

    @Test
    fun `acceptance normalizes the claim it writes`() {
        val accepted = NarrationClaimPolicy.accept(
            NarrationClaimPolicy.StoredNarration(narrator = known.narrator),
            NarrationClaimPolicy.Narration(narrator = "  Степан   Бандура ")
        )
        assertEquals("Степан Бандура", accepted.narrator)
    }

    @Test
    fun `acceptance is refused when the found claim is blank`() {
        val stored = NarrationClaimPolicy.StoredNarration(narrator = known.narrator)
        val refused = NarrationClaimPolicy.accept(stored, NarrationClaimPolicy.Narration(narrator = " "))
        assertEquals(stored.narrator, refused.narrator)
        assertFalse(refused.claimedByListener)
    }

    @Test
    fun `rejection changes nothing - sibling edition stays untouched`() {
        val stored = NarrationClaimPolicy.StoredNarration(narrator = known.narrator)
        val after = NarrationClaimPolicy.reject(stored)
        assertEquals(stored, after)
    }
}
