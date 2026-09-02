package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #471 (spec #462, Implementation Decision 8) — the pure «розумний retry»
 * decision: (a) локальний файл розділу існує → грати з нього; (b) інакше
 * один обмежений ре-резолв джерел; (c) нічого не знайдено → чесне
 * «Книга недоступна» + явні браузерні двері для ЛЮБОГО BROWSER джерела.
 */
class SmartRetryPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- (a) локальний файл перемагає -------------------------------------

    @Test
    fun `a real downloaded file is a ready local copy`() {
        val file = tmp.newFile("chapter01.mp3")
        file.writeBytes(ByteArray(512))
        assertTrue(SmartRetryPolicy.localFileReady(file.absolutePath))
    }

    @Test
    fun `a missing or empty local path never counts as local`() {
        assertFalse(SmartRetryPolicy.localFileReady(null))
        assertFalse(SmartRetryPolicy.localFileReady(""))
        assertFalse(SmartRetryPolicy.localFileReady("/nonexistent/missing.mp3"))
        // The buildMediaItem threshold: a stub file is not a local copy.
        val stub = tmp.newFile("stub.mp3")
        stub.writeBytes(ByteArray(50))
        assertFalse(SmartRetryPolicy.localFileReady(stub.absolutePath))
    }

    @Test
    fun `регресія — скачана книга грає після смерті remote`() {
        // The local file wins UNCONDITIONALLY: even with the re-resolve memo
        // exhausted, the retry plays the downloaded copy instead of knocking
        // on the dead remote.
        assertEquals(
            SmartRetryPolicy.Decision.PlayLocal,
            SmartRetryPolicy.decide(localFileReady = true, canReResolve = true)
        )
        assertEquals(
            SmartRetryPolicy.Decision.PlayLocal,
            SmartRetryPolicy.decide(localFileReady = true, canReResolve = false)
        )
    }

    // --- (b) ре-резолв на retry -------------------------------------------

    @Test
    fun `without a local copy a fresh memo allows one re-resolve`() {
        assertEquals(
            SmartRetryPolicy.Decision.ReResolve,
            SmartRetryPolicy.decide(localFileReady = false, canReResolve = true)
        )
    }

    // --- (c) чесна відмова -------------------------------------------------

    @Test
    fun `an exhausted memo surfaces the honest unavailability`() {
        assertEquals(
            SmartRetryPolicy.Decision.Unavailable,
            SmartRetryPolicy.decide(localFileReady = false, canReResolve = false)
        )
    }

    // --- явні браузерні двері ---------------------------------------------

    @Test
    fun `only BROWSER sources get a door`() {
        val doors = SmartRetryPolicy.browserDoorSourceIds(
            listOf("4read", "soundbooks", "sluhayua", "audiobookmp3", "lihtar", "sluhay", "sluhayknigi", "local")
        )
        assertEquals(listOf("4read", "sluhay", "sluhayknigi"), doors)
    }

    @Test
    fun `doors are distinct and drop blank ids`() {
        val doors = SmartRetryPolicy.browserDoorSourceIds(listOf("4read", "4read", "", "sluhay"))
        assertEquals(listOf("4read", "sluhay"), doors)
    }

    @Test
    fun `a direct-only book has no browser door`() {
        assertTrue(
            SmartRetryPolicy.browserDoorSourceIds(listOf("soundbooks", "sluhayua", "lihtar")).isEmpty()
        )
    }

    // --- SmartRetryMemo: обмежена послідовність спроб (ADR-0019) ----------

    @Test
    fun `a failed re-resolve blocks the automatic retry for the negative window`() {
        var now = 1_000_000L
        val memo = SmartRetryMemo(clock = { now })
        assertTrue(memo.canAttempt("book-1"))
        memo.recordFailure("book-1")
        // Immediately after the failure: bounded, no loop.
        assertFalse(memo.canAttempt("book-1"))
        now += CatalogAvailabilityPolicy.POSITIVE_TTL_MS // well past 15 min
        assertTrue(memo.canAttempt("book-1"))
    }

    @Test
    fun `a succeeded re-resolve is not repeated while fresh`() {
        var now = 1_000_000L
        val memo = SmartRetryMemo(clock = { now })
        memo.recordSuccess("book-1")
        assertFalse("just succeeded — the tracks are fresh", memo.canAttempt("book-1"))
        now += CatalogAvailabilityPolicy.POSITIVE_TTL_MS
        assertTrue("the positive verdict is stale at the exact boundary", memo.canAttempt("book-1"))
    }

    @Test
    fun `the negative window expires at the exact boundary`() {
        var now = 1_000_000L
        val memo = SmartRetryMemo(clock = { now })
        memo.recordFailure("book-1")
        now += CatalogAvailabilityPolicy.NEGATIVE_TTL_MS
        assertTrue("stale at the exact expiry boundary", memo.canAttempt("book-1"))
    }

    @Test
    fun `keys are independent`() {
        val memo = SmartRetryMemo()
        memo.recordFailure("book-1")
        assertTrue(memo.canAttempt("book-2"))
    }
}
