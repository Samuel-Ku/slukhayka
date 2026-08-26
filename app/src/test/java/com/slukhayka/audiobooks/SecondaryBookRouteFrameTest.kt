package com.slukhayka.audiobooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryBookRouteFrameTest {

    @Test
    fun exitedParentCannotClaimAnUnrelatedBookDetail() {
        val staleRoute = SecondaryBookRouteFrame(
            parent = SecondaryBookParent.SERIES,
            originBookId = "book-a",
            detailBookId = "book-a",
            parentTitle = "Series A",
            parentUrl = "/series-a"
        )

        val afterUnrelatedBookOpened = staleRoute.withSelectedDetail(
            selectedBookId = "book-b",
            parentActive = false
        ).afterParentState(parentActive = false, childRouteOpen = false)

        assertEquals(SecondaryBookRouteFrame(), afterUnrelatedBookOpened)
        assertFalse(afterUnrelatedBookOpened.ownsDetail("book-b", parentActive = false))
    }

    @Test
    fun bookOpenedInsideTheOwnedParentReplacesTheCurrentDetailFrame() {
        val route = SecondaryBookRouteFrame(
            parent = SecondaryBookParent.PERSON,
            originBookId = "book-a",
            detailBookId = "book-a",
            parentName = "Авторка",
            parentPath = "/author"
        )

        val nestedBook = route.withSelectedDetail(
            selectedBookId = "book-b",
            parentActive = true
        )

        assertEquals("book-a", nestedBook.originBookId)
        assertEquals("book-b", nestedBook.detailBookId)
        assertTrue(nestedBook.ownsDetail("book-b", parentActive = true))
        assertFalse(nestedBook.ownsDetail("book-a", parentActive = true))
    }
}
