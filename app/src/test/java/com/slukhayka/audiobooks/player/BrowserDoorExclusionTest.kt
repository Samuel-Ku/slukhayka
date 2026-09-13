package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app browser door exists for services behind Cloudflare — never for a
 * scam source. 4read's browser session serves a 52-second scam ad instead of
 * the book, so no door may ever be opened for it (ADR-0037 refusal + the
 * listener's decision of 2026-09-13).
 */
class BrowserDoorExclusionTest {

    @Test
    fun `4read never gets a browser door`() {
        assertTrue(
            "a scam source must never open a browser door",
            SmartRetryPolicy.browserDoorSourceIds(listOf("4read")).isEmpty()
        )
        // Even when it is offered among other sources, it stays out.
        assertEquals(
            emptyList<String>(),
            SmartRetryPolicy.browserDoorSourceIds(listOf("soundbooks", "4read", "lihtar"))
        )
    }

    @Test
    fun `direct sources never get a door either`() {
        assertTrue(SmartRetryPolicy.browserDoorSourceIds(listOf("soundbooks", "lihtar", "audiobookmp3")).isEmpty())
    }
}
