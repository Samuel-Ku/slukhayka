package com.slukhayka.audiobooks.ui.screens

import com.slukhayka.audiobooks.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1383 — «зірки є, але дотик не працює».
 *
 * The average stars are drawn even when the listener cannot rate, and they are
 * read-only: the SAME stars the rater block uses, so they look tappable. The
 * defect was not a broken tap — it was SILENCE about why the tap could not do
 * anything.
 *
 * [narrationRateUnavailableReason] is the one place that decides the message,
 * and the screen feeds it the same three booleans it gates the rater block
 * with. These tests pin the mapping, so the screen can never show an
 * explanation for a gate that is actually open (or stay silent about one that
 * is closed).
 */
class NarrationRateUnavailableReasonTest {

    @Test
    fun `every gate open means no message at all`() {
        assertNull(
            narrationRateUnavailableReason(
                hasRatingsBackend = true,
                hasListenerIdentity = true,
                hasEdition = true,
            ),
        )
    }

    @Test
    fun `the message names the FIRST closed gate`() {
        assertEquals(
            R.string.book_detail_rate_unavailable_no_backend,
            narrationRateUnavailableReason(
                hasRatingsBackend = false,
                hasListenerIdentity = true,
                hasEdition = true,
            ),
        )
        assertEquals(
            R.string.book_detail_rate_unavailable_no_profile,
            narrationRateUnavailableReason(
                hasRatingsBackend = true,
                hasListenerIdentity = false,
                hasEdition = true,
            ),
        )
        assertEquals(
            R.string.book_detail_rate_unavailable_no_edition,
            narrationRateUnavailableReason(
                hasRatingsBackend = true,
                hasListenerIdentity = true,
                hasEdition = false,
            ),
        )
    }

    @Test
    fun `the backend gate wins over the rest`() {
        // Nothing could be stored without a backend, so naming the profile or
        // the edition first would send the listener after the wrong fix.
        assertEquals(
            R.string.book_detail_rate_unavailable_no_backend,
            narrationRateUnavailableReason(
                hasRatingsBackend = false,
                hasListenerIdentity = false,
                hasEdition = false,
            ),
        )
        assertEquals(
            R.string.book_detail_rate_unavailable_no_profile,
            narrationRateUnavailableReason(
                hasRatingsBackend = true,
                hasListenerIdentity = false,
                hasEdition = false,
            ),
        )
    }

    @Test
    fun `every closed gate produces a DIFFERENT message`() {
        // A single shared "unavailable" string would satisfy the ticket's
        // wording and destroy its point: the message must say WHICH gate.
        val backend = narrationRateUnavailableReason(false, true, true)
        val profile = narrationRateUnavailableReason(true, false, true)
        val edition = narrationRateUnavailableReason(true, true, false)
        assertEquals(3, setOf(backend, profile, edition).size)
    }
}
