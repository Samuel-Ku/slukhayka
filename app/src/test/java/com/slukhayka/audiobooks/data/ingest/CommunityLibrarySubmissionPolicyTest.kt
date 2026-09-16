package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0050 / #830 — several pastes, parts into one Edition, observed-only boundaries. */
class CommunityLibrarySubmissionPolicyTest {

    private fun post(
        link: String,
        title: String,
        author: String? = "Автор",
        part: Int? = null,
        tracks: List<TelegramTrack> = emptyList()
    ) = CommunityPostRef(link = link, title = title, author = author, partIndex = part, tracks = tracks)

    @Test
    fun `one link is typically one book`() {
        val groups = CommunityLibrarySubmissionPolicy.group(
            listOf(
                post(
                    "https://t.me/slukhayka/573/1",
                    "Кобзар",
                    tracks = listOf(TelegramTrack(0, "Кобзар"))
                )
            )
        )

        assertEquals(1, groups.size)
        assertEquals("Кобзар", groups.single().title)
        assertEquals(1, groups.single().trackCount)
    }

    @Test
    fun `parts of one book across posts collapse into one group in observed order`() {
        val groups = CommunityLibrarySubmissionPolicy.group(
            listOf(
                post("https://t.me/slukhayka/573/3", "Гіперіон", part = 2),
                post("https://t.me/slukhayka/573/1", "Гіперіон", part = 1),
                post("https://t.me/slukhayka/573/9", "Інша книга", author = "Інший")
            )
        )

        assertEquals(2, groups.size)
        val hyperion = groups.first()
        assertEquals("Гіперіон", hyperion.title)
        assertEquals(
            listOf("https://t.me/slukhayka/573/1", "https://t.me/slukhayka/573/3"),
            hyperion.posts.map { it.link }
        )
        assertEquals("Інша книга", groups[1].title)
    }

    @Test
    fun `a post without an observed part marker sorts after the marked ones`() {
        val groups = CommunityLibrarySubmissionPolicy.group(
            listOf(
                post("no-marker", "Книга"),
                post("part-1", "Книга", part = 1)
            )
        )

        assertEquals(listOf("part-1", "no-marker"), groups.single().posts.map { it.link })
    }

    @Test
    fun `a blank title cannot become a book`() {
        assertTrue(CommunityLibrarySubmissionPolicy.group(listOf(post("x", "   "))).isEmpty())
    }

    @Test
    fun `only observed chapter markers are boundaries`() {
        val tracks = listOf(
            TelegramTrack(0, "Розділ 1"),
            TelegramTrack(1, "Просто трек без мітки"),
            TelegramTrack(2, "Частина 2"),
            TelegramTrack(3, "Chapter 4")
        )

        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(tracks)

        assertEquals(listOf(0, 2, 3), boundaries.map { it.trackIndex })
        assertEquals(listOf(1, 2, 4), boundaries.map { it.chapterNumber })
    }

    @Test
    fun `an unlabelled track is never promoted to a boundary`() {
        val boundaries = CommunityLibrarySubmissionPolicy.observedBoundaries(
            listOf(TelegramTrack(0, "Вступ"), TelegramTrack(1, "Кінець"))
        )

        assertTrue("no observed marker, no boundary", boundaries.isEmpty())
    }
}
