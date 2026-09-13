package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerCollectionLimitsTest {

    @Test
    fun `links never survive hygiene`() {
        assertEquals(
            "Найкращі книжки про магію",
            ListenerCollectionLimits.cleanTitle("Найкращі книжки про магію https://spam.example/x")
        )
        assertEquals(
            "Дивись також",
            ListenerCollectionLimits.cleanTitle("Дивись також www.spam.example")
        )
    }

    @Test
    fun `whitespace collapses and blank becomes empty`() {
        assertEquals("дві words", ListenerCollectionLimits.cleanDescription("  дві \n\n  words  "))
        assertEquals("", ListenerCollectionLimits.cleanDescription("   "))
        assertEquals("", ListenerCollectionLimits.cleanDescription(null))
    }

    @Test
    fun `each field is bounded by its own limit`() {
        val long = "я".repeat(1_000)
        assertEquals(
            ListenerCollectionLimits.MAX_TITLE_LEN,
            ListenerCollectionLimits.cleanTitle(long).length
        )
        assertEquals(
            ListenerCollectionLimits.MAX_DESCRIPTION_LEN,
            ListenerCollectionLimits.cleanDescription(long).length
        )
        assertEquals(
            ListenerCollectionLimits.MAX_REASON_LEN,
            ListenerCollectionLimits.cleanReason(long).length
        )
    }

    @Test
    fun `a title made only of a link is not a writable title`() {
        assertFalse(ListenerCollectionLimits.isWritableTitle("https://spam.example"))
        assertTrue(ListenerCollectionLimits.isWritableTitle("  Космос  "))
    }
}
