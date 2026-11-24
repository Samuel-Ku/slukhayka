package com.slukhayka.audiobooks.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F32 of the 2026-10-28 device audit — the read-only book ruler drew a tick
 * per chapter (12+ on a real book), which read as a second, nearly identical
 * progress bar next to the chapter seek. The spec asks for ONE seek plus a
 * read-only line that shows the book's THREE parts.
 */
class PlayerBookRulerTest {

    @Test
    fun `the read-only ruler divides the book into exactly three parts`() {
        assertEquals("two dividers make three parts", 2, BOOK_PART_DIVIDERS.size)
        assertEquals(1f / 3f, BOOK_PART_DIVIDERS[0], 1e-6f)
        assertEquals(2f / 3f, BOOK_PART_DIVIDERS[1], 1e-6f)
        assertTrue(
            "every divider must sit inside the book line",
            BOOK_PART_DIVIDERS.all { it > 0f && it < 1f },
        )
    }
}
