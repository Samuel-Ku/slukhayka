package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moderation T7 (#840) — the migration is pure, idempotent and never invents. */
class CatalogCardModerationMigrationTest {

    private fun card(
        url: String = "https://www.youtube.com/watch?v=6XIPkMFZf-0",
        title: String = "Острів Дума"
    ) = mapOf<String, Any?>(
        "sourceId" to "youtube",
        "sourceUrl" to url,
        "title" to title,
        "author" to "Стівен Кінг",
        "narrator" to "Олександр",
        "coverUrl" to "https://i.ytimg.com/vi/6XIPkMFZf-0/hq.jpg",
        "durationSeconds" to 5400L,
        "chapterCount" to 2L,
        "observedAt" to 1_700_000_000_000L
    )

    @Test
    fun `an existing card becomes a pending candidate`() {
        val candidate = CatalogCardModerationMigration.candidateFromCard(card())!!

        assertEquals(SubmissionCandidate.State.PENDING, candidate.state)
        assertEquals("Острів Дума", candidate.title)
        assertEquals("Стівен Кінг", candidate.author)
        assertEquals(5400L, candidate.durationSeconds)
        assertEquals(2, candidate.chaptersCount)
        assertEquals(
            "the queue key is the canonical URL's hash",
            "9a83685cbc05c4584046fff1e0a8698a6e6edee376ea86ff55e3489bf4d86c6f",
            candidate.documentId
        )
        assertEquals("no playback verdict existed for a card", 0L, candidate.playedAt)
        assertEquals("the card's own observation moment", 1_700_000_000_000L, candidate.createdAt)
    }

    @Test
    fun `the migration is idempotent - a second run changes nothing`() {
        val first = CatalogCardModerationMigration.candidateFromCard(card())!!
        val second = CatalogCardModerationMigration.candidateFromCard(card())!!

        assertEquals("identical documents, not duplicates", first, second)
        assertEquals(first.documentId, second.documentId)
    }

    @Test
    fun `the synthetic submitter is a hash, never a person`() {
        val candidate = CatalogCardModerationMigration.candidateFromCard(card())!!

        assertEquals(64, candidate.submitterHash.length)
        assertTrue(candidate.submitterHash.all { it in "0123456789abcdef" })
        assertNotEquals("Стівен Кінг", candidate.submitterHash)
        assertTrue(
            "the marker names the migration, not a listener",
            CatalogCardModerationMigration.submitterHashFor(candidate.canonicalUrl) == candidate.submitterHash
        )
    }

    @Test
    fun `two spellings of one link migrate to ONE document`() {
        val short = CatalogCardModerationMigration.candidateFromCard(
            card(url = "https://youtu.be/6XIPkMFZf-0")
        )!!
        val long = CatalogCardModerationMigration.candidateFromCard(card())!!

        assertEquals(
            "the canonical form is what the dedup keys on",
            long.documentId,
            short.documentId
        )
    }

    @Test
    fun `a card without identity is skipped, never invented`() {
        assertNull(CatalogCardModerationMigration.candidateFromCard(card(title = "   ")))
        assertNull(CatalogCardModerationMigration.candidateFromCard(card(url = "")))
        assertNull(CatalogCardModerationMigration.candidateFromCard(emptyMap()))
        assertTrue(!CatalogCardModerationMigration.isMigratable(card(title = "")))
        assertTrue(CatalogCardModerationMigration.isMigratable(card()))
    }

    @Test
    fun `a migrated candidate passes the app's own codec`() {
        val candidate = CatalogCardModerationMigration.candidateFromCard(card())!!
        val encoded = com.slukhayka.audiobooks.data.metadata.SubmissionCandidateCodec.encode(candidate)

        assertEquals(
            "the migration writes exactly what the bot contract expects",
            candidate,
            com.slukhayka.audiobooks.data.metadata.SubmissionCandidateCodec.decode(encoded)
        )
        assertEquals("pending", encoded["state"])
    }
}
