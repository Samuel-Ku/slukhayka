package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #830 AC2 (second half) — the chapter boundaries a post OBSERVED.
 *
 * The gap analysis on #830 found `ObservedChapterBoundary` referenced once in
 * `main` and **zero times** in `test`. The sibling half,
 * `ChannelPostPattern`, was covered in #1083; this is the other one.
 *
 * The rule this exists to protect is in the type's own name: a boundary is
 * something the post **observed**, never something we guessed. The tests below
 * pin the two ways that can go wrong — inventing a boundary for an unmarked
 * track, and silently RENUMBERING one the post already numbered.
 */
class ObservedChapterBoundaryTest {

    private fun track(title: String, index: Int = 0) = TelegramTrack(index = index, title = title)

    // ------------------------------------------------------------------
    // The post's own numbering is the truth.
    // ------------------------------------------------------------------

    @Test
    fun `the marker's own number is kept, not renumbered`() {
        // The post may label «Розділ 7» FIRST. Renumbering it to 1 would
        // overwrite what the post actually said with our assumption.
        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            listOf(track("Розділ 7", index = 0))
        )

        assertEquals(listOf(ObservedChapterBoundary(trackIndex = 0, chapterNumber = 7)), boundaries)
    }

    @Test
    fun `a track without a marker contributes no boundary`() {
        // Never a guess: an unmarked title means the post observed nothing.
        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            listOf(track("Вступ", index = 0), track("Розділ 1", index = 1))
        )

        assertEquals(1, boundaries.size)
        assertEquals(1, boundaries.single().chapterNumber)
    }

    @Test
    fun `the track index is the ORIGINAL position, so gaps stay visible`() {
        // Only marked tracks appear, and each keeps its own index. Compacting
        // the indices would hide which track the boundary belonged to.
        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            listOf(
                track("Вступ", index = 0),
                track("Розділ 1", index = 1),
                track("Подяки", index = 2),
                track("Розділ 2", index = 3)
            )
        )

        assertEquals(listOf(1, 3), boundaries.map { it.trackIndex })
        assertEquals(listOf(1, 2), boundaries.map { it.chapterNumber })
    }

    // ------------------------------------------------------------------
    // The marker spellings the post actually uses.
    // ------------------------------------------------------------------

    @Test
    fun `every marker spelling is recognised`() {
        val titles = listOf(
            "Розділ 3", "глава 4", "Частина №5", "chapter 6", "part#7", "РОЗДІЛ 8"
        )

        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            titles.mapIndexed { i, t -> track(t, index = i) }
        )

        assertEquals(listOf(3, 4, 5, 6, 7, 8), boundaries.map { it.chapterNumber })
    }

    @Test
    fun `an empty track list yields no boundaries`() {
        assertTrue(CommunityLibrarySubmissionPolicy.observedBoundaries(emptyList()).isEmpty())
    }

    @Test
    fun `a marker not at the start is NOT a boundary`() {
        // Current behaviour, pinned deliberately: the marker is anchored to the
        // START of the title. «Слухайка — Розділ 3» therefore contributes
        // nothing, even though a human would read a chapter number there.
        //
        // This test exists so the behaviour is a decision rather than an
        // accident: if someone widens the regex, this fails and they have to
        // say why — either «that WAS a boundary we missed» or «no, a marker
        // buried in a book title is not a chapter claim».
        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            listOf(track("Слухайка — Розділ 3", index = 0))
        )

        assertTrue("the marker is anchored to the start of the title", boundaries.isEmpty())
    }

    @Test
    fun `a marker without a number contributes no boundary`() {
        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            listOf(track("Розділ", index = 0), track("Розділ без номера", index = 1))
        )

        assertTrue("a number is required — «Розділ» alone claims nothing", boundaries.isEmpty())
    }
}
