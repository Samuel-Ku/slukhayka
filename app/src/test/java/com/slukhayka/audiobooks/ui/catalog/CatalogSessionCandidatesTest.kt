package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSessionCandidatesTest {
    private val source = SourceEntity(
        id = "4read-edition",
        bookId = "work",
        editionId = "edition",
        type = "4read",
        url = "https://4read.org/book"
    )

    @Test
    fun `browser source with session tries session then keeps browser fallback`() {
        val candidates = catalogSessionCandidates(source, SourceAccessMode.BROWSER, true)

        assertEquals(
            listOf(
                SourceSelectionCoordinator.SourceCategory.UNKNOWN,
                SourceSelectionCoordinator.SourceCategory.BROWSER
            ),
            candidates.map { it.category }
        )
        assertEquals(listOf("edition", "edition"), candidates.map { it.source.editionId })
    }

    @Test
    fun `browser source without session never performs a hidden request`() {
        val candidates = catalogSessionCandidates(source, SourceAccessMode.BROWSER, false)

        assertEquals(
            listOf(SourceSelectionCoordinator.SourceCategory.BROWSER),
            candidates.map { it.category }
        )
    }

    @Test
    fun `one durable source session is reusable for two books then expiry restores browser door`() {
        val firstBook = source.copy(id = "sluhay-first", bookId = "work-1", url = "https://sluhay.com/first")
        val secondBook = source.copy(id = "sluhay-second", bookId = "work-2", url = "https://sluhay.com/second")

        assertEquals(
            listOf(SourceSelectionCoordinator.SourceCategory.UNKNOWN, SourceSelectionCoordinator.SourceCategory.BROWSER),
            catalogSessionCandidates(firstBook, SourceAccessMode.BROWSER, true).map { it.category }
        )
        assertEquals(
            listOf(SourceSelectionCoordinator.SourceCategory.UNKNOWN, SourceSelectionCoordinator.SourceCategory.BROWSER),
            catalogSessionCandidates(secondBook, SourceAccessMode.BROWSER, true).map { it.category }
        )
        assertEquals(
            listOf(SourceSelectionCoordinator.SourceCategory.BROWSER),
            catalogSessionCandidates(secondBook, SourceAccessMode.BROWSER, false).map { it.category }
        )
    }
}
