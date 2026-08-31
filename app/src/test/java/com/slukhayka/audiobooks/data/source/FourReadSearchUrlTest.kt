package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-42 #440 — the 4read search door's target URL is pure JVM so the
 * release-accessible pre-filled search can be pinned without a WebView.
 */
class FourReadSearchUrlTest {

    @Test
    fun `encodes the query into the 4read search endpoint`() {
        assertEquals(
            "https://4read.org/index.php?do=search&subaction=search&story=%D0%A1%D0%BD%D0%B8",
            fourReadSearchUrl("Сни")
        )
    }

    @Test
    fun `trims surrounding whitespace before encoding`() {
        assertEquals(
            "https://4read.org/index.php?do=search&subaction=search&story=%D0%A1%D0%BD%D0%B8",
            fourReadSearchUrl("  Сни  ")
        )
    }

    @Test
    fun `spaces in the query encode as plus per form encoding`() {
        // "Кобзар Т" -> the space becomes '+' (application/x-www-form-urlencoded).
        assertEquals(
            "https://4read.org/index.php?do=search&subaction=search&story=%D0%9A%D0%BE%D0%B1%D0%B7%D0%B0%D1%80+%D0%A2",
            fourReadSearchUrl("Кобзар Т")
        )
    }
}
