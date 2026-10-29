package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1589 (lever 2) — the witness for "match once per library snapshot".
 *
 * The snapshot flow ticks on every written playback second, but the library
 * only changes when a book or its completion event changes. The rule under
 * witness is the circuit, not the matching arithmetic: the derivation counter
 * below must stay at 1 across sixty identical emissions, and must move when
 * the library content really changes.
 *
 * A counter in the TEST is deliberate: production code carries no test-only
 * field, and the same [deriveOwnLibraryFacts] rule is composed by the real
 * source. Mutation `recompute-per-tick` (drop the leading
 * `distinctUntilChanged`) makes the first test fail — that run is the pin's
 * red evidence.
 */
class OwnLibraryFactsTest {

    private fun book(id: String, completedAt: Long? = null) = OwnLibraryBook(
        bookId = id,
        workId = "work-$id",
        title = "Title $id",
        author = "Author",
        seriesTitle = null,
        seriesUrl = null,
        seriesIndex = null,
        completedAt = completedAt
    )

    @Test
    fun `sixty identical progress ticks derive the library facts once`() = runTest {
        val library = listOf(book("a"), book("b"))
        var derivations = 0

        val facts = flow {
            repeat(60) { emit(library) }
        }.ownLibraryFacts { own ->
            derivations++
            deriveOwnLibraryFacts(own, emptyList(), emptyList())
        }.toList()

        assertEquals(
            "60 identical 1 Hz ticks must match the library once, not once per tick",
            1,
            derivations
        )
        assertEquals("an unchanged library has one fact snapshot", 1, facts.size)
    }

    @Test
    fun `a changed library snapshot derives again`() = runTest {
        val before = listOf(book("a"), book("b"))
        val after = listOf(book("a", completedAt = 100L), book("b"))
        var derivations = 0

        flow {
            emit(before)
            emit(before.toList())
            emit(after)
        }.ownLibraryFacts { own ->
            derivations++
            deriveOwnLibraryFacts(own, emptyList(), emptyList())
        }.toList()

        assertEquals("two distinct library contents must derive twice", 2, derivations)
    }
}
