package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1185 T6 / ADR-0043 §5 — the receiver only ever plays proxy URLs over HTTP,
 * so a book with any local address refuses the cast takeover with an
 * explanation instead of starting a session that cannot play.
 */
class CastAvailabilityTest {

    @Test
    fun `a streamed book is castable`() {
        assertNull(
            CastAvailability.refusal(
                listOf(
                    "https://lihtar.in.ua/audio/0.mp3",
                    "http://example.org/audio/1.mp3"
                )
            )
        )
    }

    @Test
    fun `a local book is refused with an explanation`() {
        assertEquals(
            CastAvailability.LOCAL_BOOK_EXPLANATION,
            CastAvailability.refusal(listOf("content://tree/books/0.mp3"))
        )
        assertEquals(
            CastAvailability.LOCAL_BOOK_EXPLANATION,
            CastAvailability.refusal(listOf("file:///data/user/0/copies/0.mp3"))
        )
    }

    @Test
    fun `a mixed book is refused whole instead of silently skipping local chapters`() {
        assertEquals(
            CastAvailability.LOCAL_BOOK_EXPLANATION,
            CastAvailability.refusal(
                listOf(
                    "https://lihtar.in.ua/audio/0.mp3",
                    "content://tree/books/1.mp3"
                )
            )
        )
    }

    @Test
    fun `a null chapter is not a local one - the no-stream explanation owns it`() {
        assertNull(CastAvailability.refusal(emptyList()))
        assertNull(CastAvailability.refusal(listOf(null, "https://example.org/a.mp3")))
    }
}
