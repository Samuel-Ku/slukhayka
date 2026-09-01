package com.slukhayka.audiobooks.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogBrowserFocusReturnTest {
    @Test
    fun `focus token appears only after browser closes and is consumed by its card`() {
        CatalogBrowserFocusReturn.consume("book-a")
        CatalogBrowserFocusReturn.remember("book-a")
        assertNull(CatalogBrowserFocusReturn.returnCardKey.value)

        CatalogBrowserFocusReturn.publishAfterBrowserClose()
        assertEquals("book-a", CatalogBrowserFocusReturn.returnCardKey.value)

        CatalogBrowserFocusReturn.consume("book-b")
        assertEquals("book-a", CatalogBrowserFocusReturn.returnCardKey.value)
        CatalogBrowserFocusReturn.consume("book-a")
        assertNull(CatalogBrowserFocusReturn.returnCardKey.value)
    }
}
