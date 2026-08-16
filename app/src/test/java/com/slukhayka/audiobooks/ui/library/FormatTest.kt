package com.slukhayka.audiobooks.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-27 (#184) — pure tests for the UI formatting helpers: the Ukrainian
 * plural rules (BUG-006), the byte-size formatter and the exact-scope copy
 * of the clear-cache confirm dialog (BUG-001). No Android, no Robolectric.
 */
class FormatTest {

    // --- ukPlural (BUG-006) -------------------------------------------------

    @Test
    fun `ukrainian plurals - one few many with the 11-14 exception`() {
        assertEquals("книга", ukPlural(1, "книга", "книги", "книг"))
        assertEquals("книги", ukPlural(2, "книга", "книги", "книг"))
        assertEquals("книги", ukPlural(4, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(5, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(10, "книга", "книги", "книг"))
        // 11–14 are many even though they end in 1–4.
        assertEquals("книг", ukPlural(11, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(14, "книга", "книги", "книг"))
        // 21+ return to the one/few forms.
        assertEquals("книга", ukPlural(21, "книга", "книги", "книг"))
        assertEquals("книги", ukPlural(22, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(25, "книга", "книги", "книг"))
        // 101 behaves like 1, 111–114 like 11–14.
        assertEquals("книга", ukPlural(101, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(111, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(114, "книга", "книги", "книг"))
        assertEquals("книг", ukPlural(0, "книга", "книги", "книг"))
    }

    // --- formatBytes (BUG-001 exact scope) ----------------------------------

    @Test
    fun `bytes format - megabytes below one gigabyte`() {
        assertEquals("0 МБ", formatBytes(0))
        assertEquals("0 МБ", formatBytes(-5))
        assertEquals("1 МБ", formatBytes(1024 * 1024))
        assertEquals("350 МБ", formatBytes(350L * 1024 * 1024))
        assertEquals("1023 МБ", formatBytes(1023L * 1024 * 1024))
    }

    @Test
    fun `bytes format - one decimal gigabyte with the Ukrainian comma from 1 GB`() {
        assertEquals("1,0 ГБ", formatBytes(1024L * 1024 * 1024))
        assertEquals("2,3 ГБ", formatBytes(2_469_396_397L)) // ≈ 2.3 GiB
        assertEquals("16,5 ГБ", formatBytes(16L * 1024 * 1024 * 1024 + 512L * 1024 * 1024))
    }

    // --- clearCacheConfirmText (BUG-001) ------------------------------------

    @Test
    fun `confirm copy quotes the exact book count and size`() {
        val text = clearCacheConfirmText(12, 2_469_396_397L)
        assertEquals(
            "Видалити 12 завантажених книг, 2,3 ГБ? " +
                "Книги залишаться в медіатеці — доведеться завантажити знову.",
            text
        )
    }

    @Test
    fun `confirm copy takes the proper plural for one and few`() {
        val tail = " Книги залишаться в медіатеці — доведеться завантажити знову."
        assertEquals("Видалити 1 завантажену книгу, 350 МБ?$tail", clearCacheConfirmText(1, 350L * 1024 * 1024))
        assertEquals("Видалити 3 завантажені книги, 350 МБ?$tail", clearCacheConfirmText(3, 350L * 1024 * 1024))
        assertEquals("Видалити 21 завантажену книгу, 350 МБ?$tail", clearCacheConfirmText(21, 350L * 1024 * 1024))
        assertEquals("Видалити 11 завантажених книг, 350 МБ?$tail", clearCacheConfirmText(11, 350L * 1024 * 1024))
    }
}
